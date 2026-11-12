package com.ai.orange.session;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.json.Json;
import com.ai.orange.project.Project;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.WorkConventions;
import com.ai.orange.routing.RiskPaths;
import com.ai.orange.run.EvidenceGap;
import com.ai.orange.run.ReviewGap;
import com.ai.orange.run.ReviewPolicy;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunOutcome;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closing a session's work (project-space spec §7, §8, decisions 10–12): when the judge says a
 * session is finished, orange checks that itself, and only evidence closes the work.
 *
 * <p>The steps are runs of the session, one at a time, each queued by the one before:
 * {@code verify} (the work is committed and the project's evidence commands pass),
 * {@code diff_review} (the diff does what the intent asked; only when the {@code review} convention
 * asks for one, see {@link ReviewPolicy}), {@code integrate} (onto the base,
 * as the {@code integration} convention says). The session is {@code verifying} throughout and
 * its stop hook waits. A gap at any step goes back into the session as a {@code continue}; a
 * pass at the last one closes the work and lets the session stop.
 *
 * <p>Everything here belongs to one stop. A session the user has spoken to, or that stopped
 * again, has left that stop: results for it are dropped.
 */
@Service
public class SessionClosing {

    private static final Logger log = LoggerFactory.getLogger(SessionClosing.class);

    /** The session waits on these runs, so they go ahead of task work, like its judge. */
    static final int PRIORITY = SessionService.JUDGE_PRIORITY;
    /** How often one stop may be verified, and integrated: a rebase or a moved base asks for another round. */
    static final int MAX_ROUNDS = 3;
    /** How much of a failing command's output (its end) the session is shown. */
    static final int GAP_TAIL = 1_500;

    public static final String SESSION_INTEGRATION = "session_integration";
    public static final String EVIDENCE_MISSING = "evidence_missing";

    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final VerdictRepository verdicts;
    private final RunRepository runs;
    private final EvidenceRepository evidence;
    private final SessionWorkRepository work;
    private final WorkConventions conventions;
    private final SpaceService space;
    private final AttentionService attention;
    private final AttentionRepository attentionItems;
    private final RiskPaths riskPaths;

    public SessionClosing(SessionRepository sessions, SessionService sessionService, VerdictRepository verdicts,
                          RunRepository runs, EvidenceRepository evidence, SessionWorkRepository work,
                          WorkConventions conventions, SpaceService space, AttentionService attention,
                          AttentionRepository attentionItems, RiskPaths riskPaths) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.verdicts = verdicts;
        this.runs = runs;
        this.evidence = evidence;
        this.work = work;
        this.conventions = conventions;
        this.space = space;
        this.attention = attention;
        this.attentionItems = attentionItems;
        this.riskPaths = riskPaths;
    }

    /** The judge said this stop ends the work: check it. Called with the session's row locked. */
    void start(Session s, String stopEventId) {
        sessions.save(s.waiting(Session.VERIFYING, null, s.waitingSince()));
        queue(s, RunKind.VERIFY, "verify", Json.mapper().createObjectNode().put("stop_event_id", stopEventId));
        log.info("session {} says it is finished: verifying", s.id());
    }

    /** Applies a finished verify, diff review or integrate run of a session. A post-run step. */
    @Transactional
    public void after(Run run, RunResult result) {
        if (run.attendedSessionId() == null) return;
        Session s = sessions.lock(run.attendedSessionId()).orElse(null);
        if (s == null) return;
        JsonNode in = input(run);
        String stop = in.path("stop_event_id").asText("");
        if (!Session.VERIFYING.equals(s.status()) || !stop.equals(s.stopEventId())) {
            // The user spoke while the push was in flight: the work is on the base all the same, and
            // that must be on record even though nothing is said to the session now.
            if (run.kind() == RunKind.INTEGRATE && result.reason() == null
                    && result.outcome() instanceof RunOutcome.Integration i && "integrated".equals(i.status())) {
                work.close(s.id(), SessionWork.INTEGRATED, i.headSha(), in.path("branch").asText(null),
                        in.path("base_branch").asText(null), "Verified and integrated onto "
                                + in.path("base_branch").asText("the base") + " at " + i.headSha()
                                + (Boolean.TRUE.equals(i.pushed()) ? "; pushed to origin." : ".")
                                + " The session had been spoken to again by the time this landed.");
                log.info("integrate run {}: session {} has moved on, but its work at {} is integrated", run.id(),
                        s.id(), i.headSha());
                return;
            }
            log.info("{} run {}: session {} has moved on; its result is dropped", run.kind().dbValue(), run.id(), s.id());
            return;
        }
        switch (run.kind()) {
            case VERIFY -> afterVerify(s, run, result, stop);
            case DIFF_REVIEW -> afterReview(s, run, result, in);
            case INTEGRATE -> afterIntegrate(s, run, result, in);
            default -> { }
        }
    }

    // ─────────────────────────── the steps ───────────────────────────

    private void afterVerify(Session s, Run run, RunResult result, String stop) {
        if (result.reason() != null || !(result.outcome() instanceof RunOutcome.SessionCheck check)) {
            letStop(s, run, stop, "the check did not run: " + result.reason());
            return;
        }
        switch (check.status()) {
            case RunOutcome.SessionCheck.PASSED -> {
                ObjectNode next = Json.mapper().createObjectNode().put("stop_event_id", stop)
                        .put("head_sha", check.headSha()).put("branch", check.branch());
                if (check.baseSha() != null) next.put("base_sha", check.baseSha());
                if (check.baseBranch() != null) next.put("base_branch", check.baseBranch());
                boolean reviewable = conventions.done(s.projectId(), s.id()).contains(WorkConventions.EVIDENCE)
                        && check.baseSha() != null && !check.baseSha().equals(check.headSha())
                        && !evidence.sessionHasPassingDiffReview(s.id(), check.headSha())
                        && reviewRequired(s, check);
                boolean nothingNew = check.baseSha() != null && check.baseSha().equals(check.headSha());
                if (nothingNew) {
                    // The base already has this commit: there is nothing to review, rebase or push, and
                    // an integration would only move the user's branch for no reason.
                    boolean onBase = check.branch() != null && check.branch().equals(check.baseBranch());
                    close(s, run, next, onBase ? SessionWork.INTEGRATED : SessionWork.VERIFIED, onBase
                            ? "Verified; the work is on " + check.baseBranch() + " and there is nothing to push."
                            : "Verified; the branch has no commits that " + check.baseBranch() + " lacks.");
                } else if (reviewable) {
                    queue(s, RunKind.DIFF_REVIEW, "reviewer", next);
                } else {
                    integrateOrClose(s, run, next);
                }
            }
            case RunOutcome.SessionCheck.GAPS -> sendBack(s, run, stop, gapsMessage(check));
            case RunOutcome.SessionCheck.NO_EVIDENCE -> {
                askForEvidence(s, check);
                letStop(s, run, stop, "the project has no evidence commands");
            }
            default -> letStop(s, run, stop, check.note());
        }
    }

    private void afterReview(Session s, Run run, RunResult result, JsonNode in) {
        String stop = in.path("stop_event_id").asText("");
        if (result.reason() != null || !(result.outcome() instanceof RunOutcome.Review review)) {
            letStop(s, run, stop, "the diff review gave no verdict: " + result.reason());
            return;
        }
        boolean pass = "pass".equals(review.verdict());
        String gaps = review.gaps().stream().map(SessionClosing::gapLine).collect(Collectors.joining("\n"));
        evidence.recordForSession(s.id(), run.id(), in.path("head_sha").asText(""), EvidenceRepository.DIFF_REVIEW, null,
                null, gaps.isEmpty() ? review.summary() : review.summary() + "\n" + gaps, pass);
        if (pass) {
            integrateOrClose(s, run, (ObjectNode) in.deepCopy());
            return;
        }
        sendBack(s, run, stop, "orange reviewed the diff of this work against what was asked, and it is not done yet.\n\n"
                + review.summary().strip() + (gaps.isEmpty() ? "" : "\n\n" + gaps)
                + "\n\nAddress these, run the checks and commit.");
    }

    /** Evidence passed: integrate as the conventions say, or close with the work where it is. */
    private void integrateOrClose(Session s, Run run, ObjectNode in) {
        String stop = in.path("stop_event_id").asText("");
        String integration = conventions.integration(s.projectId(), s.id());
        boolean wanted = conventions.done(s.projectId(), s.id()).contains(WorkConventions.INTEGRATED);
        if (!wanted || WorkConventions.BRANCH.equals(integration)) {
            close(s, run, in, SessionWork.VERIFIED, wanted ? "Verified; left on its branch (integration: branch)."
                    : "Verified; this project's done convention asks for no integration.");
            return;
        }
        if (!WorkConventions.MERGE.equals(integration)) {
            String why = WorkConventions.PR.equals(integration)
                    ? "The project integrates through pull requests, which orange does not open yet."
                    : "The project's integration convention is \"" + integration + "\": integrating is yours.";
            close(s, run, in, SessionWork.VERIFIED, "Verified; not integrated. " + why);
            needsUser(s, stop, "is verified and waits for you to integrate it", why);
            return;
        }
        if (!in.hasNonNull("base_branch")) {
            String why = "orange does not know which branch this repo integrates onto. Set the project's "
                    + "base-branch convention.";
            close(s, run, in, SessionWork.VERIFIED, "Verified; not integrated. " + why);
            needsUser(s, stop, "is verified but orange cannot integrate it", why);
            return;
        }
        if (rounds(s, RunKind.INTEGRATE, stop) >= MAX_ROUNDS) {
            needsUser(s, stop, "is verified but orange could not integrate it",
                    "The base kept moving under " + MAX_ROUNDS + " attempts.");
            letStop(s, run, stop, "integration did not settle");
            return;
        }
        queue(s, RunKind.INTEGRATE, "integrate", in);
    }

    private void afterIntegrate(Session s, Run run, RunResult result, JsonNode in) {
        String stop = in.path("stop_event_id").asText("");
        String branch = in.path("branch").asText("its branch");
        String base = in.path("base_branch").asText("the base");
        if (result.reason() != null || !(result.outcome() instanceof RunOutcome.Integration outcome)) {
            String error = result.outcome() instanceof RunOutcome.Integration i && i.error() != null ? i.error()
                    : String.valueOf(result.reason());
            needsUser(s, stop, "is verified but orange could not integrate it", error);
            letStop(s, run, stop, "integration failed: " + error);
            return;
        }
        switch (outcome.status()) {
            case "integrated" -> {
                StringBuilder note = new StringBuilder("Verified and integrated onto ").append(base);
                note.append(Boolean.TRUE.equals(outcome.pushed()) ? "; pushed to origin." : "; nothing was pushed.");
                if ("dirty".equals(outcome.localBase())) {
                    note.append(" Your checkout of ").append(base)
                            .append(" has uncommitted changes, so orange left it where it was.");
                } else if ("skipped".equals(outcome.localBase())) {
                    note.append(" The local ").append(base).append(" branch was not moved.");
                }
                close(s, run, in, SessionWork.INTEGRATED, note.toString());
            }
            case "rebased" -> {
                // A clean rebase leaves the session's own patch as it was: its review holds, the commands re-run.
                String from = in.path("head_sha").asText("");
                if (evidence.sessionHasPassingDiffReview(s.id(), from)) {
                    evidence.recordForSession(s.id(), run.id(), outcome.headSha(), EvidenceRepository.DIFF_REVIEW, null,
                            null, "carried over from " + from + " after a clean rebase", true);
                }
                if (rounds(s, RunKind.VERIFY, stop) >= MAX_ROUNDS) {
                    needsUser(s, stop, "is verified but orange could not integrate it",
                            "The base kept moving: the work was rebased and checked " + MAX_ROUNDS + " times.");
                    letStop(s, run, stop, "verification did not settle");
                } else {
                    queue(s, RunKind.VERIFY, "verify", Json.mapper().createObjectNode().put("stop_event_id", stop));
                }
            }
            case "conflict" -> sendBack(s, run, stop, "orange checked this work and it passes, but rebasing `" + branch
                    + "` onto `" + base + "` conflicts in:\n" + outcome.files().stream().map(f -> "- " + f)
                    .collect(Collectors.joining("\n")) + "\n\nRebase onto `" + base
                    + "`, resolve the conflicts, run the checks and commit.");
            case "behind" -> sendBack(s, run, stop, "orange checked this work and it passes, but `origin/" + base
                    + "` has commits this checkout does not have, so it cannot be pushed. Bring them in "
                    + "(`git pull --rebase origin " + base + "`), run the checks again and commit.");
            case "base_moved" -> integrateOrClose(s, run, (ObjectNode) in.deepCopy());
            default -> letStop(s, run, stop, "the checkout moved while orange was integrating it");
        }
    }

    // ─────────────────────────── the ends ───────────────────────────

    /** A gap goes back into the session: the stop hook blocks the stop with it. */
    /** Whether the {@code review} convention asks for a diff review of this work (remote-first spec §7.3). */
    private boolean reviewRequired(Session s, RunOutcome.SessionCheck check) {
        ReviewPolicy.Mode mode = conventions.review(s.projectId(), s.id());
        if (mode != ReviewPolicy.Mode.RISKY) return ReviewPolicy.required(mode, false, false);
        String where = s.worktree() != null ? s.worktree() : s.repoRoot();
        boolean risky = where == null || riskPaths.touches(Path.of(where), check.baseSha(), check.headSha());
        return ReviewPolicy.required(mode, false, risky);
    }

    private void sendBack(Session s, Run run, String stop, String message) {
        // No confidence: this is what orange found, not an answer it gives on the user's behalf.
        verdicts.insert(s.id(), run.id(), stop, Verdict.CONTINUE, message, "verification", null, Verdict.PENDING);
        sessions.save(s.withAutoContinues(s.autoContinues() + 1));
        log.info("session {}: its work is not done; sending it back ({} in a row)", s.id(), s.autoContinues() + 1);
    }

    /** Nothing closes the work, and nothing is asked of the session: it stops like any finished session. */
    private void letStop(Session s, Run run, String stop, String why) {
        verdicts.insert(s.id(), run.id(), stop, Verdict.VERIFY, null, why, null, Verdict.PENDING);
        sessions.save(s.waiting(Session.STOPPED, Session.WAIT_FINISHED, OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS)));
        log.info("session {}: not closed ({}); it stops", s.id(), why);
    }

    private void close(Session s, Run run, JsonNode in, String result, String note) {
        String stop = in.path("stop_event_id").asText("");
        String head = in.path("head_sha").asText(null);
        verdicts.insert(s.id(), run.id(), stop, Verdict.VERIFY, null, note, null, Verdict.PENDING);
        work.openIfNone(s.id(), s.intent());
        work.close(s.id(), result, head, in.path("branch").asText(null), in.path("base_branch").asText(null), note);
        sessions.save(s.closed(result));
        attentionItems.resolveForSession(s.id(), "closed", null);
        log.info("session {}: work closed as {} at {} — {}", s.id(), result, head, note);
    }

    /** The work passed, and what is left is the user's. */
    private void needsUser(Session s, String stop, String what, String why) {
        Project project = sessionService.projectOf(s);
        StringBuilder body = new StringBuilder();
        if (s.intent() != null) body.append("Doing: ").append(SessionService.cut(s.intent(), 300)).append('\n');
        body.append(why).append('\n');
        body.append("Where: ").append(s.cwd() == null ? "unknown" : s.cwd());
        if (s.branch() != null) body.append(" (").append(s.branch()).append(')');
        attention.raise(SESSION_INTEGRATION, "session:" + s.id() + ":integration:" + stop, AttentionItem.NORMAL,
                s.projectId(), s.id(), SessionService.label(s, project) + " " + what, body.toString());
    }

    /**
     * A project without evidence commands cannot have its work verified. orange says so once per
     * project, and proposes what the repo suggests; the proposal is the user's to accept, because
     * these commands run on their machine (spec decision 13).
     */
    private void askForEvidence(Session s, RunOutcome.SessionCheck check) {
        Project project = sessionService.projectOf(s);
        if (project == null) return;
        // Said once per project, proposal included: a user who dropped it does not get it again.
        if (attentionItems.findByKey("project:" + project.id() + ":" + EVIDENCE_MISSING).isPresent()) return;
        StringBuilder body = new StringBuilder("orange cannot tell when work in ").append(project.name())
                .append(" is done: the project has no evidence commands, so a finished session just stops.\n");
        if (check.detected().isEmpty()) {
            body.append("Set them with orange_set: key \"evidence\", value a list of commands such as "
                    + "[\"./gradlew test\"].");
        } else {
            ArrayNode value = Json.mapper().createArrayNode();
            check.detected().forEach(value::add);
            SpaceItem proposal = space.proposeConvention(project.id(), WorkConventions.EVIDENCE, value);
            body.append("From the repo, orange would run: ").append(String.join(", ", check.detected()))
                    .append(".\nAccept that with orange_accept ").append(proposal.id())
                    .append(", or set your own with orange_set (key \"evidence\").");
        }
        body.append("\nIf this project needs no checks, set its \"done\" convention to [\"committed\"] or [].");
        attention.raise(EVIDENCE_MISSING, "project:" + project.id() + ":" + EVIDENCE_MISSING, AttentionItem.NORMAL,
                project.id(), null, project.name() + " has no evidence commands", body.toString());
    }

    // ─────────────────────────── helpers ───────────────────────────

    private void queue(Session s, RunKind kind, String role, ObjectNode input) {
        runs.queueForSession(s.id(), kind, role, PRIORITY, JSONB.valueOf(input.toString()));
    }

    /** How many runs of this kind the stop has had. */
    private int rounds(Session s, RunKind kind, String stop) {
        return (int) runs.findBySession(s.id()).stream()
                .filter(r -> r.kind() == kind && stop.equals(input(r).path("stop_event_id").asText("")))
                .count();
    }

    static String gapsMessage(RunOutcome.SessionCheck check) {
        StringBuilder b = new StringBuilder("orange checked this work and it is not done yet.\n");
        for (EvidenceGap g : check.gaps()) {
            if (g.exitCode() == null) {
                b.append("\nThere are uncommitted or untracked files:\n```\n").append(g.outputTail().strip())
                        .append("\n```\nCommit what belongs to the work, and remove or ignore the rest.\n");
            } else {
                b.append("\n`").append(g.command()).append("` failed")
                        .append(g.exitCode() == -1 ? " (it timed out)" : " with exit code " + g.exitCode())
                        .append(" at commit ").append(check.headSha(), 0, Math.min(10, check.headSha().length()))
                        .append(g.outputTail() == null || g.outputTail().isBlank() ? ". It printed nothing.\n"
                                : ". The end of its output:\n```\n" + SessionService.tail(g.outputTail(), GAP_TAIL)
                                        + "\n```\n")
                        .append("Fix it and commit.\n");
            }
        }
        return b.toString().strip();
    }

    private static String gapLine(ReviewGap g) {
        String where = g.path() == null ? "" : g.path() + (g.line() == null ? "" : ":" + g.line()) + ": ";
        return "- " + where + g.body();
    }

    private static JsonNode input(Run run) {
        if (run.input() == null) return MissingNode.getInstance();
        try {
            return Json.mapper().readTree(run.input().data());
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }
}
