package com.ai.orange.session;

import static com.ai.orange.db.jooq.Tables.SESSIONS;
import static com.ai.orange.db.jooq.Tables.VERDICTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.events.EventProcessor;
import com.ai.orange.harness.FakeClaude;
import com.ai.orange.json.Json;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.run.WorkerRunner;
import com.ai.orange.session.SessionJudgement.Poll;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The session judge, each verdict with a fake {@code claude}: a stop is handed to a judge run,
 * its decision becomes the verdict the stop hook long-polls for, and the lines the judge may not
 * cross are held by orange.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "orange.judge.enabled=true")
@Import(TestcontainersConfiguration.class)
class JudgeIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("judge-it-");
    static final Path REPORTER = Path.of("..", "plugin", "hooks", "orange-report.py").toAbsolutePath().normalize();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        FakeClaude.register(r, RUN_ROOT);
    }

    @Value("${local.server.port}") int port;
    @Autowired EventProcessor processor;
    @Autowired SessionRepository sessions;
    @Autowired SessionJudgement judgement;
    @Autowired VerdictRepository verdicts;
    @Autowired AttentionRepository attention;
    @Autowired ProjectService projects;
    @Autowired SpaceService space;
    @Autowired RunRepository runs;
    @Autowired RunsProperties runProps;
    @Autowired WorkerRunner runner;
    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;
    final HttpClient http = HttpClient.newHttpClient();

    String sessionId;
    String repoRoot;

    @BeforeEach
    void fresh() {
        pause.resume();
        sessionId = "judge-" + UUID.randomUUID();
        repoRoot = "/tmp/judge-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Posts an event the way a reporter does and applies it; returns its event id. */
    String event(String type, String payloadField, String payloadText) throws Exception {
        String eventId = UUID.randomUUID().toString();
        ObjectNode e = Json.mapper().createObjectNode().put("event_id", eventId).put("harness", "claude-code")
                .put("session_id", sessionId).put("type", type).put("cwd", repoRoot).put("repo_root", repoRoot)
                .put("branch", "main");
        if (payloadField != null) e.putObject("payload").put(payloadField, payloadText);
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sessions/events"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(e.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(202);
        processor.drain();
        return eventId;
    }

    String stop(String lastMessage) throws Exception {
        return event("stopped", "last_message", lastMessage);
    }

    /** Runs every queued judge run to its end, as the scheduler would. */
    int runJudges() {
        int n = 0;
        for (Optional<Run> run = claim(); run.isPresent(); run = claim()) {
            runner.execute(run.get());
            n++;
        }
        return n;
    }

    Optional<Run> claim() {
        return runs.claimNext(RunKind.JUDGE, runProps.workerId(), runProps.leaseTtl());
    }

    Session session() {
        return sessions.find("claude-code", sessionId).orElseThrow();
    }

    List<AttentionItem> questions() {
        return attention.findBySession(session().id()).stream()
                .filter(i -> !AttentionItem.RESOLVED.equals(i.status())).toList();
    }

    Poll poll(String eventId) {
        return judgement.poll("claude-code", sessionId, eventId);
    }

    @Test
    void aRoutineQuestionIsAnsweredThroughTheStopHook() throws Exception {
        event("user_prompt", "prompt", "Add retry with backoff");
        String stop = stop("Two designs.\n\nShould I proceed with option 1?");

        assertThat(session().status()).isEqualTo(Session.JUDGING);
        assertThat(questions()).as("the judge decides first; nothing is filed yet").isEmpty();
        assertThat(poll(stop).state()).isEqualTo(Poll.State.PENDING);

        assertThat(runJudges()).isEqualTo(1);
        Run run = runs.findBySession(session().id()).getFirst();
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.tier()).as("judge runs route small and low").isEqualTo("small");
        assertThat(run.effort()).isEqualTo("low");
        String prompt = Files.readString(RUN_ROOT.resolve(run.id().toString()).resolve("claude-config").resolve("fake-prompt.txt"));
        assertThat(prompt).contains("Add retry with backoff").contains("Should I proceed with option 1?")
                .contains("auto-answer: \"routine\"").contains("0 time(s) in a row");

        assertThat(session().autoContinues()).isEqualTo(1);
        Poll verdict = poll(stop);
        assertThat(verdict.state()).isEqualTo(Poll.State.VERDICT);
        assertThat(verdict.kind()).isEqualTo("continue");
        assertThat(verdict.message()).isEqualTo("Yes, proceed with option 1.");
        // Delivered once; the session works again.
        assertThat(poll(stop).state()).isEqualTo(Poll.State.NONE);
        assertThat(session().status()).isEqualTo(Session.ACTIVE);
        assertThat(session().waitingSince()).isNull();
        assertThat(verdicts.findBySession(session().id())).singleElement()
                .satisfies(v -> assertThat(v.status()).isEqualTo(Verdict.DELIVERED));
        assertThat(questions()).isEmpty();

        // What orange decided for the user is on record in the project's space.
        assertThat(space.current(session().projectId())).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(SpaceItem.DECISION);
            assertThat(i.authorKind()).isEqualTo(SpaceItem.ORANGE);
            assertThat(i.status()).isEqualTo(SpaceItem.ACTIVE);
            assertThat(i.body().data()).contains("Yes, proceed with option 1.").contains("It fits the project's goals.");
        });
    }

    @Test
    void theRealStopHookBlocksWithOrangesAnswer() throws Exception {
        boolean python;
        try {
            python = new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (java.io.IOException e) {
            python = false;
        }
        assumeTrue(python, "python3 is not installed");
        Path cwd = Files.createTempDirectory("judge-hook-");
        String hookInput = Json.mapper().createObjectNode().put("session_id", sessionId).put("cwd", cwd.toString())
                .put("hook_event_name", "Stop").put("stop_hook_active", false)
                .put("last_assistant_message", "Should I proceed with option 1?").toString();
        ProcessBuilder pb = new ProcessBuilder("python3", REPORTER.toString());
        pb.environment().put("ORANGE_URL", "http://localhost:" + port);
        pb.environment().put("ORANGE_NODE_DIR", cwd.resolve("node").toString());
        pb.environment().put("ORANGE_HOOK_WAIT", "60");
        pb.environment().remove("ORANGE_API_TOKEN");
        Process hook = pb.start();
        hook.getOutputStream().write(hookInput.getBytes(StandardCharsets.UTF_8));
        hook.getOutputStream().close();

        // The hook has posted its stop and is waiting; orange applies it and judges.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (sessions.find("claude-code", sessionId).isEmpty() && System.nanoTime() < deadline) {
            processor.drain();
            Thread.sleep(100);
        }
        assertThat(session().status()).isEqualTo(Session.JUDGING);
        assertThat(hook.isAlive()).as("the hook waits for the verdict").isTrue();
        runJudges();

        assertThat(hook.waitFor(40, TimeUnit.SECONDS)).isTrue();
        assertThat(hook.exitValue()).isZero();
        JsonNode out = Json.mapper().readTree(hook.getInputStream().readAllBytes());
        assertThat(out.path("decision").asText()).isEqualTo("block");
        assertThat(out.path("reason").asText()).isEqualTo("Yes, proceed with option 1.");
        assertThat(session().status()).isEqualTo(Session.ACTIVE);
    }

    @Test
    void anAnswerBelowTheConfidenceThresholdGoesToTheUserAsAProposal() throws Exception {
        String stop = stop("Option 1 or option 2? [[fake:judge-unsure]]");
        runJudges();

        assertThat(session().status()).isEqualTo(Session.STOPPED);
        assertThat(session().waitingReason()).isEqualTo(Session.WAIT_QUESTION);
        assertThat(session().autoContinues()).isZero();
        assertThat(questions()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(AttentionItem.SESSION_QUESTION);
            assertThat(i.urgency()).isEqualTo(AttentionItem.NORMAL);
            assertThat(i.proposedAnswer()).isEqualTo("Probably option 2.");
        });
        // The hook is told a verdict exists, with nothing to say to the session: it lets the stop happen.
        Poll verdict = poll(stop);
        assertThat(verdict.kind()).isEqualTo("escalate");
        assertThat(verdict.message()).isNull();
        assertThat(space.current(session().projectId())).as("nothing was decided for the user").isEmpty();
    }

    @Test
    void aCriticalDecisionIsEscalatedWithTheJudgesQuestion() throws Exception {
        stop("I will now drop the table. [[fake:judge-escalate]]");
        runJudges();

        assertThat(questions()).singleElement().satisfies(i -> {
            assertThat(i.urgency()).isEqualTo(AttentionItem.URGENT);
            assertThat(i.title()).contains("delete the production table");
            assertThat(i.body()).contains("Needs: The session wants to delete the production table; allow it?");
            assertThat(i.proposedAnswer()).isEqualTo("No, archive it instead.");
        });
        assertThat(session().waitingReason()).isEqualTo(Session.WAIT_QUESTION);
    }

    @Test
    void finishedWorkAndIdleStopsJustStop() throws Exception {
        String stop = stop("All done and committed. [[fake:judge-verify]]");
        runJudges();
        assertThat(session().status()).isEqualTo(Session.STOPPED);
        assertThat(session().waitingReason()).isEqualTo(Session.WAIT_FINISHED);
        assertThat(questions()).isEmpty();
        assertThat(poll(stop).kind()).isEqualTo("verify");

        event("user_prompt", "prompt", "thanks");
        String idle = stop("You're welcome. [[fake:judge-idle]]");
        runJudges();
        assertThat(poll(idle).kind()).isEqualTo("idle");
        assertThat(questions()).isEmpty();
    }

    @Test
    void theUserSpeakingFirstVoidsTheVerdict() throws Exception {
        String stop = stop("Should I proceed?");
        runJudges();
        assertThat(verdicts.findBySession(session().id()).getFirst().status()).isEqualTo(Verdict.PENDING);

        event("user_prompt", "prompt", "no, wait");
        assertThat(verdicts.findBySession(session().id()).getFirst().status()).isEqualTo(Verdict.CANCELLED);
        assertThat(poll(stop).state()).isEqualTo(Poll.State.NONE);
        assertThat(session().status()).isEqualTo(Session.ACTIVE);
        assertThat(session().autoContinues()).as("a user prompt resets the loop guard").isZero();

        // A prompt before the judge even ran: its run never starts.
        stop("Should I proceed now?");
        event("user_prompt", "prompt", "hold on");
        assertThat(runJudges()).isZero();
        assertThat(runs.findBySession(session().id())).last()
                .satisfies(r -> assertThat(r.status()).isEqualTo(RunStatus.CANCELLED));
    }

    @Test
    void aDecisionForAStopTheSessionHasLeftIsDropped() throws Exception {
        String first = stop("Should I proceed with A?");
        String second = stop("Should I proceed with B?");
        runJudges();

        List<Verdict> all = verdicts.findBySession(session().id());
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(v -> v.stopEventId().equals(first)).singleElement()
                .satisfies(v -> assertThat(v.status()).isEqualTo(Verdict.EXPIRED));
        assertThat(poll(first).state()).isEqualTo(Poll.State.NONE);
        assertThat(poll(second).kind()).isEqualTo("continue");
        assertThat(session().autoContinues()).isEqualTo(1);
    }

    @Test
    void theLoopGuardAsksTheUserAfterTooManyAnswersInARow() throws Exception {
        stop("Should I proceed?");
        dsl.update(SESSIONS).set(SESSIONS.AUTO_CONTINUES, 5).where(SESSIONS.ID.eq(session().id())).execute();
        int before = runs.findBySession(session().id()).size();

        String stop = stop("Should I proceed? (again)");
        assertThat(runs.findBySession(session().id())).as("no judge run").hasSize(before);
        assertThat(session().status()).isEqualTo(Session.STOPPED);
        assertThat(questions()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(AttentionItem.SESSION_LOOP);
            assertThat(i.urgency()).isEqualTo(AttentionItem.URGENT);
            assertThat(i.title()).contains("after 5 answers from orange");
        });
        assertThat(poll(stop).state()).isEqualTo(Poll.State.NONE);
    }

    @Test
    void aJudgeThatFailsFallsBackToTheRules() throws Exception {
        String stop = stop("Which one should I use? [[fake:error]]");
        runJudges();

        assertThat(session().status()).isEqualTo(Session.STOPPED);
        assertThat(session().waitingReason()).isEqualTo(Session.WAIT_QUESTION);
        assertThat(questions()).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo(AttentionItem.SESSION_QUESTION));
        assertThat(poll(stop).state()).isEqualTo(Poll.State.NONE);
    }

    @Test
    void aProjectThatAnswersNothingItselfIsNotJudged() throws Exception {
        event("user_prompt", "prompt", "start");
        space.setConvention(session().projectId(), null, "auto-answer", Json.mapper().readTree("\"none\""),
                SpaceService.Author.USER);

        String stop = stop("Should I proceed?");
        assertThat(session().status()).isEqualTo(Session.STOPPED);
        assertThat(runs.findBySession(session().id())).isEmpty();
        assertThat(questions()).singleElement()
                .satisfies(i -> assertThat(i.kind()).isEqualTo(AttentionItem.SESSION_QUESTION));
        assertThat(poll(stop).state()).isEqualTo(Poll.State.NONE);
    }

    @Test
    void aPausedSchedulerMeansRulesNotAWaitingHook() throws Exception {
        pause.pause("test", null);
        try {
            String stop = stop("Should I proceed?");
            assertThat(session().status()).isEqualTo(Session.STOPPED);
            assertThat(poll(stop).state()).isEqualTo(Poll.State.NONE);
            assertThat(questions()).hasSize(1);
        } finally {
            pause.resume();
        }

        // Paused after the stop was handed over: the sweeper takes it back.
        event("user_prompt", "prompt", "go on");
        String stop = stop("Should I proceed, then?");
        assertThat(session().status()).isEqualTo(Session.JUDGING);
        pause.pause("test", null);
        try {
            judgement.sweep();
            assertThat(session().status()).isEqualTo(Session.STOPPED);
            assertThat(poll(stop).state()).isEqualTo(Poll.State.NONE);
            assertThat(runJudges()).isZero();
        } finally {
            pause.resume();
        }
    }

    @Test
    void anAnswerNoHookCollectedBecomesAQuestionWithThatAnswerProposed() throws Exception {
        String stop = stop("Should I proceed with option 1?");
        runJudges();
        assertThat(session().autoContinues()).isEqualTo(1);
        judgement.sweep();
        assertThat(verdicts.findBySession(session().id()).getFirst().status()).as("inside the grace").isEqualTo(Verdict.PENDING);

        dsl.update(VERDICTS).set(VERDICTS.CREATED_AT, OffsetDateTime.now().minusMinutes(5))
                .where(VERDICTS.SESSION_ID.eq(session().id())).execute();
        judgement.sweep();

        assertThat(verdicts.findBySession(session().id()).getFirst().status()).isEqualTo(Verdict.EXPIRED);
        assertThat(poll(stop).state()).isEqualTo(Poll.State.NONE);
        assertThat(session().status()).isEqualTo(Session.STOPPED);
        assertThat(session().autoContinues()).as("an answer that never arrived does not count").isZero();
        assertThat(questions()).singleElement()
                .satisfies(i -> assertThat(i.proposedAnswer()).isEqualTo("Yes, proceed with option 1."));
    }

    @Test
    void aStopNobodyDecidedInTimeFallsBackToTheRules() throws Exception {
        String stop = stop("Should I proceed?");
        dsl.update(SESSIONS).set(SESSIONS.WAITING_SINCE, OffsetDateTime.now().minusMinutes(11))
                .where(SESSIONS.ID.eq(session().id())).execute();
        assertThat(poll(stop).state()).as("the hook's wait is over").isEqualTo(Poll.State.NONE);

        judgement.sweep();
        assertThat(session().status()).isEqualTo(Session.STOPPED);
        assertThat(questions()).hasSize(1);
        assertThat(runJudges()).as("its queued run was cancelled").isZero();
    }

    @Test
    void theJudgeMayProposeForTheSpace() throws Exception {
        stop("Should I go ahead? [[fake:judge-propose]]");
        runJudges();

        List<SpaceItem> items = space.current(session().projectId());
        assertThat(items).filteredOn(i -> SpaceItem.PROPOSED.equals(i.status())).singleElement().satisfies(i -> {
            assertThat(i.authorKind()).isEqualTo(SpaceItem.AGENT);
            assertThat(i.authorRef()).startsWith("run:");
            assertThat(i.body().data()).contains("Retries use exponential backoff.");
        });
        assertThat(session().autoContinues()).isEqualTo(1);
    }

    @Test
    void aStopOrangeNeverGotHasNoVerdict() {
        assertThat(judgement.poll("claude-code", sessionId, UUID.randomUUID().toString()).state()).isEqualTo(Poll.State.NONE);
    }
}
