package com.ai.orange.session;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionProperties;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.json.Json;
import com.ai.orange.project.Conventions;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectRepository;
import com.ai.orange.project.ProjectService;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.SchedulerPause;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies session events to orange's picture of the user's sessions, and files what wants the
 * user (project-space spec §6, Phase A rules). {@link SessionReactor} decides; this class does.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /** Excerpts kept on the session row; the reporter cuts its own, this is the coordinator's bound. */
    static final int MAX_INTENT = 2_000;
    static final int MAX_MESSAGE = 8_000;
    /** How much of the last message an attention item quotes (its end). */
    static final int BODY_TAIL = 1_500;
    /** Judge runs go ahead of task work: a session and its user are waiting. */
    static final int JUDGE_PRIORITY = 10;

    private final SessionRepository sessions;
    private final ProjectService projectService;
    private final ProjectRepository projects;
    private final AttentionService attention;
    private final AttentionRepository attentionItems;
    private final AttentionProperties props;
    private final JudgeProperties judge;
    private final Conventions conventions;
    private final RunRepository runs;
    private final VerdictRepository verdicts;
    private final SchedulerPause pause;
    private final SessionWorkRepository work;

    public SessionService(SessionRepository sessions, ProjectService projectService, ProjectRepository projects,
                          AttentionService attention, AttentionRepository attentionItems, AttentionProperties props,
                          JudgeProperties judge, Conventions conventions, RunRepository runs,
                          VerdictRepository verdicts, SchedulerPause pause, SessionWorkRepository work) {
        this.work = work;
        this.judge = judge;
        this.conventions = conventions;
        this.runs = runs;
        this.verdicts = verdicts;
        this.pause = pause;
        this.sessions = sessions;
        this.projectService = projectService;
        this.projects = projects;
        this.attention = attention;
        this.attentionItems = attentionItems;
        this.props = props;
    }

    /** Applies one event, in the caller's transaction or its own. Returns the session after it. */
    @Transactional
    public Session apply(NeutralEvent e) {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        Session s = sessions.lock(e.harness(), e.sessionId())
                .orElseGet(() -> sessions.insert(e.harness(), e.sessionId(), e.node()));

        Project project = e.repoRoot() == null ? null
                : projectService.resolve(e.node(), e.repoRoot(), e.remoteUrl(), e.defaultBranch());
        if (project == null && s.projectId() != null) project = projects.findById(s.projectId()).orElse(null);
        boolean muted = project != null && project.archived();

        String lastMessage = e.payloadText("last_message");
        boolean asks = NeutralEvent.STOPPED.equals(e.type()) && QuestionDetector.asks(lastMessage);
        SessionReactor.Decision d = SessionReactor.onEvent(s.status(), s.waitingReason(), e.type(),
                e.payloadText("reason"), asks, muted, judging(e, s, project, muted));

        String waitingReason = s.waitingReason();
        OffsetDateTime waitingSince = s.waitingSince();
        switch (d.waiting()) {
            case KEEP -> { }
            case CLEAR -> {
                waitingReason = null;
                waitingSince = null;
            }
            case QUESTION, PERMISSION, FINISHED -> {
                waitingReason = d.waiting().name().toLowerCase(java.util.Locale.ROOT);
                waitingSince = now;
            }
            // Not waiting for the user yet: the verdict decides. The time bounds the hook's wait.
            case JUDGING -> {
                waitingReason = null;
                waitingSince = now;
            }
        }
        String prompt = e.payloadText("prompt");
        // The unit of work is an intent (spec decision 10): a prompt opens work when none is open, and
        // after a close it states a new intent.
        boolean newIntent = false;
        if (d.userPrompt() && prompt != null) {
            // Earlier work means this prompt starts something new, whatever the session did in between
            // (it may have been quit and resumed). A session from before work was tracked keeps its intent.
            boolean hadWork = !work.findBySession(s.id()).isEmpty();
            String first = hadWork || s.intent() == null ? cut(prompt, MAX_INTENT) : s.intent();
            newIntent = work.openIfNone(s.id(), first) && hadWork;
        }
        Session next = new Session(s.id(),
                project != null ? project.id() : s.projectId(),
                s.harness(), s.externalId(), e.node(),
                coalesce(e.cwd(), s.cwd()),
                coalesce(e.repoRoot(), s.repoRoot()),
                // A session that left its branch (a detached head) reports none; only git events say so.
                e.repoRoot() != null ? e.branch() : s.branch(),
                e.repoRoot() != null ? e.worktree() : s.worktree(),
                (s.intent() == null || newIntent) && prompt != null ? cut(prompt, MAX_INTENT) : s.intent(),
                d.status() != null ? d.status() : s.status(),
                waitingReason, waitingSince,
                NeutralEvent.STOPPED.equals(e.type()) ? tail(lastMessage, MAX_MESSAGE) : s.lastMessage(),
                d.userPrompt() ? 0 : s.autoContinues(),
                now,
                d.userPrompt() ? now : s.lastUserPromptAt(),
                NeutralEvent.SESSION_ENDED.equals(e.type()) ? e.payloadText("reason") : s.closedReason(),
                s.createdAt(),
                // The stop a verdict may be delivered to; anything else the session does ends that stop.
                NeutralEvent.STOPPED.equals(e.type()) ? e.eventId() : d.status() == null ? s.stopEventId() : null);
        sessions.save(next);
        sessions.addEvent(s.id(), e.type(), JSONB.valueOf(e.payload().toString()), e.at());

        if (d.userPrompt()) {
            // The user wins (spec decision 2): whatever orange was about to say to this session is void.
            verdicts.cancelPending(s.id());
            runs.cancelQueuedForSession(s.id());
        }
        if (Session.JUDGING.equals(d.status())) {
            ObjectNode input = Json.mapper().createObjectNode().put("stop_event_id", e.eventId())
                    .put("stop_hook_active", e.payload().path("stop_hook_active").asBoolean(false));
            runs.queueForSession(s.id(), RunKind.JUDGE, "judge", JUDGE_PRIORITY, JSONB.valueOf(input.toString()));
        }
        if (d.resolveOpen()) attentionItems.resolveForSession(s.id(), d.resolvedBy(), null);
        if (d.raise() != null) raise(next, project, d.raise(), e.payloadText("message"));
        return next;
    }

    /**
     * Looks at every waiting session: a question or a prompt nobody answered becomes urgent, and a
     * session that finished and was left alone is filed. Returns how many items it raised or filed.
     */
    @Transactional
    public int sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        int changed = 0;
        for (Session seen : sessions.findWaiting()) {
            // An event may have ended this wait since the list was read: look again, under the row's lock.
            Session s = sessions.lock(seen.id()).orElse(null);
            if (s == null || s.waitingSince() == null) continue;
            // A session nobody has heard from in a long time most likely died without saying so.
            if (Duration.between(s.lastEventAt(), now).compareTo(props.staleAfter()) > 0) continue;
            Project project = s.projectId() == null ? null : projects.findById(s.projectId()).orElse(null);
            SessionReactor.Sweep sweep = SessionReactor.onSweep(s.waitingReason(),
                    Duration.between(s.waitingSince(), now), props.waitingEscalateAfter(), props.idleAfter(),
                    project != null && project.archived());
            if (sweep.escalate()) {
                int n = attentionItems.escalateForSession(s.id(),
                        List.of(AttentionItem.SESSION_QUESTION, AttentionItem.SESSION_WAITING), AttentionItem.URGENT);
                if (n > 0) log.info("session {} has waited since {}: its item is urgent now", s.id(), s.waitingSince());
                changed += n;
            }
            if (sweep.raiseIdle()) {
                String key = key(s, AttentionItem.SESSION_IDLE);
                if (attentionItems.findByKey(key).isEmpty()) {
                    raise(s, project, AttentionItem.SESSION_IDLE, null);
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Whether this stop goes to the judge. Not when the judge is off or cannot run now (a paused
     * scheduler would leave the stop hook waiting for nothing), not for a muted project, not when
     * the project's {@code auto-answer} convention is {@code none}; and after too many answers in
     * a row the loop guard asks the user instead.
     */
    private SessionReactor.Judging judging(NeutralEvent e, Session s, Project project, boolean muted) {
        if (!NeutralEvent.STOPPED.equals(e.type()) || muted || !judge.enabled() || pause.current().isPresent()) {
            return SessionReactor.Judging.OFF;
        }
        String autoAnswer = conventions.resolve("auto-answer", project == null ? null : project.id(), s.id())
                .map(v -> v.asText("")).orElse("routine");
        if ("none".equals(autoAnswer)) return SessionReactor.Judging.OFF;
        return s.autoContinues() >= judge.maxAutoContinues() ? SessionReactor.Judging.LOOP_GUARD
                : SessionReactor.Judging.JUDGE;
    }

    /**
     * A stop the judge could not decide (its run failed, or never ran) falls back to the rules of
     * Phase A, so the user still hears about a session that asks something.
     */
    void fallBackToRules(Session s) {
        boolean asks = QuestionDetector.asks(s.lastMessage());
        Session next = s.waiting(Session.STOPPED, asks ? Session.WAIT_QUESTION : Session.WAIT_FINISHED,
                OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS));
        sessions.save(next);
        if (asks) raise(next, projectOf(next), AttentionItem.SESSION_QUESTION, null, null, AttentionItem.NORMAL);
    }

    Project projectOf(Session s) {
        return s.projectId() == null ? null : projects.findById(s.projectId()).orElse(null);
    }

    public Optional<Session> find(String harness, String externalId) {
        return sessions.find(harness, externalId);
    }

    private void raise(Session s, Project project, String kind, String harnessMessage) {
        raise(s, project, kind, harnessMessage, null, null);
    }

    /**
     * Files an item about a session. {@code text} is the question (for a question the judge wrote)
     * and {@code proposedAnswer} the answer orange would give; {@code urgency} null = by kind.
     */
    void raise(Session s, Project project, String kind, String text, String proposedAnswer, String urgency) {
        String harnessMessage = text;
        String where = label(s, project);
        String title;
        StringBuilder body = new StringBuilder();
        if (s.intent() != null) body.append("Doing: ").append(cut(s.intent(), 300)).append('\n');
        switch (kind) {
            case AttentionItem.SESSION_QUESTION -> {
                title = where + " asks: " + cut(text != null ? text : QuestionDetector.questionLine(s.lastMessage()), 120);
                if (text != null) body.append("Needs: ").append(text).append('\n');
                if (s.lastMessage() != null) {
                    body.append("Its last message:\n").append(tail(s.lastMessage(), BODY_TAIL)).append('\n');
                }
            }
            case AttentionItem.SESSION_LOOP -> {
                title = where + " stopped again after " + s.autoContinues() + " answers from orange";
                body.append("orange answered this session ").append(s.autoContinues())
                        .append(" times in a row and it stopped again. It will not be answered again until you "
                                + "have spoken to it.\n");
                if (s.lastMessage() != null) {
                    body.append("Its last message:\n").append(tail(s.lastMessage(), BODY_TAIL)).append('\n');
                }
            }
            case AttentionItem.SESSION_WAITING -> {
                title = where + " is waiting for you" + (harnessMessage == null ? "" : ": " + cut(harnessMessage, 120));
                if (harnessMessage != null) body.append("It says: ").append(harnessMessage).append('\n');
            }
            default -> {
                title = where + " finished and is idle";
                if (s.lastMessage() != null) {
                    body.append("Its last message:\n").append(tail(s.lastMessage(), BODY_TAIL)).append('\n');
                }
            }
        }
        body.append("Where: ").append(s.cwd() == null ? "unknown" : s.cwd());
        if (s.branch() != null) body.append(" (").append(s.branch()).append(')');
        body.append(" · ").append(s.harness()).append(" session ").append(s.externalId());
        String level = urgency != null ? urgency
                : AttentionItem.SESSION_IDLE.equals(kind) ? props.idleStopUrgency()
                : AttentionItem.SESSION_LOOP.equals(kind) ? AttentionItem.URGENT
                : AttentionItem.NORMAL;
        attention.raise(kind, key(s, kind), level, s.projectId(), s.id(), title, body.toString(), proposedAnswer);
    }

    /** One item per kind per wait: the key names the wait by when it began. */
    private static String key(Session s, String kind) {
        return "session:" + s.id() + ":" + kind + ":" + s.waitingSince().toInstant().toEpochMilli();
    }

    static String label(Session s, Project project) {
        String name = project != null ? project.name()
                : s.cwd() != null && Path.of(s.cwd()).getFileName() != null ? Path.of(s.cwd()).getFileName().toString()
                : "a session";
        return s.branch() == null ? name : name + " · " + s.branch();
    }

    private static String coalesce(String a, String b) {
        return a != null ? a : b;
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String one = s.strip();
        return one.length() <= max ? one : one.substring(0, max - 1) + "…";
    }

    static String tail(String s, int max) {
        if (s == null) return null;
        String one = s.strip();
        return one.length() <= max ? one : "…" + one.substring(one.length() - max + 1);
    }
}
