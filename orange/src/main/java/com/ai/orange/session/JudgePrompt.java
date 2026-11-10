package com.ai.orange.session;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.json.Json;
import com.ai.orange.project.Conventions;
import com.ai.orange.project.Project;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.run.PreparedRun;
import com.ai.orange.run.Run;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds a judge run: the slice of state it needs, as its prompt (project-space spec §7). No
 * judge keeps a context of its own; every stop gets a fresh, short run fed from Postgres.
 */
@Component
public class JudgePrompt {

    /** How many of the session's latest prompts and stops the judge reads. */
    static final int TURNS = 12;
    static final int TURN_CHARS = 600;
    static final int MAX_SPACE_ITEMS = 40;

    private final SessionRepository sessions;
    private final SessionService sessionService;
    private final SpaceService space;
    private final Conventions conventions;
    private final AttentionRepository attention;
    private final JudgeProperties props;
    private final HarnessProperties harness;

    public JudgePrompt(SessionRepository sessions, SessionService sessionService, SpaceService space,
                       Conventions conventions, AttentionRepository attention, JudgeProperties props,
                       HarnessProperties harness) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.space = space;
        this.conventions = conventions;
        this.attention = attention;
        this.props = props;
        this.harness = harness;
    }

    public PreparedRun prepare(Run run) throws IOException {
        if (run.attendedSessionId() == null) throw new IllegalArgumentException("judge run " + run.id() + " has no session");
        Session s = sessions.findById(run.attendedSessionId())
                .orElseThrow(() -> new IllegalStateException("session " + run.attendedSessionId() + " does not exist"));
        boolean stopHookActive = false;
        try {
            stopHookActive = Json.mapper().readTree(run.input().data()).path("stop_hook_active").asBoolean(false);
        } catch (Exception ignored) {
            // a judge run's input is orange's own; a broken one only loses this hint
        }
        return new PreparedRun(workingDirectory(run, s), prompt(s, sessionService.projectOf(s), stopHookActive), "judge",
                "S", false, 0, null);
    }

    /**
     * Where the judge may read: the session's checkout when this machine has it (process mode puts
     * the coordinator and the node on one machine), otherwise an empty directory of the run's own.
     */
    private Path workingDirectory(Run run, Session s) throws IOException {
        for (String candidate : new String[] {s.worktree(), s.cwd()}) {
            if (candidate != null && Files.isDirectory(Path.of(candidate))) return Path.of(candidate);
        }
        return Files.createDirectories(harness.runRoot().resolve(run.id().toString()).resolve("empty"));
    }

    String prompt(Session s, Project project, boolean stopHookActive) {
        StringBuilder b = new StringBuilder("# A session stopped: decide what happens next\n\n");
        b.append("Session: ").append(SessionService.label(s, project)).append(" (").append(s.harness()).append(")\n");
        if (s.cwd() != null) b.append("Working directory: ").append(s.cwd()).append('\n');
        b.append("orange has answered this session ").append(s.autoContinues()).append(" time(s) in a row; after ")
                .append(props.maxAutoContinues()).append(" the user is asked instead.\n");
        if (stopHookActive) b.append("This stop follows an answer orange just gave it.\n");

        b.append("\n## What the session is doing\n\n").append(s.intent() == null ? "(no prompt seen yet)" : s.intent())
                .append('\n');
        b.append("\n## Its last message\n\n").append(s.lastMessage() == null ? "(empty)" : s.lastMessage()).append('\n');

        List<SessionRepository.Turn> turns = sessions.recentTurns(s.id(), TURNS);
        if (!turns.isEmpty()) {
            b.append("\n## Recent turns, oldest first\n\n");
            for (SessionRepository.Turn turn : turns) {
                boolean user = NeutralEvent.USER_PROMPT.equals(turn.type());
                String text = payloadText(turn.payload(), user ? "prompt" : "last_message");
                b.append("- ").append(user ? "user: " : "session stopped, saying: ")
                        .append(SessionService.cut(text.replace('\n', ' '), TURN_CHARS)).append('\n');
            }
        }

        b.append("\n## Project\n\n");
        if (project == null) {
            b.append("This session belongs to no project orange knows. There are no goals or decisions to check against.\n");
        } else {
            b.append(project.name()).append(" (").append(project.status()).append(")");
            if (project.summary() != null) b.append(": ").append(project.summary());
            b.append('\n');
            List<SpaceItem> items = space.current(project.id()).stream()
                    .filter(i -> SpaceItem.ACTIVE.equals(i.status()))
                    .filter(i -> i.sessionId() == null || i.sessionId().equals(s.id()))
                    .toList();
            section(b, "Goals", items, SpaceItem.GOAL);
            section(b, "Milestones", items, SpaceItem.MILESTONE);
            section(b, "Decisions", items, SpaceItem.DECISION);
            section(b, "Notes", items, SpaceItem.NOTE);
        }

        b.append("\n## Conventions in force\n\n");
        for (String key : List.of("auto-answer", "auto-answer-confidence", "always-ask", "done", "integration")) {
            conventions.resolve(key, s.projectId(), s.id())
                    .ifPresent(v -> b.append("- ").append(key).append(": ").append(v).append('\n'));
        }

        List<AttentionItem> earlier = attention.findBySession(s.id());
        if (!earlier.isEmpty()) {
            b.append("\n## Earlier attention items of this session\n\n");
            earlier.stream().skip(Math.max(0, earlier.size() - 5)).forEach(i -> {
                b.append("- [").append(i.status()).append("] ").append(i.title());
                if (i.resolution() != null) b.append(" → the user answered: ").append(SessionService.cut(i.resolution(), 300));
                b.append('\n');
            });
        }
        return b.toString();
    }

    private static void section(StringBuilder b, String title, List<SpaceItem> items, String kind) {
        List<SpaceItem> of = items.stream().filter(i -> kind.equals(i.kind())).toList();
        if (of.isEmpty()) return;
        b.append("\n### ").append(title).append("\n\n");
        // The newest matter most when there are too many to show.
        of.stream().skip(Math.max(0, of.size() - MAX_SPACE_ITEMS)).forEach(i -> {
            b.append("- ").append(SessionService.cut(payloadText(i.body().data(), "text"), 500));
            if (i.dueAt() != null) b.append(" (due ").append(i.dueAt().toLocalDate()).append(')');
            if (!SpaceItem.USER.equals(i.authorKind())) b.append(" [by ").append(i.authorKind()).append(']');
            b.append('\n');
        });
    }

    private static String payloadText(String json, String field) {
        try {
            JsonNode node = Json.mapper().readTree(json);
            return node.path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
