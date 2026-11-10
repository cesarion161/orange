package com.ai.orange.mcp;

import com.ai.orange.api.SpaceViews;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.brief.BriefLinks;
import com.ai.orange.brief.BriefService;
import com.ai.orange.brief.BriefText;
import com.ai.orange.json.Json;
import com.ai.orange.project.Conventions;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectRepository;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionRepository;
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
 * the focus brief. Everything
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
    private final McpJsonMapper jsonMapper;

    public SteeringTools(ProjectService projectService, ProjectRepository projects, SpaceService space,
                         Conventions conventions, AttentionService attention, SessionRepository sessions,
                         BriefService briefs, BriefLinks links, McpJsonMapper jsonMapper) {
        this.projectService = projectService;
        this.projects = projects;
        this.space = space;
        this.conventions = conventions;
        this.attention = attention;
        this.sessions = sessions;
        this.briefs = briefs;
        this.links = links;
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
                        + "a repo by the absolute path of its main checkout (the repo and its sessions move to it).",
                        "{\"type\":\"object\",\"properties\":{" + PROJECT_ARG + ","
                                + "\"name\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"active\",\"paused\",\"archived\"]},"
                                + "\"summary\":{\"type\":\"string\"},"
                                + "\"add_repo\":{\"type\":\"string\",\"description\":\"Absolute path of the repo's main checkout.\"},"
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
                        + "Keys include done, integration, evidence, base-branch, remote-push, auto-answer, always-ask, "
                        + "auto-answer-confidence, cadence; value is any JSON.",
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
                        + "the answer must be confirm or ignore.",
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
                        this::focus));
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
        Project p = projectOf(req);
        Map<String, Object> out = SpaceViews.of(p);
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
        return ok(out);
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
        if (!repo.isEmpty()) projectService.addRepo(p.id(), string(req, "node"), repo);
        Map<String, Object> out = SpaceViews.of(p);
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
