package com.ai.orange.session;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionProperties;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectRepository;
import com.ai.orange.project.ProjectService;
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

    private final SessionRepository sessions;
    private final ProjectService projectService;
    private final ProjectRepository projects;
    private final AttentionService attention;
    private final AttentionRepository attentionItems;
    private final AttentionProperties props;

    public SessionService(SessionRepository sessions, ProjectService projectService, ProjectRepository projects,
                          AttentionService attention, AttentionRepository attentionItems, AttentionProperties props) {
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
                e.payloadText("reason"), asks, muted);

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
        }
        String prompt = e.payloadText("prompt");
        Session next = new Session(s.id(),
                project != null ? project.id() : s.projectId(),
                s.harness(), s.externalId(), e.node(),
                coalesce(e.cwd(), s.cwd()),
                coalesce(e.repoRoot(), s.repoRoot()),
                // A session that left its branch (a detached head) reports none; only git events say so.
                e.repoRoot() != null ? e.branch() : s.branch(),
                e.repoRoot() != null ? e.worktree() : s.worktree(),
                s.intent() == null && prompt != null ? cut(prompt, MAX_INTENT) : s.intent(),
                d.status() != null ? d.status() : s.status(),
                waitingReason, waitingSince,
                NeutralEvent.STOPPED.equals(e.type()) ? tail(lastMessage, MAX_MESSAGE) : s.lastMessage(),
                d.userPrompt() ? 0 : s.autoContinues(),
                now,
                d.userPrompt() ? now : s.lastUserPromptAt(),
                NeutralEvent.SESSION_ENDED.equals(e.type()) ? e.payloadText("reason") : s.closedReason(),
                s.createdAt());
        sessions.save(next);
        sessions.addEvent(s.id(), e.type(), JSONB.valueOf(e.payload().toString()), e.at());

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

    public Optional<Session> find(String harness, String externalId) {
        return sessions.find(harness, externalId);
    }

    private void raise(Session s, Project project, String kind, String harnessMessage) {
        String where = label(s, project);
        String title;
        StringBuilder body = new StringBuilder();
        if (s.intent() != null) body.append("Doing: ").append(cut(s.intent(), 300)).append('\n');
        switch (kind) {
            case AttentionItem.SESSION_QUESTION -> {
                title = where + " asks: " + cut(QuestionDetector.questionLine(s.lastMessage()), 120);
                body.append("Its last message:\n").append(tail(s.lastMessage(), BODY_TAIL)).append('\n');
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
        String urgency = AttentionItem.SESSION_IDLE.equals(kind) ? props.idleStopUrgency() : AttentionItem.NORMAL;
        attention.raise(kind, key(s, kind), urgency, s.projectId(), s.id(), title, body.toString());
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
