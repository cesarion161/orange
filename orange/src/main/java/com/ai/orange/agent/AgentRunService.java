package com.ai.orange.agent;

import com.ai.orange.billing.BillingProperties;
import com.ai.orange.billing.CostEstimator;
import com.ai.orange.billing.PlanLimit;
import com.ai.orange.harness.claude.AuthFailure;
import com.ai.orange.gateway.GatewayClient;
import com.ai.orange.gateway.GatewayProperties;
import com.ai.orange.gateway.VirtualKey;
import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.HarnessRegistry;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunExit;
import com.ai.orange.harness.RunHandle;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.routing.AuthMode;
import com.ai.orange.routing.RouteDecision;
import com.ai.orange.routing.RouteRequest;
import com.ai.orange.routing.RoutingProperties;
import com.ai.orange.routing.RoutingTable;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs one agent: routes it to a model, effort and credential, starts the harness, streams its
 * events into {@code task_events}, enforces the run's timeout and budget, and records the outcome
 * in {@code agent_runs}. A gateway run's virtual key is always blocked afterwards, even when the
 * run never started.
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
    /** Length of each wait slice while a run is silent; run leases replace the v1 task heartbeat. */
    private static final Duration HEARTBEAT = Duration.ofSeconds(30);
    /** How long to wait for the process to exit after a kill. */
    private static final Duration KILL_GRACE = Duration.ofSeconds(30);

    private final RoutingTable routing;
    private final RoutingProperties routingProps;
    private final HarnessRegistry harnesses;
    private final GatewayClient gateway;
    private final GatewayProperties gatewayProps;
    private final BillingProperties billing;
    private final CostEstimator costs;
    private final AgentRunRepository agentRuns;
    private final TaskEventRepository taskEvents;
    private final TaskRepository tasks;
    /** The run currently working on each task, so a cancel can reach its process. */
    private final Map<UUID, RunHandle> active = new ConcurrentHashMap<>();

    public AgentRunService(RoutingTable routing, RoutingProperties routingProps, HarnessRegistry harnesses,
                           GatewayClient gateway, GatewayProperties gatewayProps, BillingProperties billing,
                           CostEstimator costs, AgentRunRepository agentRuns, TaskEventRepository taskEvents,
                           TaskRepository tasks) {
        this.routing = routing;
        this.routingProps = routingProps;
        this.harnesses = harnesses;
        this.gateway = gateway;
        this.gatewayProps = gatewayProps;
        this.billing = billing;
        this.costs = costs;
        this.agentRuns = agentRuns;
        this.taskEvents = taskEvents;
        this.tasks = tasks;
    }

    public AgentRunResult run(AgentRunRequest req) {
        String role = "dev".equals(req.role()) ? "implementer" : req.role();
        int failures = req.taskId() == null ? 0 : agentRuns.countFailed(req.taskId());
        RouteDecision route = routing.route(new RouteRequest(role, req.difficulty(), req.risk(), failures));
        UUID runId = UUID.randomUUID();
        String alias = route.auth() == AuthMode.GATEWAY ? "run-" + runId : null;

        UUID agentRunId = null;
        if (req.taskId() != null) {
            int attempt = agentRuns.nextAttempt(req.taskId());
            agentRunId = agentRuns.start(req.taskId(), req.agent().id(), attempt, req.prompt(),
                    metadata(route, runId, alias)).id();
        }
        log.info("run {} role={} task={} -> tier={} model={} effort={} auth={}", runId, role, req.taskId(),
                route.tier(), route.model(), route.effort().cli(), route.auth());

        VirtualKey key = null;
        try {
            Credentials credentials;
            if (route.auth() == AuthMode.SUBSCRIPTION) {
                String token = billing.subscriptionToken();
                if (token == null || token.isBlank()) {
                    return finish(req, route, runId, agentRunId, "subscription_token_missing", null, null, BigDecimal.ZERO);
                }
                credentials = new Credentials.Subscription(token);
            } else {
                if (!gateway.healthy()) {
                    return finish(req, route, runId, agentRunId, "gateway_unavailable", null, null, BigDecimal.ZERO);
                }
                try {
                    key = gateway.mint(alias, keyModels(route), route.budgetUsd(),
                            req.timeout().plus(gatewayProps.keyTtlMargin()), keyMetadata(req, role, runId));
                } catch (RuntimeException e) {
                    log.warn("run {}: could not mint a gateway key: {}", runId, e.getMessage());
                    return finish(req, route, runId, agentRunId, "gateway_unavailable", null, null, BigDecimal.ZERO);
                }
                credentials = new Credentials.Gateway(gatewayProps.baseUrl().toString(), key.key());
            }
            return execute(req, route, runId, agentRunId, credentials, key);
        } finally {
            if (key != null) {
                try {
                    gateway.block(key.key());
                } catch (RuntimeException e) {
                    log.warn("run {}: could not block gateway key {}: {}", runId, alias, e.getMessage());
                }
            }
        }
    }

    /**
     * Kills the run currently working on {@code taskId}, if there is one. The run then ends with
     * status and reason {@code cancelled}. Returns false when no run is active for the task.
     */
    public boolean cancel(UUID taskId) {
        RunHandle handle = active.get(taskId);
        if (handle == null) return false;
        log.info("cancelling the active run of task {}", taskId);
        handle.kill("cancelled");
        return true;
    }

    private AgentRunResult execute(AgentRunRequest req, RouteDecision route, UUID runId, UUID agentRunId,
                                   Credentials credentials, VirtualKey key) {
        RunSpec spec = new RunSpec(runId, req.prompt(), req.agent().systemPrompt(), route.model(),
                routingProps.smallModel(), route.effort(), req.cwd(), null, tools(req.agent().allowedTools()),
                tools(req.agent().disallowedTools()), req.agent().permissionMode(), null, credentials, req.timeout());

        RunWatch watch = new RunWatch(req, route);
        RunHandle handle;
        try {
            handle = harnesses.get(route.harness()).start(spec, watch);
        } catch (IOException | RuntimeException e) {
            log.error("run {}: harness {} failed to start: {}", runId, route.harness(), e.getMessage());
            return finish(req, route, runId, agentRunId, "start_failed", null, null, BigDecimal.ZERO);
        }
        watch.attach(handle);

        RunExit exit;
        if (req.taskId() != null) active.put(req.taskId(), handle);
        try {
            exit = await(handle, req);
        } finally {
            if (req.taskId() != null) active.remove(req.taskId(), handle);
        }
        RunEvent.Result result = exit.result();
        String reason = failureReason(exit, watch.lastAssistant);

        BigDecimal cost;
        if (key != null) {
            try {
                cost = gateway.info(key.key()).spend();
            } catch (RuntimeException e) {
                log.warn("run {}: gateway spend unavailable, using the estimate: {}", runId, e.getMessage());
                cost = watch.estimate();
            }
        } else if (result != null && result.costUsd() != null) {
            cost = result.costUsd();
        } else {
            cost = watch.estimate();
        }
        return finish(req, route, runId, agentRunId, reason, result, watch.lastAssistant, cost);
    }

    private RunExit await(RunHandle handle, AgentRunRequest req) {
        long deadline = System.nanoTime() + req.timeout().toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                handle.kill("timeout");
                return afterKill(handle, "timeout");
            }
            try {
                return handle.exit().get(Math.min(remaining, HEARTBEAT.toNanos()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // still running; wait another slice
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handle.kill("cancelled");
                return afterKill(handle, "cancelled");
            } catch (ExecutionException e) {
                handle.kill("cancelled");
                throw new IllegalStateException("run supervision failed", e.getCause());
            }
        }
    }

    private static RunExit afterKill(RunHandle handle, String reason) {
        try {
            return handle.exit().get(KILL_GRACE.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            return new RunExit(-1, reason, null);
        }
    }

    static String failureReason(RunExit exit, String lastAssistant) {
        RunEvent.Result r = exit.result();
        if (exit.killReason() != null) return exit.killReason();
        if (exit.exitCode() == 0 && r != null && !r.isError()) return null;
        if ((r != null && PlanLimit.matches(r.text())) || PlanLimit.matches(lastAssistant)) return "plan_limit";
        if (r != null && r.isError() && AuthFailure.matches(r.text())) return "auth_failed";
        if (r == null) return "no_result";
        if (r.isError()) return "error_result";
        return "exit_" + exit.exitCode();
    }

    private AgentRunResult finish(AgentRunRequest req, RouteDecision route, UUID runId, UUID agentRunId,
                                  String reason, RunEvent.Result result, String lastAssistant, BigDecimal cost) {
        String status = reason == null ? "success" : "cancelled".equals(reason) ? "cancelled" : "failure";
        if (agentRunId != null) {
            AgentRunStatus rowStatus = switch (status) {
                case "success" -> AgentRunStatus.SUCCEEDED;
                case "cancelled" -> AgentRunStatus.CANCELLED;
                default -> AgentRunStatus.FAILED;
            };
            long tokensIn = result == null ? 0 : result.inputTokens() + result.cacheReadTokens() + result.cacheWriteTokens();
            long tokensOut = result == null ? 0 : result.outputTokens();
            agentRuns.finish(agentRunId, rowStatus, cost, tokensIn, tokensOut);
            if (reason != null) {
                // Routing escalates on failed attempts; the reason lets it ignore failures that
                // were not the model's doing (plan limit, missing token, gateway down, cancel).
                agentRuns.mergeMetadata(agentRunId,
                        JSONB.valueOf(JSON.createObjectNode().put("failure_reason", reason).toString()));
            }
        }
        if (req.taskId() != null) {
            ObjectNode payload = JSON.createObjectNode()
                    .put("run_id", runId.toString())
                    .put("status", status)
                    .put("model", route.model())
                    .put("effort", route.effort().cli())
                    .put("cost_usd", cost);
            if (reason != null) payload.put("reason", reason);
            append(req.taskId(), req.actor(), "run_finished", payload);
        }
        log.info("run {} finished status={} reason={} cost={}", runId, status, reason, cost);
        return new AgentRunResult(status, reason, lastAssistant, result, cost, agentRunId, runId);
    }

    // ─────────────────────────── per-run event handling ───────────────────────────

    /** Receives the run's events: persists them, tracks the last message, and watches the budget. */
    private final class RunWatch implements Consumer<RunEvent> {

        private final AgentRunRequest req;
        private final RouteDecision route;
        private final boolean watchBudget;
        private final Map<String, RunEvent.Usage> usageByMessage = new ConcurrentHashMap<>();
        private final AtomicInteger anonymous = new AtomicInteger();
        private final AtomicReference<RunHandle> handle = new AtomicReference<>();
        private volatile boolean overBudget;
        volatile String lastAssistant;

        RunWatch(AgentRunRequest req, RouteDecision route) {
            this.req = req;
            this.route = route;
            // Gateway runs are capped by their virtual key; subscription runs only by this watch.
            this.watchBudget = route.auth() == AuthMode.SUBSCRIPTION && route.budgetUsd() != null;
        }

        @Override
        public void accept(RunEvent event) {
            if (req.taskId() != null) persist(req.taskId(), req.actor(), event);
            switch (event) {
                case RunEvent.AssistantText t -> lastAssistant = t.text();
                case RunEvent.Usage u -> {
                    String id = u.messageId() != null ? u.messageId() : "anon-" + anonymous.incrementAndGet();
                    usageByMessage.put(id, u);
                    checkBudget();
                }
                default -> { }
            }
        }

        void attach(RunHandle h) {
            handle.set(h);
            if (overBudget) h.kill("budget");
        }

        BigDecimal estimate() {
            BigDecimal total = BigDecimal.ZERO;
            for (RunEvent.Usage u : usageByMessage.values()) {
                total = total.add(costs.estimate(u.model() != null ? u.model() : route.model(), u.inputTokens(),
                        u.outputTokens(), u.cacheReadTokens(), u.cacheWriteTokens()));
            }
            return total;
        }

        private void checkBudget() {
            if (!watchBudget || overBudget) return;
            BigDecimal spent = estimate();
            if (spent.compareTo(route.budgetUsd()) > 0) {
                overBudget = true;
                log.warn("run over budget: ~${} > ${} on {}; killing it", spent, route.budgetUsd(), route.model());
                RunHandle h = handle.get();
                if (h != null) h.kill("budget");
            }
        }
    }

    private void persist(java.util.UUID taskId, String actor, RunEvent event) {
        ObjectNode payload = JSON.createObjectNode();
        String type;
        switch (event) {
            case RunEvent.Init i -> {
                type = "ready";
                payload.put("session_id", i.sessionId()).put("model", i.model());
            }
            case RunEvent.AssistantText t -> {
                type = "assistant_message";
                payload.put("text", t.text());
            }
            case RunEvent.ToolUse u -> {
                type = "tool_use";
                payload.put("id", u.id()).put("tool", u.name());
                payload.set("input", u.input());
            }
            case RunEvent.ToolResult r -> {
                type = "tool_result";
                payload.put("id", r.toolUseId()).put("is_error", r.isError()).put("excerpt", r.excerpt());
            }
            case RunEvent.Result r -> {
                type = "final";
                payload.put("is_error", r.isError()).put("subtype", r.subtype()).put("num_turns", r.numTurns());
                if (r.costUsd() != null) payload.put("cost_usd", r.costUsd());
            }
            case RunEvent.Stderr s -> {
                log.debug("run stderr (task {}): {}", taskId, s.line());
                return;
            }
            case RunEvent.Usage u -> {
                return;
            }
            case RunEvent.Unparsed u -> {
                log.debug("unparsed harness line (task {}): {}", taskId, u.line());
                return;
            }
        }
        append(taskId, actor, type, payload);
    }

    private void append(UUID taskId, String actor, String type, ObjectNode payload) {
        try {
            taskEvents.append(taskId, actor, type, JSONB.valueOf(payload.toString()));
        } catch (RuntimeException e) {
            log.warn("could not persist {} event for task {}: {}", type, taskId, e.getMessage());
        }
    }

    private static JSONB metadata(RouteDecision route, UUID runId, String alias) {
        ObjectNode m = JSON.createObjectNode()
                .put("run_id", runId.toString())
                .put("tier", route.tier())
                .put("model", route.model())
                .put("effort", route.effort().cli())
                .put("auth", route.auth().name().toLowerCase(java.util.Locale.ROOT))
                .put("harness", route.harness());
        if (alias != null) m.put("gateway_key_alias", alias);
        return JSONB.valueOf(m.toString());
    }

    private List<String> keyModels(RouteDecision route) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        models.add(route.model());
        if (routingProps.smallModel() != null) models.add(routingProps.smallModel());
        return new ArrayList<>(models);
    }

    private static Map<String, Object> keyMetadata(AgentRunRequest req, String role, UUID runId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("run_id", runId.toString());
        meta.put("role", role);
        if (req.taskId() != null) meta.put("task_id", req.taskId().toString());
        return meta;
    }

    private static List<String> tools(JSONB json) {
        if (json == null) return List.of();
        try {
            return JSON.readValue(json.data(), STR_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
