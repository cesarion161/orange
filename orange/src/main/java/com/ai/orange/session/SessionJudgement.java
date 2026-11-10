package com.ai.orange.session;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.events.EventRepository;
import com.ai.orange.json.Json;
import com.ai.orange.project.Conventions;
import com.ai.orange.project.Project;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunOutcome;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunResult;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.SchedulerPause;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What becomes of a judge's decision (project-space spec §7, decisions 2–4): the verdict the stop
 * hook long-polls for, the attention item when the user must decide, and the record of what
 * orange decided on the user's behalf.
 *
 * <p>The judge proposes; this class holds the lines the judge may not cross. A {@code continue}
 * below the project's confidence threshold becomes an escalation with the judge's answer as the
 * proposal, and a verdict is only ever delivered to the stop it was made for.
 */
@Service
public class SessionJudgement {

    private static final Logger log = LoggerFactory.getLogger(SessionJudgement.class);
    /** How long a stored stop may stay unapplied before its hook is told to stop waiting. */
    static final Duration APPLY_GRACE = Duration.ofSeconds(30);

    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final VerdictRepository verdicts;
    private final SpaceService space;
    private final Conventions conventions;
    private final EventRepository inbox;
    private final RunRepository runs;
    private final SchedulerPause pause;
    private final JudgeProperties props;

    public SessionJudgement(SessionRepository sessions, SessionService sessionService, VerdictRepository verdicts,
                            SpaceService space, Conventions conventions, EventRepository inbox, RunRepository runs,
                            SchedulerPause pause, JudgeProperties props) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.verdicts = verdicts;
        this.space = space;
        this.conventions = conventions;
        this.inbox = inbox;
        this.runs = runs;
        this.pause = pause;
        this.props = props;
    }

    // ─────────────────────────── a judge run ended ───────────────────────────

    /** Applies a finished judge run. Called before the run row is finished, like every post-run step. */
    @Transactional
    public void after(Run run, RunResult result) {
        if (run.attendedSessionId() == null) return;
        Session s = sessions.lock(run.attendedSessionId()).orElse(null);
        if (s == null) return;
        String stopEventId = stopEventId(run);
        RunOutcome.Verdict v = result.reason() == null && result.outcome() instanceof RunOutcome.Verdict o ? o : null;

        boolean current = Session.JUDGING.equals(s.status()) && stopEventId.equals(s.stopEventId());
        if (!current) {
            // The user spoke, or the session stopped again, while the judge thought: the decision is void.
            if (v != null) {
                verdicts.insert(s.id(), run.id(), stopEventId, v.kind(), v.message(), v.rationale(), v.confidence(),
                        Verdict.EXPIRED);
            }
            log.info("judge run {}: session {} has moved on; its decision is dropped", run.id(), s.id());
            return;
        }
        if (v == null) {
            log.warn("judge run {} gave no decision ({}); session {} falls back to the rules", run.id(),
                    result.reason(), s.id());
            sessionService.fallBackToRules(s);
            return;
        }
        Project project = sessionService.projectOf(s);
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        switch (v.kind()) {
            case Verdict.CONTINUE -> {
                double threshold = conventions.resolve("auto-answer-confidence", s.projectId(), s.id())
                        .map(n -> n.asDouble(0.8)).orElse(0.8);
                if (v.confidence() == null || v.confidence() < threshold) {
                    // Not sure enough to speak for the user (spec decision 4): ask, with the answer as a proposal.
                    escalate(s, project, run, stopEventId, null, v.message(), AttentionItem.NORMAL,
                            "confidence " + v.confidence() + " is below " + threshold + ": " + v.rationale(), now);
                    return;
                }
                verdicts.insert(s.id(), run.id(), stopEventId, Verdict.CONTINUE, v.message(), v.rationale(),
                        v.confidence(), Verdict.PENDING);
                // Still "judging" until the hook takes the answer; then the session works again.
                sessions.save(s.withAutoContinues(s.autoContinues() + 1));
                log.info("judge run {}: orange answers session {} ({} in a row)", run.id(), s.id(), s.autoContinues() + 1);
            }
            case Verdict.ESCALATE -> escalate(s, project, run, stopEventId, v.question(), v.proposedAnswer(),
                    AttentionItem.URGENCIES.contains(v.urgency()) ? v.urgency() : AttentionItem.NORMAL, v.rationale(), now);
            // "It is finished" starts verification in Phase C; until then it waits like any finished session.
            case Verdict.VERIFY, Verdict.IDLE -> {
                verdicts.insert(s.id(), run.id(), stopEventId, v.kind(), null, v.rationale(), null, Verdict.PENDING);
                sessions.save(s.waiting(Session.STOPPED, Session.WAIT_FINISHED, now));
            }
            default -> sessionService.fallBackToRules(s);
        }
    }

    private void escalate(Session s, Project project, Run run, String stopEventId, String question,
                          String proposedAnswer, String urgency, String rationale, OffsetDateTime now) {
        verdicts.insert(s.id(), run.id(), stopEventId, Verdict.ESCALATE, null, rationale, null, Verdict.PENDING);
        Session waiting = s.waiting(Session.STOPPED, Session.WAIT_QUESTION, now);
        sessions.save(waiting);
        sessionService.raise(waiting, project, AttentionItem.SESSION_QUESTION, question, proposedAnswer, urgency);
    }

    /** What orange decided for the user goes into the project's space, so it can be audited (spec §7). */
    private void recordDecision(Session s, Verdict v) {
        Project project = sessionService.projectOf(s);
        if (project == null) return;
        String text = "orange answered " + SessionService.label(s, project) + ": \"" + SessionService.cut(v.message(), 600)
                + "\"" + (v.rationale() == null ? "" : " — because " + SessionService.cut(v.rationale(), 600));
        space.add(project.id(), SpaceItem.DECISION, text, SpaceService.Author.ORANGE, null, s.id(), null);
    }

    static String stopEventId(Run run) {
        try {
            return Json.mapper().readTree(run.input().data()).path("stop_event_id").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    // ─────────────────────────── the stop hook asks ───────────────────────────

    /** What the stop hook is told: keep waiting, nothing is coming, or the verdict. */
    public record Poll(State state, String kind, String message) {
        public enum State { PENDING, NONE, VERDICT }

        static final Poll PENDING = new Poll(State.PENDING, null, null);
        static final Poll NONE = new Poll(State.NONE, null, null);
    }

    /**
     * One look for the verdict of the stop a reporter posted as {@code stopEventId}. A
     * {@code continue} is only shown here: it counts as delivered when the hook acknowledges it
     * ({@link #acknowledge}), so an answer whose hook has gone is not lost. The other kinds have
     * nothing to say to the session and are settled at once.
     */
    @Transactional
    public Poll poll(String harness, String externalId, String stopEventId) {
        Optional<EventRepository.Receipt> receipt = inbox.receipt(SessionEventHandler.SOURCE + ":" + stopEventId);
        if (receipt.isEmpty()) return Poll.NONE;         // orange never got this stop
        if (!receipt.get().processed()) {
            // Stored, not applied yet. That takes moments; if it does not, the inbox is stuck and a
            // hook must not wait on it.
            boolean fresh = Duration.between(receipt.get().receivedAt(), OffsetDateTime.now()).compareTo(APPLY_GRACE) < 0;
            return fresh ? Poll.PENDING : Poll.NONE;
        }
        Session s = sessions.lock(harness, externalId).orElse(null);
        if (s == null || !stopEventId.equals(s.stopEventId()) || userHasSpoken(externalId)) return Poll.NONE;
        Optional<Verdict> verdict = verdicts.find(s.id(), stopEventId);
        if (verdict.isPresent()) {
            Verdict v = verdict.get();
            if (!Verdict.PENDING.equals(v.status())) return Poll.NONE;
            if (!Verdict.CONTINUE.equals(v.kind())) verdicts.settle(v.id(), Verdict.DELIVERED);
            return new Poll(Poll.State.VERDICT, v.kind(), v.message());
        }
        boolean waiting = Session.JUDGING.equals(s.status()) && s.waitingSince() != null
                && Duration.between(s.waitingSince(), OffsetDateTime.now()).compareTo(props.hookWait()) < 0;
        return waiting ? Poll.PENDING : Poll.NONE;
    }

    /**
     * The stop hook is about to hand a {@code continue} to its session. True makes it delivered:
     * the session works again and the answer goes on record as orange's decision. False means the
     * hook must stay silent: the user spoke first, the session moved on, or the verdict expired.
     */
    @Transactional
    public boolean acknowledge(String harness, String externalId, String stopEventId) {
        Session s = sessions.lock(harness, externalId).orElse(null);
        if (s == null || !stopEventId.equals(s.stopEventId()) || userHasSpoken(externalId)) return false;
        Verdict v = verdicts.find(s.id(), stopEventId).orElse(null);
        if (v == null || !Verdict.CONTINUE.equals(v.kind()) || !verdicts.settle(v.id(), Verdict.DELIVERED)) return false;
        sessions.save(s.waiting(Session.ACTIVE, null, null));
        recordDecision(s, v);
        return true;
    }

    /** A prompt of the user that is stored but not applied yet already counts: the user wins. */
    private boolean userHasSpoken(String externalId) {
        return inbox.hasUnprocessed(SessionEventHandler.SOURCE, NeutralEvent.USER_PROMPT, externalId);
    }

    // ─────────────────────────── time passes ───────────────────────────

    /**
     * Decisions nobody collected, and stops nobody decided. An answer no hook took within the
     * pickup grace goes to the inbox as a question with that answer proposed, so it is not lost.
     * A session still being judged falls back to the rules when nothing will decide it: its judge
     * run is gone (reaped, or its result could not be applied), has not started within the start
     * grace, the scheduler is paused, or the hook has given up. Returns how many sessions it moved.
     */
    @Transactional
    public int sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        int moved = 0;
        for (Verdict v : verdicts.findPendingBefore(now.minus(props.pickupGrace()))) {
            Session s = sessions.lock(v.sessionId()).orElse(null);
            if (!verdicts.settle(v.id(), Verdict.EXPIRED) || s == null) continue;
            if (Verdict.CONTINUE.equals(v.kind()) && Session.JUDGING.equals(s.status())
                    && v.stopEventId().equals(s.stopEventId())) {
                Session waiting = s.withAutoContinues(Math.max(0, s.autoContinues() - 1))
                        .waiting(Session.STOPPED, Session.WAIT_QUESTION, now.truncatedTo(ChronoUnit.MILLIS));
                sessions.save(waiting);
                sessionService.raise(waiting, sessionService.projectOf(waiting), AttentionItem.SESSION_QUESTION, null,
                        v.message(), AttentionItem.NORMAL);
                moved++;
            }
        }
        boolean paused = pause.current().isPresent();
        for (Session seen : sessions.findJudging()) {
            Session s = sessions.lock(seen.id()).orElse(null);
            if (s == null || !Session.JUDGING.equals(s.status()) || s.waitingSince() == null) continue;
            if (s.stopEventId() != null && verdicts.find(s.id(), s.stopEventId()).isPresent()) continue;
            Duration waited = Duration.between(s.waitingSince(), now);
            List<Run> live = runs.findBySession(s.id()).stream()
                    .filter(r -> r.status() == RunStatus.QUEUED || r.status() == RunStatus.RUNNING)
                    .filter(r -> s.stopEventId() != null && s.stopEventId().equals(stopEventId(r)))
                    .toList();
            boolean neverStarted = live.stream().allMatch(r -> r.status() == RunStatus.QUEUED)
                    && waited.compareTo(props.startGrace()) >= 0;
            boolean overdue = waited.compareTo(props.hookWait()) >= 0;
            if (!live.isEmpty() && !neverStarted && !overdue && !paused) continue;
            runs.cancelQueuedForSession(s.id());
            sessionService.fallBackToRules(s);
            moved++;
        }
        return moved;
    }
}
