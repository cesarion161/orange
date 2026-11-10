package com.ai.orange.api;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectRepo;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.session.Session;
import java.util.LinkedHashMap;
import java.util.Map;

/** How projects, space items, sessions and attention items look to the operator API and the steering MCP. */
public final class SpaceViews {

    private SpaceViews() {}

    /** Ids and times as strings, so a view renders the same through any JSON mapper. */
    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    public static Map<String, Object> of(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(p.id()));
        m.put("name", p.name());
        m.put("status", p.status());
        m.put("summary", p.summary());
        m.put("created_at", str(p.createdAt()));
        return m;
    }

    public static Map<String, Object> of(ProjectRepo r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node", r.nodeId());
        m.put("repo_root", r.repoRoot());
        m.put("remote_url", r.remoteUrl());
        m.put("default_branch", r.defaultBranch());
        return m;
    }

    public static Map<String, Object> of(SpaceItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(i.id()));
        m.put("kind", i.kind());
        if (i.key() != null) m.put("key", i.key());
        Object body = RunResponse.json(i.body());
        if (body instanceof Map<?, ?> b) {
            if (b.containsKey("text")) m.put("text", b.get("text"));
            if (b.containsKey("value")) m.put("value", b.get("value"));
        }
        m.put("status", i.status());
        m.put("author", i.authorRef() == null ? i.authorKind() : i.authorKind() + ":" + i.authorRef());
        if (i.dueAt() != null) m.put("due_at", str(i.dueAt()));
        if (i.sessionId() != null) m.put("session_id", str(i.sessionId()));
        if (i.supersedesId() != null) m.put("supersedes", str(i.supersedesId()));
        m.put("created_at", str(i.createdAt()));
        return m;
    }

    public static Map<String, Object> of(Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(s.id()));
        m.put("project_id", str(s.projectId()));
        m.put("harness", s.harness());
        m.put("external_id", s.externalId());
        m.put("node", s.nodeId());
        m.put("status", s.status());
        m.put("waiting_reason", s.waitingReason());
        m.put("waiting_since", str(s.waitingSince()));
        m.put("intent", s.intent());
        m.put("cwd", s.cwd());
        m.put("branch", s.branch());
        m.put("last_event_at", str(s.lastEventAt()));
        m.put("last_user_prompt_at", str(s.lastUserPromptAt()));
        return m;
    }

    public static Map<String, Object> of(AttentionItem a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(a.id()));
        m.put("kind", a.kind());
        m.put("urgency", a.urgency());
        m.put("status", a.status());
        m.put("title", a.title());
        m.put("body", a.body());
        if (a.proposedAnswer() != null) m.put("proposed_answer", a.proposedAnswer());
        m.put("project_id", str(a.projectId()));
        m.put("session_id", str(a.sessionId()));
        if (a.taskId() != null) m.put("task_id", str(a.taskId()));
        m.put("pushed_at", str(a.pushedAt()));
        if (a.resolvedAt() != null) {
            m.put("resolved_by", a.resolvedBy());
            m.put("resolution", a.resolution());
            m.put("resolved_at", str(a.resolvedAt()));
        }
        m.put("created_at", str(a.createdAt()));
        return m;
    }
}
