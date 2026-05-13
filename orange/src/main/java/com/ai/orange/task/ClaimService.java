package com.ai.orange.task;

import com.ai.orange.agent.Agent;
import com.ai.orange.agent.AgentRepository;
import com.ai.orange.agent.AgentRun;
import com.ai.orange.agent.AgentRunRepository;
import com.ai.orange.agent.AgentRunStatus;
import com.ai.orange.concurrency.ConcurrencyGate;
import com.ai.orange.worktree.WorktreeProperties;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat-as-executor lease workflow. Wraps task-claim, heartbeat, complete, fail
 * and release as a single transactional surface so chat-driven workers (and
 * eventually headless ones) can drive a task through the FSM without owning
 * any of the orchestrator's internals.
 *
 * <p>The contract differs from the subprocess executor in one important way:
 * the chat session is a long-lived stateless caller, so we never trust the
 * presence of a row in {@code tasks.claimed_by} alone. Each mutating call has
 * to present the {@code claim_token} we handed out on claim — that, plus the
 * orchestrator-owned reaper, is the dead-worker recovery story.
 */
@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    /**
     * Headers in seeded agent rows talk about a "default lease". Whoever
     * configures the system can override via {@code orange.claims.lease-ttl}
     * — we keep the default well below the heartbeat reaper window so an
     * absent chat reliably expires.
     */
    private final Duration leaseTtl;

    private final TaskRepository tasks;
    private final TaskClaimRepository claims;
    private final TaskEdgeRepository edges;
    private final TaskEventRepository events;
    private final AgentRepository agents;
    private final AgentRunRepository agentRuns;
    private final WorktreeService worktrees;
    private final WorktreeProperties worktreeProps;
    private final ConcurrencyGate concurrencyGate;
    private final ObjectMapper json = new ObjectMapper();

    public ClaimService(TaskRepository tasks,
                        TaskClaimRepository claims,
                        TaskEdgeRepository edges,
                        TaskEventRepository events,
                        AgentRepository agents,
                        AgentRunRepository agentRuns,
                        WorktreeService worktrees,
                        WorktreeProperties worktreeProps,
                        ConcurrencyGate concurrencyGate,
                        @Value("${orange.claims.lease-ttl:2m}") Duration leaseTtl) {
        this.tasks = tasks;
        this.claims = claims;
        this.edges = edges;
        this.events = events;
        this.agents = agents;
        this.agentRuns = agentRuns;
        this.worktrees = worktrees;
        this.worktreeProps = worktreeProps;
        this.concurrencyGate = concurrencyGate;
        this.leaseTtl = leaseTtl;
    }

    public Duration leaseTtl() {
        return leaseTtl;
    }

    /**
     * Atomically claim the next {@code ready} task for {@code role} and issue
     * a lease for {@code workerId}. Also creates the agent-run row so chat
     * sessions appear in the same audit trail as subprocess runs, and
     * prepares the worktree if it doesn't already exist (so the worker can
     * immediately start typing in {@code cwd}).
     */
    @Transactional
    public Optional<ClaimedTask> claimNext(String role, String workerId) {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role required");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("worker_id required");

        // Per-role parallelism cap (see ConcurrencyProperties). Approximate —
        // two workers racing past the same threshold may both pass the check,
        // but the next claim after that is refused. Sufficient as a throttle.
        if (!concurrencyGate.canClaim(role)) {
            log.debug("claimNext: role={} at cap, refusing worker={}", role, workerId);
            return Optional.empty();
        }

        Optional<Task> taken = tasks.claimNextReady(role, workerId);
        if (taken.isEmpty()) return Optional.empty();
        Task task = taken.get();

        Agent agent = agents.findEnabledByRole(role).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no enabled agent for role " + role));

        int attempt = agentRuns.nextAttempt(task.id());
        String prompt = buildPrompt(task, dependencyArtifacts(task.id()));
        AgentRun run = agentRuns.start(task.id(), agent.id(), attempt, prompt, null);

        // The worktree create is a side-effecting shell-out. If baseRepo is
        // unconfigured (typical in tests and early bring-up) we skip it and
        // return the worktree path the worker should consider canonical — they
        // can mkdir it themselves. When configured we create a real worktree
        // so the chat session is sandboxed exactly like the subprocess flow.
        String cwd;
        if (worktreeProps.baseRepo() == null || worktreeProps.baseRepo().isBlank()) {
            cwd = worktrees.resolve(task.id()).toString();
        } else {
            try {
                cwd = worktrees.create(task.id(), "main").toString();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "worktree create failed for task " + task.id() + ": " + e.getMessage(), e);
            }
        }

        UUID claimToken = UUID.randomUUID();
        OffsetDateTime expiresAt = OffsetDateTime.now().plus(leaseTtl);
        claims.insert(task.id(), claimToken, workerId, attempt, expiresAt);

        events.append(task.id(), workerId, "claim_acquired", jsonbOf(Map.of(
                "claim_token", claimToken.toString(),
                "worker_id", workerId,
                "attempt", attempt,
                "lease_ttl_seconds", leaseTtl.toSeconds())));

        List<UUID> deps = edges.parentsOf(task.id());
        return Optional.of(new ClaimedTask(
                task,
                claimToken,
                expiresAt,
                attempt,
                prompt,
                cwd,
                deps,
                run.id()));
    }

    /**
     * Bump the claim's expiry and report any pending cancel request. Chat
     * sessions are expected to call this every ~30s.
     */
    @Transactional
    public HeartbeatResult heartbeat(UUID claimToken) {
        Optional<TaskClaim> claim = claims.findByToken(claimToken);
        if (claim.isEmpty()) {
            return HeartbeatResult.expired();
        }
        OffsetDateTime newExpires = OffsetDateTime.now().plus(leaseTtl);
        Optional<TaskClaim> refreshed = claims.heartbeat(claimToken, newExpires);
        if (refreshed.isEmpty()) return HeartbeatResult.expired();
        tasks.updateHeartbeat(refreshed.get().taskId(), OffsetDateTime.now());
        return HeartbeatResult.alive(
                refreshed.get().taskId(),
                refreshed.get().cancelRequested(),
                refreshed.get().expiresAt());
    }

    /**
     * Mark the claim's task as finished and transition into {@code pr_open}.
     * Persists artifacts on {@code tasks.metadata} so downstream task prompts
     * (built by {@link #buildPrompt}) can read what this run produced.
     */
    @Transactional
    public CompleteResult complete(UUID claimToken, String summary, Map<String, Object> artifacts) {
        TaskClaim claim = claims.findByToken(claimToken).orElse(null);
        if (claim == null) return CompleteResult.expired();

        Task task = tasks.findById(claim.taskId()).orElse(null);
        if (task == null) return CompleteResult.expired();

        AgentRunStatus runStatus = AgentRunStatus.SUCCEEDED;
        Map<String, Object> mergedMeta = mergeArtifactsIntoMetadata(task, summary, artifacts);
        tasks.updateMetadata(task.id(), jsonbOf(mergedMeta));

        // Chat-driven flow short-circuits the pipeline: tasks land at pr_open
        // (the downstream consumer either opens the PR via a follow-up tool
        // call or marks it dev_ready directly).
        boolean transitioned = tasks.transitionStatus(task.id(),
                TaskStatus.IN_PROGRESS, TaskStatus.PR_OPEN);
        if (!transitioned) {
            log.warn("complete: task {} not in_progress (was {}); leaving status as-is",
                    task.id(), task.status());
        }
        finishLatestAgentRun(task.id(), runStatus);

        events.append(task.id(), "system", "claim_complete", jsonbOf(Map.of(
                "claim_token", claimToken.toString(),
                "summary", summary == null ? "" : summary,
                "artifacts", artifacts == null ? Map.of() : artifacts)));

        claims.deleteByToken(claimToken);
        return CompleteResult.ok(task.id());
    }

    /**
     * Mark the claim's run as failed. If {@code retryable} and the task hasn't
     * burnt the per-task budget, push it back to {@code ready} for a fresh
     * attempt; otherwise transition to {@code failed} and append the reason.
     */
    @Transactional
    public FailResult fail(UUID claimToken, String reason, boolean retryable, int maxAttempts) {
        TaskClaim claim = claims.findByToken(claimToken).orElse(null);
        if (claim == null) return FailResult.expired();

        Task task = tasks.findById(claim.taskId()).orElse(null);
        if (task == null) return FailResult.expired();

        events.append(task.id(), "system", "claim_failed", jsonbOf(Map.of(
                "claim_token", claimToken.toString(),
                "reason", reason == null ? "" : reason,
                "retryable", retryable,
                "attempt", claim.attempt())));
        finishLatestAgentRun(task.id(), AgentRunStatus.FAILED);

        TaskStatus next;
        if (retryable && claim.attempt() < maxAttempts) {
            next = TaskStatus.READY;
        } else {
            next = TaskStatus.FAILED;
        }
        boolean transitioned = tasks.transitionStatus(task.id(), TaskStatus.IN_PROGRESS, next);
        if (!transitioned) {
            log.warn("fail: task {} not in_progress (was {}); leaving status as-is",
                    task.id(), task.status());
        }
        claims.deleteByToken(claimToken);
        return FailResult.ok(task.id(), next);
    }

    /**
     * Cooperative drop. Unlike {@code fail}, this does <em>not</em> count
     * against the retry budget — the task is bounced straight back to
     * {@code ready}. Use when a chat session has to leave for reasons
     * unrelated to the task itself.
     */
    @Transactional
    public ReleaseResult release(UUID claimToken, String reason) {
        TaskClaim claim = claims.findByToken(claimToken).orElse(null);
        if (claim == null) return ReleaseResult.expired();

        Task task = tasks.findById(claim.taskId()).orElse(null);
        if (task == null) return ReleaseResult.expired();

        events.append(task.id(), claim.workerId(), "claim_released", jsonbOf(Map.of(
                "claim_token", claimToken.toString(),
                "reason", reason == null ? "" : reason)));
        finishLatestAgentRun(task.id(), AgentRunStatus.CANCELLED);

        boolean transitioned = tasks.transitionStatus(task.id(),
                TaskStatus.IN_PROGRESS, TaskStatus.READY);
        if (!transitioned) {
            log.warn("release: task {} not in_progress (was {})", task.id(), task.status());
        }
        claims.deleteByToken(claimToken);
        return ReleaseResult.ok(task.id());
    }

    /** Active claims held by {@code workerId}. Used to resume a chat session after restart. */
    public List<TaskClaim> activeClaims(String workerId) {
        return claims.findByWorker(workerId);
    }

    /**
     * Collect each parent task's stored artifacts (typically a "summary" and a
     * map of named outputs) for inclusion in a downstream task's prompt.
     */
    public Map<UUID, Map<String, Object>> dependencyArtifacts(UUID taskId) {
        List<UUID> parents = edges.parentsOf(taskId);
        if (parents.isEmpty()) return Map.of();
        Map<UUID, Map<String, Object>> out = new LinkedHashMap<>();
        for (UUID parent : parents) {
            tasks.findById(parent).ifPresent(p -> out.put(parent, readArtifacts(p)));
        }
        return out;
    }

    private Map<String, Object> readArtifacts(Task t) {
        JSONB meta = t.metadata();
        if (meta == null) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(meta.data(), Map.class);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("title", t.title());
            out.put("status", t.status().dbValue());
            if (parsed.get("summary") != null) out.put("summary", parsed.get("summary"));
            if (parsed.get("artifacts") != null) out.put("artifacts", parsed.get("artifacts"));
            if (parsed.get("pr_url") != null) out.put("pr_url", parsed.get("pr_url"));
            return out;
        } catch (IOException e) {
            return Map.of("title", t.title());
        }
    }

    private void finishLatestAgentRun(UUID taskId, AgentRunStatus status) {
        agentRuns.findActiveByTask(taskId).ifPresent(run ->
                agentRuns.finish(run.id(), status, run.costUsd(), run.tokensIn(), run.tokensOut()));
    }

    private String buildPrompt(Task task, Map<UUID, Map<String, Object>> deps) {
        StringBuilder sb = new StringBuilder(task.title());
        if (task.description() != null && !task.description().isBlank()) {
            sb.append("\n\n").append(task.description());
        }
        if (!deps.isEmpty()) {
            sb.append("\n\n--- Dependency artifacts ---\n");
            for (Map.Entry<UUID, Map<String, Object>> e : deps.entrySet()) {
                sb.append("\n## ").append(e.getValue().getOrDefault("title", e.getKey())).append('\n');
                try {
                    sb.append(json.writerWithDefaultPrettyPrinter().writeValueAsString(e.getValue()));
                } catch (JsonProcessingException ex) {
                    sb.append(e.getValue());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private Map<String, Object> mergeArtifactsIntoMetadata(Task task, String summary,
                                                           Map<String, Object> artifacts) {
        Map<String, Object> meta = new HashMap<>();
        if (task.metadata() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> current = json.readValue(task.metadata().data(), Map.class);
                meta.putAll(current);
            } catch (IOException e) {
                log.warn("could not parse existing metadata for task {}: {}", task.id(), e.getMessage());
            }
        }
        if (summary != null) meta.put("summary", summary);
        if (artifacts != null && !artifacts.isEmpty()) {
            Object existing = meta.get("artifacts");
            if (existing instanceof Map<?, ?> existingMap) {
                Map<String, Object> merged = new HashMap<>();
                existingMap.forEach((k, v) -> merged.put(String.valueOf(k), v));
                merged.putAll(artifacts);
                meta.put("artifacts", merged);
            } else {
                meta.put("artifacts", artifacts);
            }
        }
        return meta;
    }

    private JSONB jsonbOf(Map<String, Object> value) {
        try {
            return JSONB.valueOf(json.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            return JSONB.valueOf("{}");
        }
    }

    // ─────────────────────────── results ────────────────────────────────

    public record ClaimedTask(
            Task task,
            UUID claimToken,
            OffsetDateTime leaseExpiresAt,
            int attempt,
            String prompt,
            String cwd,
            List<UUID> deps,
            UUID agentRunId) {
    }

    public record HeartbeatResult(boolean alive, UUID taskId, boolean cancelRequested,
                                   OffsetDateTime leaseExpiresAt) {
        public static HeartbeatResult expired() {
            return new HeartbeatResult(false, null, false, null);
        }
        public static HeartbeatResult alive(UUID taskId, boolean cancelRequested,
                                            OffsetDateTime expiresAt) {
            return new HeartbeatResult(true, taskId, cancelRequested, expiresAt);
        }
    }

    public record CompleteResult(boolean ok, UUID taskId) {
        public static CompleteResult ok(UUID taskId) { return new CompleteResult(true, taskId); }
        public static CompleteResult expired() { return new CompleteResult(false, null); }
    }

    public record FailResult(boolean ok, UUID taskId, TaskStatus newStatus) {
        public static FailResult ok(UUID taskId, TaskStatus newStatus) { return new FailResult(true, taskId, newStatus); }
        public static FailResult expired() { return new FailResult(false, null, null); }
    }

    public record ReleaseResult(boolean ok, UUID taskId) {
        public static ReleaseResult ok(UUID taskId) { return new ReleaseResult(true, taskId); }
        public static ReleaseResult expired() { return new ReleaseResult(false, null); }
    }
}
