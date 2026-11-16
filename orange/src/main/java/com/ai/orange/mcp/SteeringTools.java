package com.ai.orange.mcp;

import com.ai.orange.api.SpaceViews;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.brief.BriefLinks;
import com.ai.orange.brief.BriefService;
import com.ai.orange.brief.BriefText;
import com.ai.orange.brief.MilestoneTracks;
import com.ai.orange.evidence.Evidence;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.json.Json;
import com.ai.orange.planner.Plan;
import com.ai.orange.planner.PlanRepository;
import com.ai.orange.planner.PlanService;
import com.ai.orange.project.Conventions;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectCreation;
import com.ai.orange.project.ProjectRepository;
import com.ai.orange.repo.Repo;
import com.ai.orange.repo.RepoKey;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceRepository;
import com.ai.orange.project.SpaceService;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunEventRepository;
import com.ai.orange.run.RunEventRow;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.run.TaskCancellation;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionRepository;
import com.ai.orange.status.StatusReport;
import com.ai.orange.status.StatusService;
import com.ai.orange.status.StatusText;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEvent;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The steering MCP tools ({@code /mcp/steering}; project-space spec §10): how the user, from any
 * Claude Code session, reads and writes the project space and the attention inbox, and reads
 * the focus brief; and, from the steering tab (remote-first spec §7.5), creates projects, hands
 * work to the planner, follows tasks and pauses or resumes the scheduler. Everything
 * written here is the user's own word (author {@code user}). Handlers never throw: a bad call
 * comes back as an error result that says what to correct.
 */
public class SteeringTools {

    private static final Logger log = LoggerFactory.getLogger(SteeringTools.class);

    private static final String PROJECT_ARG = "\"project\":{\"type\":\"string\",\"description\":\"Project name or id.\"}";

    private final ProjectService projectService;
    private final ProjectRepository projects;
    private final SpaceService space;
    private final Conventions conventions;
    private final AttentionService attention;
    private final SessionRepository sessions;
    private final BriefService briefs;
    private final BriefLinks links;
    private final Tasks tasks;
    private final McpJsonMapper jsonMapper;

    /** What the task tools read and act on (remote-first spec §7.5). */
    public record Tasks(PlanService planService, PlanRepository plans, StatusService status, TaskRepository tasks,
                        TaskEventRepository taskEvents, RunRepository runs, RunEventRepository runEvents,
                        EvidenceRepository evidence, AttentionRepository attentionItems, TaskCancellation cancellation,
                        SchedulerPause pause, ProjectCreation creation, MilestoneTracks milestones,
                        SpaceRepository spaceItems) {}

    public SteeringTools(ProjectService projectService, ProjectRepository projects, SpaceService space,
                         Conventions conventions, AttentionService attention, SessionRepository sessions,
                         BriefService briefs, BriefLinks links, Tasks tasks, McpJsonMapper jsonMapper) {
        this.projectService = projectService;
        this.projects = projects;
        this.space = space;
        this.conventions = conventions;
        this.attention = attention;
        this.sessions = sessions;
        this.briefs = briefs;
        this.links = links;
        this.tasks = tasks;
        this.jsonMapper = jsonMapper;
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                spec("orange_projects", "List every project orange knows, with its status and how many sessions "
                        + "and open attention items it has.", "{\"type\":\"object\",\"properties\":{}}", this::projects),
                spec("orange_project", "Show one project's space: its repos, goals, milestones, conventions, "
                        + "decisions, notes, open questions and proposals, and its recent sessions.",
                        "{\"type\":\"object\",\"properties\":{" + PROJECT_ARG + "},\"required\":[\"project\"]}",
                        this::project),
                spec("orange_project_update", "Create a project (give only name) or change one: rename it, set its "
                        + "status (active, paused, archived; archived mutes its sessions), set its summary, or attach "
                        + "a repo: its GitHub owner/name (orange works on it and keeps a mirror of it), or the absolute "
                        + "path of a checkout (the repo and its sessions move to it).",
                        "{\"type\":\"object\",\"properties\":{" + PROJECT_ARG + ","
                                + "\"name\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"active\",\"paused\",\"archived\"]},"
                                + "\"summary\":{\"type\":\"string\"},"
                                + "\"add_repo\":{\"type\":\"string\",\"description\":\"GitHub owner/name, or the absolute path of the repo's main checkout.\"},"
                                + "\"node\":{\"type\":\"string\",\"description\":\"The machine the repo is on; default local.\"}}}",
                        this::projectUpdate),
                spec("orange_add", "Add a goal, milestone, decision, note or question to a project's space. "
                        + "To change an existing item, pass its id as supersedes: items are never edited in place.",
                        "{\"type\":\"object\",\"properties\":{" + PROJECT_ARG + ","
                                + "\"kind\":{\"type\":\"string\",\"enum\":[\"goal\",\"milestone\",\"decision\",\"note\",\"question\"]},"
                                + "\"text\":{\"type\":\"string\"},"
                                + "\"due\":{\"type\":\"string\",\"description\":\"Milestones only, optional: a date (2026-12-01) or ISO date-time.\"},"
                                + "\"supersedes\":{\"type\":\"string\",\"description\":\"Id of the item this replaces.\"}},"
                                + "\"required\":[\"project\",\"kind\",\"text\"]}", this::add),
                spec("orange_set", "Set a convention for a project (or for one session of it, as an override). "
                        + "Keys include done, integration, evidence, base-branch, remote-push, review (never, risky, always), "
                        + "plan-approval (auto, always, never: whether a plan asks before it dispatches), "
                        + "auto-answer, always-ask, auto-answer-confidence, cadence; value is any JSON.",
                        "{\"type\":\"object\",\"properties\":{" + PROJECT_ARG + ","
                                + "\"key\":{\"type\":\"string\"},\"value\":{},"
                                + "\"session\":{\"type\":\"string\",\"description\":\"orange's session id, for a per-session override.\"}},"
                                + "\"required\":[\"project\",\"key\",\"value\"]}", this::set),
                spec("orange_accept", "Accept a proposal in a project's space: it becomes active.",
                        idSchema("The proposal's id."), req -> ok(SpaceViews.of(space.accept(uuid(req, "id"))))),
                spec("orange_drop", "Drop a space item (a proposal you decline, or an active item that no longer holds).",
                        idSchema("The item's id."), req -> ok(SpaceViews.of(space.drop(uuid(req, "id"))))),
                spec("orange_done", "Mark an active goal, milestone or question as done.",
                        idSchema("The item's id."), req -> ok(SpaceViews.of(space.done(uuid(req, "id"))))),
                spec("orange_attention", "List what wants the user, most urgent first: open items by default.",
                        "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"open\",\"acked\",\"resolved\",\"all\"]}}}",
                        this::attention),
                spec("orange_resolve", "Resolve an attention item with the user's answer. For a proposed project "
                        + "the answer must be confirm or ignore. For a plan that asks first: approve (dispatch it), "
                        + "drop, or a note that orange plans it again with. For a stuck task: the answer reopens the "
                        + "task with the answer as its note.",
                        "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"answer\":{\"type\":\"string\"}},"
                                + "\"required\":[\"id\"]}", this::resolve),
                spec("orange_sessions", "List the user's sessions orange follows, most recently active first: what "
                        + "each is doing, whether it is working, stopped or waiting, and since when.",
                        "{\"type\":\"object\",\"properties\":{" + PROJECT_ARG + ","
                                + "\"all\":{\"type\":\"boolean\",\"description\":\"Include ended sessions.\"}}}",
                        this::sessions),
                spec("orange_focus", "The focus brief: where each active project stands, what is waiting on the "
                        + "user and what to focus on next. Returns the latest brief; fresh: true writes a new one, "
                        + "which can take a minute.",
                        "{\"type\":\"object\",\"properties\":{\"fresh\":{\"type\":\"boolean\","
                                + "\"description\":\"Write a new brief now instead of returning the latest one.\"}}}",
                        this::focus),
                spec("orange_plan", "Hand work to orange when the user asks to build, add, fix or change something in "
                        + "one of their projects (\"build X in Y\"). orange plans it in the background on the "
                        + "project's repo, then dispatches the tasks on its own or asks the user first with one "
                        + "attention item. Returns at once with the plan's id; orange_status shows where it is.",
                        "{\"type\":\"object\",\"properties\":{" + PROJECT_ARG + ","
                                + "\"request\":{\"type\":\"string\",\"description\":\"What to build, in the user's "
                                + "words, complete: the planner sees nothing else of this conversation.\"},"
                                + "\"milestone\":{\"type\":\"string\",\"description\":\"Optional: the milestone the "
                                + "work serves, by its id or its text.\"}},"
                                + "\"required\":[\"project\",\"request\"]}", this::plan),
                spec("orange_status", "Everything in flight, by project: plans being made or waiting for approval, "
                        + "unfinished tasks (stuck first, with the short ids orange_task takes), what was integrated "
                        + "today, milestones on track or behind, what waits for the user, and whether the scheduler "
                        + "is paused. Use it for \"what's the status\", \"what's running\" or \"are we on track\"; "
                        + "for what to focus on, orange_focus.",
                        "{\"type\":\"object\",\"properties\":{}}", this::status),
                spec("orange_task", "One task in detail, when the user asks about a task or why one is stuck: its "
                        + "status, project, milestone and plan, its runs (kind, status, why it failed, cost), its "
                        + "last events, its evidence and what of it waits for the user.",
                        taskIdSchema(""), this::task),
                spec("orange_tail", "What a task's agent is doing right now: the latest events of its running run (or "
                        + "of its last run) as short lines: its messages, tool calls and their results. Use it to "
                        + "follow a task live; call it again for newer events.",
                        taskIdSchema(",\"limit\":{\"type\":\"integer\",\"description\":\"How many events, "
                                + "default 20, at most 100.\"}"), this::tail),
                spec("orange_cancel", "Cancel a task the user no longer wants: its queued runs are dropped, a running "
                        + "agent is stopped and its open questions are resolved. A cancelled task is not resumed.",
                        taskIdSchema(""), this::cancel),
                spec("orange_pause", "Pause orange's scheduler when the user says stop, hold or pause: no new run "
                        + "starts until orange_resume, and runs that are running finish. Plans are refused meanwhile.",
                        "{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\","
                                + "\"description\":\"Why, in a few words; shown in the status.\"}}}", this::pause),
                spec("orange_resume", "Resume orange's scheduler after a pause, whoever paused it: the user, or orange "
                        + "itself (a plan limit, a rejected token, Docker missing).",
                        "{\"type\":\"object\",\"properties\":{}}", this::resume),
                spec("orange_project_create", "Start a new project when the user asks for one (\"start a project X "
                        + "on owner/repo\", \"new project X with a new repo\"). With repo owner/name orange checks it "
                        + "can read the repo and clones it; with create: true it first creates a private GitHub repo "
                        + "with a first commit (repo may then be only a name, or left out for one named after the "
                        + "project). The project is active at once. evidence lists the commands that prove work is "
                        + "done (the tests); without them orange can implement there but not integrate. To change an "
                        + "existing project use orange_project_update.",
                        "{\"type\":\"object\",\"properties\":{"
                                + "\"name\":{\"type\":\"string\",\"description\":\"The project's name.\"},"
                                + "\"repo\":{\"type\":\"string\",\"description\":\"GitHub owner/name; with create "
                                + "also a bare name.\"},"
                                + "\"create\":{\"type\":\"boolean\",\"description\":\"Create the repo on GitHub "
                                + "(private) first.\"},"
                                + "\"evidence\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                                + "\"description\":\"Shell commands that must pass before work is integrated, "
                                + "such as ./gradlew test.\"},"
                                + "\"summary\":{\"type\":\"string\",\"description\":\"One line on what the "
                                + "project is.\"}},"
                                + "\"required\":[\"name\"]}", this::projectCreate));
    }

    private CallToolResult projects(CallToolRequest req) {
        List<AttentionItem> open = attention.list(AttentionItem.OPEN, 200);
        List<Session> live = sessions.findRecent(false, null, 200);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Project p : projects.findAll()) {
            Map<String, Object> row = SpaceViews.of(p);
            row.put("sessions", live.stream().filter(s -> p.id().equals(s.projectId())).count());
            row.put("open_attention", open.stream().filter(a -> p.id().equals(a.projectId())).count());
            rows.add(row);
        }
        return ok(rows);
    }

    private CallToolResult project(CallToolRequest req) {
        return ok(projectView(projectOf(req)));
    }

    /** A project as {@code orange_project} shows it. */
    private Map<String, Object> projectView(Project p) {
        Map<String, Object> out = SpaceViews.of(p);
        out.put("github_repos", projectService.githubRepos(p.id()).stream().map(SpaceViews::of).toList());
        out.put("repos", projects.repos(p.id()).stream().map(SpaceViews::of).toList());
        Map<String, List<Map<String, Object>>> byKind = new LinkedHashMap<>();
        List<Map<String, Object>> proposals = new ArrayList<>();
        for (SpaceItem item : space.current(p.id())) {
            if (SpaceItem.PROPOSED.equals(item.status())) proposals.add(SpaceViews.of(item));
            else byKind.computeIfAbsent(item.kind() + "s", k -> new ArrayList<>()).add(SpaceViews.of(item));
        }
        out.putAll(byKind);
        out.put("proposals", proposals);
        out.put("global_conventions", Json.mapper().convertValue(conventions.globals(), Map.class));
        out.put("sessions", sessions.findRecent(false, p.id(), 20).stream().map(SpaceViews::of).toList());
        return out;
    }

    private CallToolResult projectUpdate(CallToolRequest req) {
        String ref = string(req, "project");
        String name = string(req, "name");
        Project p;
        if (ref.isEmpty()) {
            if (name.isEmpty()) return error("give project (to change one) or name (to create one)");
            p = projectService.create(name, string(req, "summary"));
        } else {
            p = projectService.update(projectOf(req).id(), name.isEmpty() ? null : name,
                    string(req, "status").isEmpty() ? null : string(req, "status"),
                    arg(req, "summary") instanceof String s ? s : null);
        }
        String repo = string(req, "add_repo");
        if (!repo.isEmpty()) {
            if (repo.startsWith("/")) projectService.addRepo(p.id(), string(req, "node"), repo);
            else projectService.addGithubRepo(p.id(), RepoKey.of(repo));
        }
        Map<String, Object> out = SpaceViews.of(p);
        out.put("github_repos", projectService.githubRepos(p.id()).stream().map(SpaceViews::of).toList());
        out.put("repos", projects.repos(p.id()).stream().map(SpaceViews::of).toList());
        return ok(out);
    }

    private CallToolResult add(CallToolRequest req) {
        Project p = projectOf(req);
        String supersedes = string(req, "supersedes");
        return ok(SpaceViews.of(space.add(p.id(), string(req, "kind"), string(req, "text"), SpaceService.Author.USER,
                due(string(req, "due")), null, supersedes.isEmpty() ? null : uuid(req, "supersedes"))));
    }

    private CallToolResult set(CallToolRequest req) {
        Project p = projectOf(req);
        Object raw = arg(req, "value");
        if (raw == null) return error("value is required");
        JsonNode value = Json.mapper().valueToTree(raw);
        UUID session = string(req, "session").isEmpty() ? null : uuid(req, "session");
        if (session != null && sessions.findById(session).filter(s -> p.id().equals(s.projectId())).isEmpty()) {
            return error("no such session in project " + p.name());
        }
        return ok(SpaceViews.of(space.setConvention(p.id(), session, string(req, "key"), value, SpaceService.Author.USER)));
    }

    private CallToolResult attention(CallToolRequest req) {
        String status = string(req, "status");
        if (status.isEmpty()) status = AttentionItem.OPEN;
        if (!List.of("open", "acked", "resolved", "all").contains(status)) {
            return error("status must be open, acked, resolved or all");
        }
        return ok(attention.list("all".equals(status) ? null : status, 100).stream().map(SpaceViews::of).toList());
    }

    private CallToolResult resolve(CallToolRequest req) {
        String answer = string(req, "answer");
        return attention.resolve(uuid(req, "id"), answer.isEmpty() ? null : answer, "user")
                .map(item -> ok(SpaceViews.of(item)))
                .orElseGet(() -> error("no such attention item"));
    }

    private CallToolResult sessions(CallToolRequest req) {
        UUID projectId = string(req, "project").isEmpty() ? null : projectOf(req).id();
        boolean all = Boolean.TRUE.equals(arg(req, "all"));
        return ok(sessions.findRecent(all, projectId, 100).stream().map(SpaceViews::of).toList());
    }

    /** The latest brief, or with {@code fresh} a new one; reading never starts a run. */
    private CallToolResult focus(CallToolRequest req) {
        BriefService.Focus f = briefs.focus(Boolean.TRUE.equals(arg(req, "fresh")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", f.state());
        if (f.hint() != null) out.put("hint", f.hint());
        if (f.brief() != null) {
            out.put("brief", SpaceViews.of(f.brief(), BriefText.text(f.brief()), links.page(f.brief())));
        }
        return ok(out);
    }

    // ───────────────────────────── the task tools ─────────────────────────────

    private CallToolResult plan(CallToolRequest req) {
        String project = string(req, "project");
        if (project.isEmpty()) return error("project is required");
        String milestone = string(req, "milestone");
        Plan plan = tasks.planService().start(project, string(req, "request"), milestone.isEmpty() ? null : milestone);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan_id", plan.id().toString());
        out.put("status", Plan.PLANNING);
        out.put("hint", "orange plans this in the background; it dispatches on its own or asks you first. "
                + "orange_status shows where it is.");
        return ok(out);
    }

    private CallToolResult status(CallToolRequest req) {
        StatusReport report = tasks.status().report();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", StatusText.text(report));
        out.put("report", report.toJson());
        return ok(out);
    }

    private CallToolResult task(CallToolRequest req) {
        Task t = taskOf(req);
        Map<String, Object> view = taskView(t);
        if (t.description() != null) view.put("description", t.description());
        view.put("failures", t.failures());
        view.put("branch", t.branch());
        view.put("head_sha", t.headSha());
        if (t.projectId() != null) {
            projectService.find(t.projectId().toString()).ifPresent(p -> view.put("project", p.name()));
            view.put("project_id", t.projectId().toString());
            projectService.githubRepos(t.projectId()).stream().filter(r -> r.id().equals(t.repoId())).findFirst()
                    .map(Repo::key).ifPresent(k -> view.put("repo", k.toString()));
        }
        if (t.milestoneId() != null) {
            tasks.milestones().current(t.milestoneId()).flatMap(tasks.spaceItems()::findById).ifPresent(m -> {
                view.put("milestone", milestoneText(m));
                view.put("milestone_id", m.id().toString());
            });
        }
        if (t.planId() != null) {
            tasks.plans().findById(t.planId()).ifPresent(p -> {
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("id", p.id().toString());
                plan.put("status", p.status());
                plan.put("request", p.request());
                view.put("plan", plan);
            });
        }
        view.put("created_at", text(t.createdAt()));
        view.put("updated_at", text(t.updatedAt()));

        List<Map<String, Object>> runs = new ArrayList<>();
        for (Run r : tasks.runs().findByTask(t.id())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id().toString());
            m.put("kind", r.kind().dbValue());
            m.put("status", r.status().dbValue());
            m.put("reason", reason(r));
            m.put("cost_usd", r.costUsd());
            m.put("model", r.model());
            m.put("attempt", r.attempt());
            m.put("queued_at", text(r.queuedAt()));
            m.put("finished_at", text(r.finishedAt()));
            runs.add(m);
        }
        List<Map<String, Object>> events = new ArrayList<>();
        List<TaskEvent> latest = new ArrayList<>(tasks.taskEvents().findLatestByTask(t.id(), 15));
        Collections.reverse(latest);
        for (TaskEvent e : latest) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.eventType());
            m.put("actor", e.actor());
            m.put("at", text(e.createdAt()));
            m.put("payload", e.payload() == null ? null : parse(e.payload().data()));
            events.add(m);
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Evidence e : tasks.evidence().findByTask(t.id())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", e.command() != null ? e.command() : e.kind());
            m.put("kind", e.kind());
            m.put("passed", e.passed());
            m.put("exit_code", e.exitCode());
            m.put("head_sha", e.headSha());
            m.put("at", text(e.createdAt()));
            checks.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", view);
        out.put("runs", runs);
        out.put("events", events);
        out.put("evidence", checks);
        out.put("attention", tasks.attentionItems().findUnresolvedByTask(t.id()).stream().map(SpaceViews::of).toList());
        return ok(out);
    }

    private CallToolResult tail(CallToolRequest req) {
        Task t = taskOf(req);
        int limit = arg(req, "limit") instanceof Number n ? Math.clamp(n.intValue(), 1, 100) : 20;
        List<Run> all = tasks.runs().findByTask(t.id());
        Run run = all.stream().filter(r -> r.status() == RunStatus.RUNNING).reduce((a, b) -> b)
                .orElse(all.isEmpty() ? null : all.getLast());
        if (run == null) return error("task " + t.shortId() + " has no run yet (it is " + t.status().dbValue() + ")");
        List<RunEventRow> latest = new ArrayList<>(tasks.runEvents().findLatestByRun(run.id(), limit));
        Collections.reverse(latest);
        List<Map<String, Object>> events = new ArrayList<>();
        for (RunEventRow e : latest) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.seq());
            m.put("type", e.type());
            m.put("text", EventLines.text(e.type(), e.payload() == null ? null : e.payload().data()));
            m.put("at", text(e.createdAt()));
            events.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", run.id().toString());
        r.put("kind", run.kind().dbValue());
        r.put("status", run.status().dbValue());
        r.put("started_at", text(run.startedAt()));
        if (run.finishedAt() != null) r.put("finished_at", text(run.finishedAt()));
        if (reason(run) != null) r.put("reason", reason(run));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", taskView(t));
        out.put("run", r);
        out.put("events", events);
        return ok(out);
    }

    private CallToolResult cancel(CallToolRequest req) {
        Task t = taskOf(req);
        Task before = tasks.cancellation().cancel(t.id()).orElse(t);
        Task now = tasks.tasks().findById(t.id()).orElse(before);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", taskView(now));
        out.put("was", before.status().dbValue());
        if (before.status().isTerminal()) out.put("hint", "it was already " + before.status().dbValue() + "; nothing changed");
        return ok(out);
    }

    private CallToolResult pause(CallToolRequest req) {
        String reason = string(req, "reason");
        tasks.pause().pause(reason.isEmpty() ? "user" : "user: " + reason, null);
        return ok(schedulerView());
    }

    private CallToolResult resume(CallToolRequest req) {
        tasks.pause().resume();
        return ok(schedulerView());
    }

    private CallToolResult projectCreate(CallToolRequest req) {
        Object evidence = arg(req, "evidence");
        JsonNode commands = evidence == null ? null : Json.mapper().valueToTree(evidence);
        Project p = tasks.creation().create(string(req, "name"), string(req, "summary"), string(req, "repo"),
                Boolean.TRUE.equals(arg(req, "create")), commands);
        Map<String, Object> out = projectView(p);
        List<String> hints = new ArrayList<>();
        if (projectService.githubRepos(p.id()).isEmpty()) {
            hints.add("It has no repo: attach one with orange_project_update add_repo owner/name before planning.");
        }
        if (commands == null) {
            hints.add("No evidence commands are in force: orange can implement here but not integrate. Set them with "
                    + "orange_set key=evidence, or approve a plan that proposes them.");
        }
        if (!hints.isEmpty()) out.put("hint", String.join(" ", hints));
        return ok(out);
    }

    /** A task by its id, its short id (the last 8 characters) or a longer prefix. */
    private Task taskOf(CallToolRequest req) {
        String ref = string(req, "id");
        if (ref.isEmpty()) throw new IllegalArgumentException("id is required: the task's short id from orange_status");
        TaskRepository.RefMatch match = tasks.tasks().findByRef(ref, 1);
        if (match.count() == 0) throw new IllegalArgumentException("no task " + ref);
        if (match.count() > 1) {
            throw new IllegalArgumentException("ambiguous id: " + match.count() + " tasks start or end with " + ref
                    + "; give more characters");
        }
        return match.tasks().getFirst();
    }

    private static Map<String, Object> taskView(Task t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id().toString());
        m.put("short_id", t.shortId());
        m.put("title", t.title());
        m.put("status", t.status().dbValue());
        return m;
    }

    /** Why a run ended: its failure reason, else the status its agent reported. */
    private static String reason(Run r) {
        if (r.error() != null) return r.error();
        if (r.outcome() == null) return null;
        JsonNode outcome = parse(r.outcome().data());
        return outcome != null && outcome.hasNonNull("status") ? outcome.path("status").asText() : null;
    }

    private static String milestoneText(SpaceItem m) {
        JsonNode body = m.body() == null ? null : parse(m.body().data());
        return body == null ? "" : body.path("text").asText("");
    }

    private Map<String, Object> schedulerView() {
        Map<String, Object> out = new HashMap<>();
        out.put("paused", tasks.pause().current().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", p.reason());
            m.put("until", text(p.until()));
            m.put("since", text(p.since()));
            return m;
        }).orElse(null));
        return out;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static JsonNode parse(String json) {
        if (json == null) return null;
        try {
            return Json.mapper().readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ───────────────────────────── plumbing ─────────────────────────────

    private SyncToolSpecification spec(String name, String description, String schema,
                                       Function<CallToolRequest, CallToolResult> handler) {
        Tool tool = Tool.builder(name, jsonMapper, schema).description(description).build();
        return SyncToolSpecification.builder().tool(tool).callHandler((ctx, req) -> {
            try {
                return handler.apply(req);
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            } catch (RuntimeException e) {
                log.warn("steering tool {} failed", name, e);
                return error(name + " failed: " + e.getClass().getSimpleName());
            }
        }).build();
    }

    private static String taskIdSchema(String more) {
        return "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"The task's "
                + "short id as orange_status shows it (its last 8 characters), a longer prefix, or its full id.\"}"
                + more + "},\"required\":[\"id\"]}";
    }

    private static String idSchema(String description) {
        return "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"" + description
                + "\"}},\"required\":[\"id\"]}";
    }

    private Project projectOf(CallToolRequest req) {
        String ref = string(req, "project");
        if (ref.isEmpty()) throw new IllegalArgumentException("project is required");
        return projectService.find(ref).orElseThrow(() -> new IllegalArgumentException("no such project: " + ref));
    }

    private static Object arg(CallToolRequest req, String key) {
        return req.arguments() == null ? null : req.arguments().get(key);
    }

    private static String string(CallToolRequest req, String key) {
        return arg(req, key) instanceof String s ? s.strip() : "";
    }

    private static UUID uuid(CallToolRequest req, String key) {
        String raw = string(req, key);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " must be a full id (a UUID)" + (raw.isEmpty() ? "" : ": " + raw));
        }
    }

    /** A date (end of that day, the coordinator's zone) or an ISO date-time with an offset. */
    static OffsetDateTime due(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return raw.length() <= 10
                    ? LocalDate.parse(raw).atTime(23, 59).atZone(ZoneId.systemDefault()).toOffsetDateTime()
                    : OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("due must be a date like 2026-12-01 or an ISO date-time with an offset");
        }
    }

    private static CallToolResult ok(Object value) {
        try {
            return CallToolResult.builder().addTextContent(Json.mapper().writeValueAsString(value)).isError(false).build();
        } catch (JsonProcessingException e) {
            return error("could not render the result");
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message == null ? "invalid call" : message).isError(true).build();
    }
}
