package com.ai.orange.session;

import static com.ai.orange.db.jooq.Tables.VERDICTS;
import static com.ai.orange.run.RunTestSupport.git;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.events.EventProcessor;
import com.ai.orange.evidence.Evidence;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.harness.FakeClaude;
import com.ai.orange.json.Json;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunExecutors;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.session.SessionJudgement.Poll;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Closing a session's work on real git repositories with a fake {@code claude}: the judge says
 * it is finished, orange checks that (committed, the project's evidence commands, a diff review),
 * integrates it as the conventions say and closes it; a gap goes back into the session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "orange.judge.enabled=true")
@Import(TestcontainersConfiguration.class)
class SessionCloseIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("close-it-");
    /** The judge's fake answer for "this work is finished". */
    static final String FINISHED = "All done. [[fake:judge-verify]]";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        FakeClaude.register(r, RUN_ROOT);
    }

    @Value("${local.server.port}") int port;
    @Autowired EventProcessor processor;
    @Autowired SessionRepository sessions;
    @Autowired SessionJudgement judgement;
    @Autowired SessionWorkRepository work;
    @Autowired SessionClosing closing;
    @Autowired VerdictRepository verdicts;
    @Autowired AttentionRepository attention;
    @Autowired EvidenceRepository evidence;
    @Autowired SpaceService space;
    @Autowired RunRepository runs;
    @Autowired RunsProperties runProps;
    @Autowired RunExecutors executors;
    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;
    final HttpClient http = HttpClient.newHttpClient();

    @TempDir Path tmp;
    Path origin;
    Path repo;
    String sessionId;
    String branch;
    boolean reportDefaultBranch = true;

    @BeforeEach
    void fresh() throws Exception {
        pause.resume();
        sessionId = "close-" + UUID.randomUUID();
        origin = tmp.resolve("origin.git");
        git(tmp, "init", "-q", "--bare", "-b", "main", origin.toString());
        // Each test gets its own project: the repo's path names it.
        repo = tmp.resolve("repo-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "t@e");
        git(repo, "config", "user.name", "T");
        Files.writeString(repo.resolve("README.md"), "# demo\n");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "initial");
        git(repo, "remote", "add", "origin", origin.toString());
        git(repo, "push", "-q", "-u", "origin", "main");
        git(repo, "checkout", "-q", "-b", "feat");
        branch = "feat";
    }

    // ─────────────────────────── helpers ───────────────────────────

    String event(String type, String payloadField, String payloadText) throws Exception {
        String eventId = UUID.randomUUID().toString();
        ObjectNode e = Json.mapper().createObjectNode().put("event_id", eventId).put("harness", "claude-code")
                .put("session_id", sessionId).put("type", type).put("cwd", repo.toString())
                .put("repo_root", repo.toString()).put("worktree", repo.toString()).put("branch", branch)
                .put("default_branch", "main");
        if (!reportDefaultBranch) e.remove("default_branch");
        if (payloadField != null) e.putObject("payload").put(payloadField, payloadText);
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sessions/events"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(e.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(202);
        processor.drain();
        return eventId;
    }

    String prompt(String text) throws Exception {
        return event("user_prompt", "prompt", text);
    }

    String stop() throws Exception {
        return event("stopped", "last_message", FINISHED);
    }

    /** Runs the session's queued runs to their end, each queuing the next, as the scheduler would. */
    int runAll() {
        int n = 0;
        boolean ran = true;
        while (ran) {
            ran = false;
            for (RunKind kind : List.of(RunKind.JUDGE, RunKind.VERIFY, RunKind.DIFF_REVIEW, RunKind.INTEGRATE)) {
                Optional<Run> run = runs.claimNext(kind, runProps.workerId(), runProps.leaseTtl());
                if (run.isEmpty()) continue;
                executors.forKind(kind).orElseThrow().execute(run.get());
                n++;
                ran = true;
            }
        }
        return n;
    }

    Session session() {
        return sessions.find("claude-code", sessionId).orElseThrow();
    }

    void convention(String key, String json) throws Exception {
        space.setConvention(session().projectId(), null, key, Json.mapper().readTree(json), SpaceService.Author.USER);
    }

    void commit(String file, String content) throws Exception {
        Files.createDirectories(repo.resolve(file).getParent());
        Files.writeString(repo.resolve(file), content);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "add " + file);
    }

    List<Run> runsOf(RunKind kind) {
        return runs.findBySession(session().id()).stream().filter(r -> r.kind() == kind).toList();
    }

    List<AttentionItem> open() {
        return attention.findBySession(session().id()).stream()
                .filter(i -> !AttentionItem.RESOLVED.equals(i.status())).toList();
    }

    Poll poll(String stop) {
        return judgement.poll("claude-code", sessionId, stop);
    }

    SessionWork lastWork() {
        return work.findBySession(session().id()).getFirst();
    }

    // ─────────────────────────── closing ───────────────────────────

    @Test
    void finishedWorkIsCheckedReviewedIntegratedAndClosed() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"test -f feature.txt\"]");
        convention("review", "\"always\"");
        commit("feature.txt", "the feature\n");
        String head = git(repo, "rev-parse", "HEAD");
        String stop = stop();

        assertThat(session().status()).isEqualTo(Session.JUDGING);
        assertThat(runAll()).as("judge, verify, diff review, integrate").isEqualTo(4);

        Session s = session();
        assertThat(s.status()).isEqualTo(Session.CLOSED);
        assertThat(s.waitingSince()).isNull();
        assertThat(s.closedReason()).isEqualTo(SessionWork.INTEGRATED);
        assertThat(git(origin, "rev-parse", "main")).as("the base was pushed").isEqualTo(head);
        assertThat(git(repo, "rev-parse", "main")).as("and the local base moved along").isEqualTo(head);
        assertThat(git(repo, "rev-parse", "--abbrev-ref", "HEAD")).as("the session's checkout stays on its branch")
                .isEqualTo("feat");
        assertThat(git(repo, "worktree", "list").lines().count()).as("the scratch checkout is gone").isEqualTo(1);

        List<Evidence> rows = evidence.findBySession(s.id());
        assertThat(rows).extracting(Evidence::kind).containsExactlyInAnyOrder("tests", "diff_review");
        assertThat(rows).allSatisfy(e -> {
            assertThat(e.passed()).isTrue();
            assertThat(e.headSha()).isEqualTo(head);
        });
        SessionWork w = lastWork();
        assertThat(w.status()).isEqualTo(SessionWork.CLOSED);
        assertThat(w.result()).isEqualTo(SessionWork.INTEGRATED);
        assertThat(w.intent()).isEqualTo("Add the feature file");
        assertThat(w.headSha()).isEqualTo(head);
        assertThat(w.note()).contains("integrated onto main").contains("pushed to origin");

        HttpResponse<String> shown = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/sessions/" + s.id() + "/work")).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode view = Json.mapper().readTree(shown.body());
        assertThat(view.path("work").path(0).path("result").asText()).isEqualTo("integrated");
        assertThat(view.path("evidence")).hasSize(2);

        // The hook is told to let the session stop; nothing wants the user.
        Poll verdict = poll(stop);
        assertThat(verdict.state()).isEqualTo(Poll.State.VERDICT);
        assertThat(verdict.kind()).isEqualTo("verify");
        assertThat(open()).isEmpty();

        // The review ran outside the user's checkout, without file tools, on the diff alone.
        Run review = runsOf(RunKind.DIFF_REVIEW).getFirst();
        Path config = RUN_ROOT.resolve(review.id().toString()).resolve("claude-config");
        assertThat(Files.readString(config.resolve("fake-args.json"))).contains("\"--tools\",\"\"")
                .doesNotContain(repo.toString());
        assertThat(Files.readString(config.resolve("fake-prompt.txt"))).contains("Add the feature file")
                .contains("+the feature");
    }

    @Test
    void finishedWorkOutsideTheRiskPathsIsIntegratedWithoutAReview() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"test -f feature.txt\"]");
        commit("feature.txt", "the feature\n");
        stop();

        // The default (risky): the tests are the gate, and nothing here is risky.
        assertThat(runAll()).as("judge, verify, integrate").isEqualTo(3);
        assertThat(runsOf(RunKind.DIFF_REVIEW)).isEmpty();
        assertThat(evidence.findBySession(session().id())).extracting(Evidence::kind).containsExactly("tests");
        assertThat(session().status()).isEqualTo(Session.CLOSED);
        assertThat(lastWork().result()).isEqualTo(SessionWork.INTEGRATED);
    }

    @Test
    void workTouchingARiskPathIsReviewed() throws Exception {
        prompt("Add a migration");
        convention("evidence", "[\"true\"]");
        commit("db/migration/V1__x.sql", "select 1;\n");
        stop();

        assertThat(runAll()).as("judge, verify, diff review, integrate").isEqualTo(4);
        assertThat(runsOf(RunKind.DIFF_REVIEW)).hasSize(1);
        assertThat(lastWork().result()).isEqualTo(SessionWork.INTEGRATED);
    }

    @Test
    void reviewNeverSkipsEvenRiskyWork() throws Exception {
        prompt("Add a migration");
        convention("evidence", "[\"true\"]");
        convention("review", "\"never\"");
        commit("db/migration/V1__x.sql", "select 1;\n");
        stop();

        assertThat(runAll()).as("judge, verify, integrate").isEqualTo(3);
        assertThat(runsOf(RunKind.DIFF_REVIEW)).isEmpty();
        assertThat(lastWork().result()).isEqualTo(SessionWork.INTEGRATED);
    }

    @Test
    void aFailingCheckGoesBackIntoTheSessionAndTheFixClosesIt() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[{\"command\": \"test -f wanted.txt\", \"kind\": \"tests\"}]");
        commit("feature.txt", "the feature\n");
        String stop = stop();
        assertThat(runAll()).as("judge, verify").isEqualTo(2);

        assertThat(session().status()).isEqualTo(Session.VERIFYING);
        assertThat(session().autoContinues()).isEqualTo(1);
        Poll verdict = poll(stop);
        assertThat(verdict.kind()).isEqualTo("continue");
        assertThat(verdict.message()).contains("not done yet").contains("`test -f wanted.txt` failed with exit code 1")
                .contains("It printed nothing.");
        assertThat(evidence.findBySession(session().id())).singleElement().satisfies(e -> assertThat(e.passed()).isFalse());

        assertThat(judgement.acknowledge("claude-code", sessionId, stop)).isTrue();
        assertThat(session().status()).isEqualTo(Session.ACTIVE);
        assertThat(space.current(session().projectId())).as("a finding is not a decision made for the user")
                .noneMatch(i -> SpaceItem.DECISION.equals(i.kind()));
        assertThat(git(origin, "rev-parse", "main")).isNotEqualTo(git(repo, "rev-parse", "HEAD"));

        // The session fixes it and stops again.
        commit("wanted.txt", "here\n");
        stop();
        runAll();
        assertThat(session().status()).isEqualTo(Session.CLOSED);
        assertThat(git(origin, "rev-parse", "main")).isEqualTo(git(repo, "rev-parse", "HEAD"));
    }

    @Test
    void uncommittedWorkIsSentBackBeforeAnythingRuns() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"touch ran.marker\"]");
        commit("feature.txt", "the feature\n");
        Files.writeString(repo.resolve("scratch.txt"), "left behind\n");
        String stop = stop();
        runAll();

        Poll verdict = poll(stop);
        assertThat(verdict.kind()).isEqualTo("continue");
        assertThat(verdict.message()).contains("uncommitted or untracked files").contains("scratch.txt");
        assertThat(runsOf(RunKind.DIFF_REVIEW)).isEmpty();
        assertThat(evidence.findBySession(session().id())).singleElement()
                .satisfies(e -> assertThat(e.command()).isEqualTo("git status --porcelain"));
    }

    @Test
    void theChecksRunInAScratchCheckoutNotInTheUsersTree() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"touch ran.marker && test -f feature.txt\"]");
        commit("feature.txt", "the feature\n");
        stop();
        runAll();

        assertThat(session().status()).isEqualTo(Session.CLOSED);
        assertThat(repo.resolve("ran.marker")).as("the command ran somewhere else").doesNotExist();
    }

    @Test
    void reviewGapsGoBackIntoTheSession() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"true\"]");
        convention("review", "\"always\"");
        commit("feature.txt", "half of it [[fake:review-gaps]]\n");
        String stop = stop();
        assertThat(runAll()).as("judge, verify, diff review").isEqualTo(3);

        Poll verdict = poll(stop);
        assertThat(verdict.kind()).isEqualTo("continue");
        assertThat(verdict.message()).contains("reviewed the diff").contains("Two gaps");
        assertThat(runsOf(RunKind.INTEGRATE)).isEmpty();
        assertThat(evidence.findBySession(session().id())).filteredOn(e -> "diff_review".equals(e.kind()))
                .singleElement().satisfies(e -> assertThat(e.passed()).isFalse());
        assertThat(git(origin, "rev-parse", "main")).isNotEqualTo(git(repo, "rev-parse", "HEAD"));
    }

    @Test
    void aProjectWithoutEvidenceCommandsIsAskedOnceAndOrangeProposesWhatItFound() throws Exception {
        prompt("Add the feature file");
        Files.writeString(repo.resolve("gradlew"), "#!/bin/sh\n");
        commit("feature.txt", "the feature\n");
        String stop = stop();
        assertThat(runAll()).as("judge, verify").isEqualTo(2);

        Session s = session();
        assertThat(s.status()).isEqualTo(Session.STOPPED);
        assertThat(s.waitingReason()).isEqualTo(Session.WAIT_FINISHED);
        assertThat(poll(stop).kind()).isEqualTo("verify");
        SpaceItem proposal = space.current(s.projectId()).stream()
                .filter(i -> SpaceItem.CONVENTION.equals(i.kind())).findFirst().orElseThrow();
        assertThat(proposal.status()).as("orange never puts commands in force itself").isEqualTo(SpaceItem.PROPOSED);
        assertThat(proposal.authorKind()).isEqualTo(SpaceItem.ORANGE);
        assertThat(proposal.body().data()).contains("./gradlew test");
        AttentionItem item = attention.findByKey("project:" + s.projectId() + ":evidence_missing").orElseThrow();
        assertThat(item.body()).contains("./gradlew test").contains(proposal.id().toString());
        assertThat(evidence.findBySession(s.id())).isEmpty();

        // The next finished stop says nothing new.
        prompt("and one more thing");
        stop();
        runAll();
        assertThat(space.current(s.projectId()).stream().filter(i -> SpaceItem.CONVENTION.equals(i.kind()))).hasSize(1);
        assertThat(session().status()).isEqualTo(Session.STOPPED);
    }

    @Test
    void workOnTheBaseBranchIsOnlyPushed() throws Exception {
        git(repo, "checkout", "-q", "main");
        branch = "main";
        prompt("Fix the readme");
        convention("evidence", "[\"test -f README.md\"]");
        commit("README.md", "# demo, fixed\n");
        String head = git(repo, "rev-parse", "HEAD");
        stop();
        assertThat(runAll()).as("judge, verify, integrate: a readme fix needs no review").isEqualTo(3);

        assertThat(session().status()).isEqualTo(Session.CLOSED);
        assertThat(git(origin, "rev-parse", "main")).isEqualTo(head);
        assertThat(git(repo, "rev-parse", "HEAD")).as("nothing moved under the user's tree").isEqualTo(head);
    }

    @Test
    void aSessionBehindOriginOnTheBaseBranchIsToldToCatchUp() throws Exception {
        git(repo, "checkout", "-q", "main");
        branch = "main";
        pushFromElsewhere("other.txt", "someone else\n");
        prompt("Fix the readme");
        convention("evidence", "[\"true\"]");
        commit("README.md", "# demo, fixed\n");
        String stop = stop();
        runAll();

        Poll verdict = poll(stop);
        assertThat(verdict.kind()).isEqualTo("continue");
        assertThat(verdict.message()).contains("origin/main").contains("git pull --rebase origin main");
        assertThat(session().status()).isEqualTo(Session.VERIFYING);
    }

    /** Someone else lands a commit on origin's main. */
    void pushFromElsewhere(String file, String content) throws Exception {
        Path other = tmp.resolve("other-" + UUID.randomUUID().toString().substring(0, 8));
        git(tmp, "clone", "-q", origin.toString(), other.toString());
        git(other, "config", "user.email", "o@e");
        git(other, "config", "user.name", "O");
        Files.writeString(other.resolve(file), content);
        git(other, "add", ".");
        git(other, "commit", "-q", "-m", "elsewhere: " + file);
        git(other, "push", "-q", "origin", "HEAD:main");
    }

    @Test
    void aMovedBaseIsRebasedCheckedAgainAndThenIntegrated() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"test -f feature.txt\"]");
        convention("review", "\"always\"");
        commit("feature.txt", "the feature\n");
        String before = git(repo, "rev-parse", "HEAD");
        pushFromElsewhere("other.txt", "someone else\n");
        stop();
        runAll();

        assertThat(session().status()).isEqualTo(Session.CLOSED);
        String head = git(repo, "rev-parse", "HEAD");
        assertThat(head).as("the session's branch was rebased").isNotEqualTo(before);
        assertThat(repo.resolve("other.txt")).exists();
        assertThat(git(origin, "rev-parse", "main")).isEqualTo(head);
        assertThat(runsOf(RunKind.VERIFY)).as("the commands ran again on the rebased head").hasSize(2);
        assertThat(runsOf(RunKind.DIFF_REVIEW)).as("the review of the same patch carried over").hasSize(1);
        assertThat(evidence.sessionHasPassingDiffReview(session().id(), head)).isTrue();
        assertThat(git(repo, "log", "-1", "--format=%cn")).as("the user's commits stay the user's").isEqualTo("T");
    }

    @Test
    void aConflictWithTheBaseGoesBackIntoTheSession() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"true\"]");
        commit("feature.txt", "mine\n");
        String head = git(repo, "rev-parse", "HEAD");
        pushFromElsewhere("feature.txt", "theirs\n");
        String stop = stop();
        runAll();

        Poll verdict = poll(stop);
        assertThat(verdict.kind()).isEqualTo("continue");
        assertThat(verdict.message()).contains("conflicts in").contains("feature.txt").contains("Rebase onto `main`");
        assertThat(git(repo, "rev-parse", "HEAD")).as("the rebase was aborted").isEqualTo(head);
        assertThat(git(repo, "status", "--porcelain")).isEmpty();
        assertThat(session().status()).isEqualTo(Session.VERIFYING);
    }

    @Test
    void integrationLeftToTheUserClosesTheWorkAndSaysSo() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"true\"]");
        convention("review", "\"always\"");
        convention("integration", "\"ask\"");
        commit("feature.txt", "the feature\n");
        stop();
        assertThat(runAll()).as("judge, verify, diff review").isEqualTo(3);

        assertThat(session().status()).isEqualTo(Session.CLOSED);
        assertThat(lastWork().result()).isEqualTo(SessionWork.VERIFIED);
        assertThat(open()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(SessionClosing.SESSION_INTEGRATION);
            assertThat(i.title()).contains("waits for you to integrate");
        });
        assertThat(git(origin, "rev-parse", "main")).isNotEqualTo(git(repo, "rev-parse", "HEAD"));
    }

    @Test
    void aProjectThatOnlyWantsCommitsNeedsNoCommandsAndNoReview() throws Exception {
        prompt("Add the feature file");
        convention("done", "[\"committed\"]");
        commit("feature.txt", "the feature\n");
        stop();
        assertThat(runAll()).as("judge, verify").isEqualTo(2);

        assertThat(session().status()).isEqualTo(Session.CLOSED);
        assertThat(lastWork().result()).isEqualTo(SessionWork.VERIFIED);
        assertThat(evidence.findBySession(session().id())).isEmpty();
    }

    @Test
    void aPromptAfterACloseOpensNewWorkWithItsOwnIntent() throws Exception {
        prompt("Add the feature file");
        convention("done", "[\"committed\"]");
        commit("feature.txt", "the feature\n");
        stop();
        runAll();
        assertThat(session().status()).isEqualTo(Session.CLOSED);

        prompt("Now document it");
        Session s = session();
        assertThat(s.status()).isEqualTo(Session.ACTIVE);
        assertThat(s.intent()).isEqualTo("Now document it");
        List<SessionWork> all = work.findBySession(s.id());
        assertThat(all).hasSize(2);
        assertThat(all.getFirst().status()).isEqualTo(SessionWork.OPEN);
        assertThat(all.getFirst().intent()).isEqualTo("Now document it");
        assertThat(all.getLast().intent()).isEqualTo("Add the feature file");
    }

    @Test
    void aSessionResumedAfterACloseStillStatesANewIntent() throws Exception {
        prompt("Add the feature file");
        convention("done", "[\"committed\"]");
        commit("feature.txt", "the feature\n");
        stop();
        runAll();
        event("session_ended", "reason", "exit");
        event("session_started", null, null);
        assertThat(session().status()).isEqualTo(Session.ACTIVE);

        prompt("Now document it");
        assertThat(session().intent()).isEqualTo("Now document it");
        assertThat(work.findBySession(session().id()).getFirst().intent()).isEqualTo("Now document it");
    }

    @Test
    void aRepoThatWasNeverClonedStillHasABase() throws Exception {
        // git init plus "remote add" leaves no origin/HEAD, and the reporter then knows no default branch.
        reportDefaultBranch = false;
        prompt("Add the feature file");
        convention("evidence", "[\"test -f feature.txt\"]");
        convention("review", "\"always\"");
        commit("feature.txt", "the feature\n");
        stop();
        assertThat(runAll()).as("judge, verify, diff review, integrate").isEqualTo(4);

        assertThat(lastWork().baseBranch()).isEqualTo("main");
        assertThat(git(origin, "rev-parse", "main")).isEqualTo(git(repo, "rev-parse", "HEAD"));
    }

    @Test
    void aSessionThatCommittedNothingIsNotIntegrated() throws Exception {
        prompt("Explain the readme");
        convention("evidence", "[\"true\"]");
        pushFromElsewhere("other.txt", "someone else\n");
        git(repo, "fetch", "-q", "origin");
        String head = git(repo, "rev-parse", "HEAD");
        stop();
        assertThat(runAll()).as("judge, verify: nothing to review or integrate").isEqualTo(2);

        assertThat(session().status()).isEqualTo(Session.CLOSED);
        assertThat(lastWork().result()).isEqualTo(SessionWork.VERIFIED);
        assertThat(git(repo, "rev-parse", "HEAD")).as("the user's branch was not moved").isEqualTo(head);
    }

    @Test
    void anIntegrationThatLandsAfterTheUserSpokeIsStillOnRecord() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"true\"]");
        convention("review", "\"always\"");
        commit("feature.txt", "the feature\n");
        String head = git(repo, "rev-parse", "HEAD");
        stop();
        for (RunKind kind : List.of(RunKind.JUDGE, RunKind.VERIFY, RunKind.DIFF_REVIEW)) {
            executors.forKind(kind).orElseThrow()
                    .execute(runs.claimNext(kind, runProps.workerId(), runProps.leaseTtl()).orElseThrow());
        }
        Run integrate = runs.claimNext(RunKind.INTEGRATE, runProps.workerId(), runProps.leaseTtl()).orElseThrow();
        // The push went out, and the user spoke before its result was applied.
        git(repo, "push", "-q", "origin", head + ":refs/heads/main");
        prompt("one more thing");
        closing.after(integrate, new com.ai.orange.run.RunResult(integrate.id(), RunStatus.SUCCEEDED, null,
                new com.ai.orange.run.RunOutcome.Integration("integrated", head, true, "updated", List.of(), null),
                null, null, java.math.BigDecimal.ZERO));

        assertThat(session().status()).isEqualTo(Session.ACTIVE);
        SessionWork closed = work.findBySession(session().id()).getFirst();
        assertThat(closed.result()).isEqualTo(SessionWork.INTEGRATED);
        assertThat(closed.headSha()).isEqualTo(head);
        assertThat(closed.note()).contains("spoken to again");
    }

    @Test
    void aPromptNotYetAppliedAlreadyStopsTheIntegration() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"true\"]");
        convention("review", "\"always\"");
        commit("feature.txt", "the feature\n");
        String head = git(repo, "rev-parse", "HEAD");
        stop();
        for (RunKind kind : List.of(RunKind.JUDGE, RunKind.VERIFY, RunKind.DIFF_REVIEW)) {
            executors.forKind(kind).orElseThrow()
                    .execute(runs.claimNext(kind, runProps.workerId(), runProps.leaseTtl()).orElseThrow());
        }
        // Stored, not drained: the session row still says verifying.
        ObjectNode e = Json.mapper().createObjectNode().put("event_id", UUID.randomUUID().toString())
                .put("harness", "claude-code").put("session_id", sessionId).put("type", "user_prompt");
        e.putObject("payload").put("prompt", "wait");
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sessions/events"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(e.toString())).build(), HttpResponse.BodyHandlers.ofString());

        executors.forKind(RunKind.INTEGRATE).orElseThrow()
                .execute(runs.claimNext(RunKind.INTEGRATE, runProps.workerId(), runProps.leaseTtl()).orElseThrow());

        assertThat(git(origin, "rev-parse", "main")).as("nothing was pushed").isNotEqualTo(head);
        processor.drain();
        assertThat(session().status()).isEqualTo(Session.ACTIVE);
    }

    // ─────────────────────────── the user, and time ───────────────────────────

    @Test
    void theUserSpeakingEndsTheCheck() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"true\"]");
        commit("feature.txt", "the feature\n");
        String stop = stop();
        // The judge has spoken; the check is queued.
        executors.forKind(RunKind.JUDGE).orElseThrow()
                .execute(runs.claimNext(RunKind.JUDGE, runProps.workerId(), runProps.leaseTtl()).orElseThrow());
        assertThat(session().status()).isEqualTo(Session.VERIFYING);
        assertThat(poll(stop).state()).isEqualTo(Poll.State.PENDING);

        prompt("wait, one more change");
        assertThat(session().status()).isEqualTo(Session.ACTIVE);
        assertThat(runsOf(RunKind.VERIFY)).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(RunStatus.CANCELLED));
        assertThat(runAll()).isZero();
        assertThat(poll(stop).state()).isEqualTo(Poll.State.NONE);
    }

    @Test
    void aResultForAStopTheSessionHasLeftIsDropped() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"true\"]");
        commit("feature.txt", "the feature\n");
        stop();
        executors.forKind(RunKind.JUDGE).orElseThrow()
                .execute(runs.claimNext(RunKind.JUDGE, runProps.workerId(), runProps.leaseTtl()).orElseThrow());
        Run verify = runs.claimNext(RunKind.VERIFY, runProps.workerId(), runProps.leaseTtl()).orElseThrow();
        prompt("wait, one more change");          // while the check runs

        executors.forKind(RunKind.VERIFY).orElseThrow().execute(verify);

        assertThat(session().status()).isEqualTo(Session.ACTIVE);
        assertThat(runsOf(RunKind.DIFF_REVIEW)).isEmpty();
        assertThat(verdicts.findBySession(session().id())).isEmpty();
    }

    @Test
    void aGapNoHookCollectedReachesTheUser() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"test -f wanted.txt\"]");
        commit("feature.txt", "the feature\n");
        stop();
        runAll();
        dsl.update(VERDICTS).set(VERDICTS.CREATED_AT, OffsetDateTime.now().minusHours(1))
                .where(VERDICTS.SESSION_ID.eq(session().id())).execute();

        assertThat(judgement.sweep()).isEqualTo(1);

        assertThat(session().status()).isEqualTo(Session.STOPPED);
        assertThat(open()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(AttentionItem.SESSION_QUESTION);
            assertThat(i.body()).contains("checked this session's work after it stopped");
            assertThat(i.proposedAnswer()).contains("test -f wanted.txt");
        });
    }

    @Test
    void aCheckWhoseRunIsGoneFallsBackToTheRules() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "[\"true\"]");
        commit("feature.txt", "the feature\n");
        stop();
        executors.forKind(RunKind.JUDGE).orElseThrow()
                .execute(runs.claimNext(RunKind.JUDGE, runProps.workerId(), runProps.leaseTtl()).orElseThrow());
        assertThat(judgement.sweep()).as("a queued check is left to run").isZero();
        runs.cancelQueuedForSession(session().id());

        assertThat(judgement.sweep()).isEqualTo(1);
        assertThat(session().status()).isEqualTo(Session.STOPPED);
    }

    @Test
    void aBrokenEvidenceConventionStopsTheCheckInsteadOfPassingIt() throws Exception {
        prompt("Add the feature file");
        convention("evidence", "{\"not\": \"a list\"}");
        commit("feature.txt", "the feature\n");
        stop();
        runAll();

        assertThat(session().status()).isEqualTo(Session.STOPPED);
        assertThat(runsOf(RunKind.INTEGRATE)).isEmpty();
        JsonNode outcome = Json.mapper().readTree(runsOf(RunKind.VERIFY).getFirst().outcome().data());
        assertThat(outcome.path("status").asText()).isEqualTo("unavailable");
    }
}
