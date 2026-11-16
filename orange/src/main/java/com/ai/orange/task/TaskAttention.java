package com.ai.orange.task;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionResolver;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.json.Json;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A stuck task asks for a person (remote-first spec §7.5). When a task goes {@code needs_human} (a
 * blocked report, a {@code request_human}, the reactor giving up, a failed integration) it files one
 * urgent {@code task_needs_human} item with the task's context. The user's answer reopens the task
 * with the answer as its note, which the next implement prompt carries; a blank answer only dismisses
 * the item. A reopen over REST or a cancel resolves the item instead ({@link TaskService#reopenTask},
 * {@code TaskCancellation}).
 */
@Component
public class TaskAttention implements AttentionResolver {

    private static final Logger log = LoggerFactory.getLogger(TaskAttention.class);

    static final String KEY = AttentionItem.TASK_NEEDS_HUMAN + ":";
    private static final int RECENT_EVENTS = 3;
    private static final int MAX_LINE = 160;
    private static final int MAX_WHY = 2_000;
    private static final int MAX_TITLE = 240;

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final ProjectRepository projects;
    private final AttentionService attention;
    private final TaskService taskService;
    private final StuckBranches branches;

    public TaskAttention(TaskRepository tasks, TaskEventRepository events, ProjectRepository projects,
                         AttentionService attention, TaskService taskService, StuckBranches branches) {
        this.branches = branches;
        this.tasks = tasks;
        this.events = events;
        this.projects = projects;
        this.attention = attention;
        this.taskService = taskService;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(AttentionItem.TASK_NEEDS_HUMAN);
    }

    /**
     * Files the urgent item for {@code task}, which has just gone {@code needs_human}. One item per
     * stuck event ({@code eventId}), so a task stuck again after a reopen files a new one. Never throws:
     * the task is already stuck, and the inbox is how the user learns it.
     *
     * <p>First the task's branch is pushed to GitHub ({@link StuckBranches#push}), so the item can name
     * it (and says that CI may run there); a push that fails only leaves the branch out, and one held
     * back on purpose (a review found gaps) says why.
     *
     * @param why the run's question, or why orange gave up, in words for the user
     */
    public void stuck(Task task, String why, long eventId) {
        try {
            StuckBranches.Pushed branch = branches.push(task);
            String title = clip(task.title() + " needs you", MAX_TITLE);
            // An event that could not be recorded (-1) has no id to dedupe on: a later stuck item must not be swallowed.
            String moment = eventId < 0 ? "unrecorded-" + UUID.randomUUID() : Long.toString(eventId);
            attention.raise(AttentionItem.TASK_NEEDS_HUMAN, KEY + task.id() + ":" + moment, AttentionItem.URGENT,
                    task.projectId(), null, task.id(), title, body(task, why, eventId, branch), null);
        } catch (RuntimeException e) {
            log.warn("task {} needs a person, but its attention item could not be filed: {}", task.id(), e.toString());
        }
    }

    /**
     * A non-blank answer reopens the task with the answer as its note, when the task still waits for
     * one; otherwise the item just resolves.
     */
    @Override
    public void onResolve(AttentionItem item, String answer) {
        if (item.taskId() == null || answer == null || answer.isBlank()) return;
        Task task = tasks.findById(item.taskId()).orElse(null);
        if (task == null || (task.status() != TaskStatus.NEEDS_HUMAN && task.status() != TaskStatus.FAILED)) return;
        if (taskService.reopen(task.id(), answer.strip(), "user")) {
            log.info("task {} reopened with the user's answer to item {}", task.id(), item.id());
        }
    }

    private String body(Task task, String why, long eventId, StuckBranches.Pushed branch) {
        StringBuilder b = new StringBuilder();
        String project = task.projectId() == null ? "no project"
                : projects.findById(task.projectId()).map(Project::name).orElse("no project");
        b.append("Task ").append(task.shortId()).append(" of ").append(project).append(": ")
                .append(oneLine(task.title())).append('\n');
        b.append("Why: ").append(clip(why == null || why.isBlank() ? "it needs a decision" : why.strip(), MAX_WHY))
                .append('\n');
        List<TaskEvent> recent = new ArrayList<>(events.findLatestByTask(task.id(), RECENT_EVENTS + 1).stream()
                .filter(e -> e.id() != eventId).limit(RECENT_EVENTS).toList());
        if (!recent.isEmpty()) {
            b.append("Recent:\n");
            for (TaskEvent e : recent.reversed()) b.append("- ").append(eventLine(e)).append('\n');
        }
        if (branch.branch() != null) {
            b.append("Branch: ").append(branch.branch()).append(" on GitHub, to look at the work so far\n");
            b.append("CI on the repo may run on that branch.\n");
        } else if (branch.withheld() != null) {
            b.append("Branch: not pushed to GitHub: ").append(branch.withheld()).append('\n');
        }
        b.append("Answer with what the task should do and orange reopens it with your answer as its note; "
                + "resolve it blank to leave the task waiting.");
        return b.toString();
    }

    /** {@code <type>: key=value …}, the payload's short scalar fields, one line. */
    static String eventLine(TaskEvent e) {
        StringBuilder b = new StringBuilder(e.eventType());
        try {
            JsonNode payload = e.payload() == null ? null : Json.mapper().readTree(e.payload().data());
            if (payload != null && payload.isObject()) {
                List<String> fields = new ArrayList<>();
                for (Map.Entry<String, JsonNode> f : payload.properties()) {
                    if (f.getKey().endsWith("_id") || !f.getValue().isValueNode() || f.getValue().isNull()) continue;
                    fields.add(f.getKey() + "=" + f.getValue().asText());
                }
                if (!fields.isEmpty()) b.append(": ").append(String.join(" ", fields));
            }
        } catch (Exception ignored) {
            // the type alone
        }
        return clip(oneLine(b.toString()), MAX_LINE);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
