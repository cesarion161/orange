package com.ai.orange.run;

import com.ai.orange.billing.BillingProperties;
import com.ai.orange.billing.CostEstimator;
import com.ai.orange.billing.PlanLimit;
import com.ai.orange.gateway.GatewayClient;
import com.ai.orange.gateway.GatewayProperties;
import com.ai.orange.gateway.VirtualKey;
import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.HarnessRegistry;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunExit;
import com.ai.orange.harness.RunHandle;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.harness.claude.AuthFailure;
import com.ai.orange.json.Json;
import com.ai.orange.mcp.RunTokens;
import com.ai.orange.mcp.WorkerMcpConfigWriter;
import com.ai.orange.roles.RolePrompts;
import com.ai.orange.roles.ToolPolicy;
import com.ai.orange.routing.AuthMode;
import com.ai.orange.routing.RouteDecision;
import com.ai.orange.routing.RouteRequest;
import com.ai.orange.routing.RoutingProperties;
import com.ai.orange.routing.RoutingTable;
import com.ai.orange.worktree.WorktreeCapacityException;
import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * Runs one coding agent for a {@code runs} row: prepares its workspace and prompt, routes it to a
 * model, effort and credential, gives it a worker MCP token, starts the harness, streams its
 * events into {@code run_events}, keeps its lease alive, enforces its timeout and budget, applies
 * the post-run steps and finishes the row. A gateway run's virtual key is always blocked
 * afterwards, and the run's MCP config file is always deleted.
 */
@Service
public class WorkerRunner implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);
    private static final Set<RunKind> KINDS = EnumSet.of(RunKind.PLAN, RunKind.IMPLEMENT, RunKind.CI_FIX,
            RunKind.DIFF_REVIEW, RunKind.JUDGE);
    /** Kinds whose agent must report through the worker MCP endpoint; a silent clean exit is a failure. */
    private static final Set<RunKind> NEEDS_OUTCOME = EnumSet.of(RunKind.IMPLEMENT, RunKind.CI_FIX,
            RunKind.DIFF_REVIEW, RunKind.JUDGE);
    /** How long to wait for the process to exit after a kill. */
    private static final Duration KILL_GRACE = Duration.ofSeconds(30);
    /** How long a shutdown waits for killed runs to be recorded. */
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(20);
    /** Stderr and unparsed lines are only logged, and only this much of each. */
    private static final int LOG_LINE_MAX = 200;
    private static final String DEFAULT_DIFFICULTY = "M";

    private final RoutingTable routing;
    private final RoutingProperties routingProps;
    private final HarnessRegistry harnesses;
    private final HarnessProperties harnessProps;
    private final GatewayClient gateway;
    private final GatewayProperties gatewayProps;
    private final BillingProperties billing;
    private final CostEstimator costs;
    private final RunsProperties props;
    private final RunRepository runs;
    private final RunEventRepository runEvents;
    private final RunLifecycle lifecycle;
    private final RunPreparer preparer;
    private final RolePrompts rolePrompts;
    private final WorkerMcpConfigWriter mcpConfigs;
    private final RunSecrets secrets;

    /** The runs this process is supervising, by run id, so a cancel can reach their processes. */
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();
    /** Runs between their start here and their row being finished; a shutdown waits for these. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    private record Active(UUID taskId, RunHandle handle) {}

    public WorkerRunner(RoutingTable routing, RoutingProperties routingProps, HarnessRegistry harnesses,
                        HarnessProperties harnessProps, GatewayClient gateway, GatewayProperties gatewayProps,
                        BillingProperties billing, CostEstimator costs, RunsProperties props, RunRepository runs,
                        RunEventRepository runEvents, RunLifecycle lifecycle, RunPreparer preparer,
                        RolePrompts rolePrompts, WorkerMcpConfigWriter mcpConfigs, RunSecrets secrets) {
        this.routing = routing;
        this.routingProps = routingProps;
        this.harnesses = harnesses;
        this.harnessProps = harnessProps;
        this.gateway = gateway;
        this.gatewayProps = gatewayProps;
        this.billing = billing;
        this.costs = costs;
        this.props = props;
        this.runs = runs;
        this.runEvents = runEvents;
        this.lifecycle = lifecycle;
        this.preparer = preparer;
        this.rolePrompts = rolePrompts;
        this.mcpConfigs = mcpConfigs;
        this.secrets = secrets;
    }

    @Override
    public Set<RunKind> kinds() {
        return KINDS;
    }

    /** Executes a run this worker has claimed (status {@code running}, leased to {@link RunsProperties#workerId()}). */
    @Override
    public RunResult execute(Run run) {
        if (!KINDS.contains(run.kind())) {
            throw new IllegalArgumentException("WorkerRunner does not run " + run.kind().dbValue() + " runs");
        }
        if (!runs.heartbeat(run.id(), props.workerId(), props.leaseTtl())) {
            log.warn("run {} is no longer this worker's to run (finished or reaped); skipping it", run.id());
            return lifecycle.fromRow(run.id());
        }
        inFlight.add(run.id());
        try {
            if (stopping) return conclude(run, RunResult.failed(run.id(), "coordinator_restart"), null);
            if (lifecycle.taskEnded(run)) return lifecycle.finishCancelled(run);
            PreparedRun prep;
            try {
                prep = preparer.prepare(run);
            } catch (WorktreeCapacityException e) {
                log.info("run {}: {}; the task waits for a worktree slot", run.id(), e.getMessage());
                return conclude(run, RunResult.failed(run.id(), "no_worktree_slot"), null);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.error("run {}: could not prepare the workspace: {}", run.id(), e.toString());
                return conclude(run, RunResult.failed(run.id(), "workspace_failed"), null);
            }
            try {
                return run(run, prep, props.timeout(run.kind()));
            } finally {
                try {
                    prep.cleanup().run();
                } catch (RuntimeException e) {
                    log.warn("run {}: cleanup failed: {}", run.id(), e.toString());
                }
            }
        } finally {
            inFlight.remove(run.id());
        }
    }

    /**
     * Runs an agent outside any task (the planner): inserts a running row with no task and executes
     * it in {@code cwd}. Not subject to slots; the caller waits for the result.
     */
    public RunResult runDirect(RunKind kind, String role, Path cwd, String prompt, Duration timeout) {
        Run run = runs.insertRunning(kind, role, null, props.workerId(), props.leaseTtl(), JSONB.valueOf("{}"));
        inFlight.add(run.id());
        try {
            if (stopping) return conclude(run, RunResult.failed(run.id(), "coordinator_restart"), null);
            return run(run, new PreparedRun(cwd, prompt, role, null, false, 0, null), timeout);
        } finally {
            inFlight.remove(run.id());
        }
    }

    /**
     * Kills the run(s) this process is supervising for {@code taskId}. They end with status and
     * reason {@code cancelled}. Returns false when none is active here.
     */
    public boolean cancel(UUID taskId) {
        boolean any = false;
        for (Map.Entry<UUID, Active> e : active.entrySet()) {
            if (taskId.equals(e.getValue().taskId())) {
                log.info("cancelling run {} of task {}", e.getKey(), taskId);
                e.getValue().handle().kill("cancelled");
                any = true;
            }
        }
        return any;
    }

    /** The runs whose processes this coordinator is supervising right now. */
    public Set<UUID> activeRunIds() {
        return Set.copyOf(active.keySet());
    }

    /**
     * Stopping orange stops its agents: every running one is killed (process tree included) and its
     * run ends {@code coordinator_restart}, which does not count against its task; new runs are
     * refused. Waits up to {@link #SHUTDOWN_GRACE} for the runs to be recorded, so the database is
     * still there when they are. A run it could not record is failed by the reaper on the next start.
     */
    @PreDestroy
    public void shutdown() {
        stopping = true;
        if (inFlight.isEmpty()) return;
        log.warn("stopping: killing {} running agent(s)", active.size());
        active.values().forEach(a -> a.handle().kill("coordinator_restart"));
        long deadline = System.nanoTime() + SHUTDOWN_GRACE.toNanos();
        while (!inFlight.isEmpty() && System.nanoTime() < deadline) {
            active.values().forEach(a -> a.handle().kill("coordinator_restart"));
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!inFlight.isEmpty()) log.warn("stopping: {} run(s) not recorded; the next start fails them", inFlight.size());
    }

    // ─────────────────────────── one run ───────────────────────────

    private RunResult run(Run run, PreparedRun prep, Duration timeout) {
        RouteDecision route;
        String systemPrompt;
        try {
            String difficulty = prep.difficulty() == null ? DEFAULT_DIFFICULTY : prep.difficulty();
            route = routing.route(new RouteRequest(prep.role(), difficulty, prep.risk(), prep.failures()));
            systemPrompt = rolePrompts.systemPrompt(prep.role());
        } catch (RuntimeException e) {
            log.error("run {}: cannot route or prompt role {}: {}", run.id(), prep.role(), e.getMessage());
            return conclude(run, RunResult.failed(run.id(), "start_failed"), null);
        }
        ToolPolicy policy = ToolPolicy.forRun(run.kind(), run.attendedSessionId() != null);
        String alias = route.auth() == AuthMode.GATEWAY ? "run-" + run.id() : null;
        String mcpToken = policy.mcp() ? RunTokens.newToken() : null;
        Path runDir = harnessProps.runRoot().resolve(run.id().toString());
        runs.recordRoute(run.id(), route.tier(), route.model(), route.effort().cli(), route.harness(),
                authName(route), route.budgetUsd(), alias, mcpToken == null ? null : RunTokens.hash(mcpToken),
                runDir.toString());
        lifecycle.started(run, Json.mapper().createObjectNode()
                .put("tier", route.tier())
                .put("model", route.model())
                .put("effort", route.effort().cli()));
        log.info("run {} {} role={} task={} -> tier={} model={} effort={} auth={}", run.id(), run.kind().dbValue(),
                prep.role(), run.taskId(), route.tier(), route.model(), route.effort().cli(), route.auth());

        VirtualKey key = null;
        Path mcpConfig = null;
        try {
            Credentials credentials;
            if (route.auth() == AuthMode.SUBSCRIPTION) {
                String token = billing.subscriptionToken();
                if (token == null || token.isBlank()) {
                    return conclude(run, RunResult.failed(run.id(), "subscription_token_missing"), null);
                }
                credentials = new Credentials.Subscription(token);
            } else {
                if (!gateway.healthy()) {
                    return conclude(run, RunResult.failed(run.id(), "gateway_unavailable"), null);
                }
                try {
                    key = gateway.mint(alias, keyModels(route), route.budgetUsd(),
                            timeout.plus(gatewayProps.keyTtlMargin()), keyMetadata(run, prep.role()));
                } catch (RuntimeException e) {
                    log.warn("run {}: could not mint a gateway key: {}", run.id(), e.getMessage());
                    return conclude(run, RunResult.failed(run.id(), "gateway_unavailable"), null);
                }
                credentials = new Credentials.Gateway(gatewayProps.baseUrl().toString(), key.key());
            }
            secrets.register(run.id(), secretsOf(credentials, mcpToken));

            if (mcpToken != null) {
                try {
                    mcpConfig = mcpConfigs.write(runDir, mcpToken);
                } catch (IOException e) {
                    log.error("run {}: could not write its MCP config: {}", run.id(), e.getMessage());
                    return conclude(run, RunResult.failed(run.id(), "start_failed"), null);
                }
            }
            RunSpec spec = new RunSpec(run.id(), prep.prompt(), systemPrompt, route.model(), routingProps.smallModel(),
                    route.effort(), prep.cwd(), policy.tools(), policy.allowedTools(), List.of(),
                    policy.permissionMode(), mcpConfig, credentials, timeout,
                    mcpToken == null ? List.of() : List.of(mcpToken));
            return supervise(run, route, spec, key, timeout);
        } finally {
            secrets.forget(run.id());
            if (key != null) {
                try {
                    gateway.block(key.key());
                } catch (RuntimeException e) {
                    log.warn("run {}: could not block gateway key {}: {}", run.id(), alias, e.getMessage());
                }
            }
            if (mcpConfig != null) {
                try {
                    Files.deleteIfExists(mcpConfig);
                } catch (IOException e) {
                    log.warn("run {}: could not delete {}: {}", run.id(), mcpConfig, e.getMessage());
                }
            }
        }
    }

    private RunResult supervise(Run run, RouteDecision route, RunSpec spec, VirtualKey key, Duration timeout) {
        // Last look before an agent starts: a cancel during preparation finds no process to kill.
        if (stopping) return conclude(run, RunResult.failed(run.id(), "coordinator_restart"), null);
        if (lifecycle.taskEnded(run)) return lifecycle.finishCancelled(run);
        RunWatch watch = new RunWatch(run, route);
        RunHandle handle;
        try {
            handle = harnesses.get(route.harness()).start(spec, watch);
        } catch (IOException | RuntimeException e) {
            log.error("run {}: harness {} failed to start: {}", run.id(), route.harness(), e.getMessage());
            return conclude(run, RunResult.failed(run.id(), "start_failed"), null);
        }
        watch.attach(handle);
        active.put(run.id(), new Active(run.taskId(), handle));
        RunExit exit;
        try {
            runs.recordPid(run.id(), handle.pid());
            exit = await(run, handle, timeout);
        } finally {
            active.remove(run.id());
        }

        Run row = runs.findById(run.id()).orElse(null);
        if (row == null || row.status() != RunStatus.RUNNING) {
            log.warn("run {} was finished elsewhere ({}) while its process ran; leaving it as it is", run.id(),
                    row == null ? "deleted" : row.status().dbValue() + "/" + row.error());
            return lifecycle.fromRow(run.id());
        }

        RunEvent.Result result = exit.result();
        String reason = failureReason(exit, watch.lastAssistant);
        RunOutcome outcome = RunOutcome.parse(row.outcome()).orElse(null);
        if (reason == null) {
            if (outcome == null && NEEDS_OUTCOME.contains(run.kind())) {
                reason = "no_outcome";
            } else if (outcome instanceof RunOutcome.Result r && "failed".equals(r.status())) {
                reason = "reported_failed";
            }
        }
        RunResult runResult = new RunResult(run.id(), statusFor(reason), reason, outcome, watch.lastAssistant,
                result == null ? null : result.text(), cost(run, key, result, watch));
        return conclude(run, runResult, watch.usage(result));
    }

    /** Waits for the run to end, renewing its lease every heartbeat and killing it at its timeout. */
    private RunExit await(Run run, RunHandle handle, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextBeat = System.nanoTime() + props.heartbeat().toNanos();
        while (true) {
            long now = System.nanoTime();
            if (now >= deadline) {
                handle.kill("timeout");
                return afterKill(handle, "timeout");
            }
            if (now >= nextBeat) {
                nextBeat = now + props.heartbeat().toNanos();
                Boolean renewed = lifecycle.renewLease(run);
                if (Boolean.FALSE.equals(renewed)) {
                    log.warn("run {} lost its lease; killing it", run.id());
                    handle.kill("lease_lost");
                    return afterKill(handle, "lease_lost");
                }
            }
            long wait = Math.max(Math.min(deadline, nextBeat) - now, TimeUnit.MILLISECONDS.toNanos(1));
            try {
                return handle.exit().get(wait, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // still running
            } catch (InterruptedException e) {
                // Only a shutting-down coordinator interrupts its run threads.
                Thread.currentThread().interrupt();
                handle.kill("coordinator_restart");
                return afterKill(handle, "coordinator_restart");
            } catch (ExecutionException e) {
                log.error("run {}: supervision failed: {}", run.id(), e.getCause() == null ? e : e.getCause());
                handle.kill("coordinator_restart");
                return afterKill(handle, "coordinator_restart");
            }
        }
    }

    private static RunExit afterKill(RunHandle handle, String reason) {
        try {
            return handle.exit().get(KILL_GRACE.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RunExit(-1, reason, null);
        } catch (Exception e) {
            return new RunExit(-1, reason, null);
        }
    }

    /** PR 1's classification of how the harness ended; the MCP outcome is judged by the caller. */
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

    private static RunStatus statusFor(String reason) {
        if (reason == null) return RunStatus.SUCCEEDED;
        return "cancelled".equals(reason) ? RunStatus.CANCELLED : RunStatus.FAILED;
    }

    private BigDecimal cost(Run run, VirtualKey key, RunEvent.Result result, RunWatch watch) {
        if (key != null) {
            try {
                return gateway.info(key.key()).spend();
            } catch (RuntimeException e) {
                log.warn("run {}: gateway spend unavailable, using the estimate: {}", run.id(), e.getMessage());
                return watch.estimate();
            }
        }
        if (result != null && result.costUsd() != null) return result.costUsd();
        return watch.estimate();
    }

    /** Post-run steps, then the row is finished: the run stays {@code running} until its effects have landed. */
    private RunResult conclude(Run run, RunResult result, Usage usage) {
        Usage u = usage == null ? Usage.NONE : usage;
        return lifecycle.finish(run, result, u.tokensIn(), u.tokensOut(), u.cacheRead(), u.sessionId());
    }

    // ─────────────────────────── per-run event handling ───────────────────────────

    /** Tokens for the run row: input not read from cache (fresh and cache-write), output, cache reads. */
    private record Usage(long tokensIn, long tokensOut, long cacheRead, String sessionId) {
        static final Usage NONE = new Usage(0, 0, 0, null);
    }

    /** Receives the run's events: persists them, tracks the last message and usage, and watches the budget. */
    private final class RunWatch implements Consumer<RunEvent> {

        private final Run run;
        private final RouteDecision route;
        private final boolean watchBudget;
        private final AtomicInteger seq = new AtomicInteger();
        private final Map<String, RunEvent.Usage> usageByMessage = new ConcurrentHashMap<>();
        private final AtomicInteger anonymous = new AtomicInteger();
        private final AtomicReference<RunHandle> handle = new AtomicReference<>();
        private volatile boolean overBudget;
        private volatile String sessionId;
        volatile String lastAssistant;

        RunWatch(Run run, RouteDecision route) {
            this.run = run;
            this.route = route;
            // Gateway runs are capped by their virtual key; subscription runs only by this watch.
            this.watchBudget = route.auth() == AuthMode.SUBSCRIPTION && route.budgetUsd() != null;
        }

        @Override
        public void accept(RunEvent event) {
            switch (event) {
                case RunEvent.Init i -> {
                    sessionId = i.sessionId();
                    persist("init", Json.mapper().createObjectNode().put("session_id", i.sessionId())
                            .put("model", i.model()));
                }
                case RunEvent.AssistantText t -> {
                    lastAssistant = t.text();
                    persist("assistant", Json.mapper().createObjectNode().put("text", t.text()));
                }
                case RunEvent.ToolUse u -> {
                    ObjectNode p = Json.mapper().createObjectNode().put("id", u.id()).put("tool", u.name());
                    p.set("input", u.input());
                    persist("tool_use", p);
                }
                case RunEvent.ToolResult r -> persist("tool_result", Json.mapper().createObjectNode()
                        .put("id", r.toolUseId()).put("is_error", r.isError()).put("excerpt", r.excerpt()));
                case RunEvent.Result r -> {
                    ObjectNode p = Json.mapper().createObjectNode().put("is_error", r.isError())
                            .put("subtype", r.subtype()).put("num_turns", r.numTurns());
                    if (r.costUsd() != null) p.put("cost_usd", r.costUsd());
                    persist("result", p);
                }
                case RunEvent.ApiRetry a -> {
                    log.info("run {}: API retry {}/{} (status {}, {})", run.id(), a.attempt(), a.maxRetries(),
                            a.errorStatus(), a.error());
                    ObjectNode p = Json.mapper().createObjectNode().put("attempt", a.attempt())
                            .put("max_retries", a.maxRetries()).put("retry_delay_ms", a.retryDelayMs())
                            .put("error", a.error());
                    if (a.errorStatus() == null) p.putNull("error_status");
                    else p.put("error_status", a.errorStatus());
                    persist("api_retry", p);
                }
                case RunEvent.Usage u -> {
                    String id = u.messageId() != null ? u.messageId() : "anon-" + anonymous.incrementAndGet();
                    usageByMessage.put(id, u);
                    checkBudget();
                }
                case RunEvent.Stderr s -> log.debug("run {} stderr: {}", run.id(), trim(s.line()));
                case RunEvent.Unparsed u -> log.debug("run {} unparsed line: {}", run.id(), trim(u.line()));
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

        /** The harness's own totals when it reported them, else what the run's messages added up to. */
        Usage usage(RunEvent.Result r) {
            if (r != null) {
                return new Usage(r.inputTokens() + r.cacheWriteTokens(), r.outputTokens(), r.cacheReadTokens(),
                        r.sessionId() != null ? r.sessionId() : sessionId);
            }
            long in = 0, out = 0, cacheRead = 0;
            for (RunEvent.Usage u : usageByMessage.values()) {
                in += u.inputTokens() + u.cacheWriteTokens();
                out += u.outputTokens();
                cacheRead += u.cacheReadTokens();
            }
            return new Usage(in, out, cacheRead, sessionId);
        }

        private void persist(String type, ObjectNode payload) {
            try {
                runEvents.append(run.id(), seq.incrementAndGet(), type, JSONB.valueOf(payload.toString()));
            } catch (RuntimeException e) {
                log.warn("run {}: could not persist a {} event: {}", run.id(), type, e.getMessage());
            }
        }

        private void checkBudget() {
            if (!watchBudget || overBudget) return;
            BigDecimal spent = estimate();
            if (spent.compareTo(route.budgetUsd()) > 0) {
                overBudget = true;
                log.warn("run {} over budget: ~${} > ${} on {}; killing it", run.id(), spent, route.budgetUsd(),
                        route.model());
                RunHandle h = handle.get();
                if (h != null) h.kill("budget");
            }
        }
    }

    private static String trim(String line) {
        if (line == null) return "";
        return line.length() <= LOG_LINE_MAX ? line : line.substring(0, LOG_LINE_MAX) + "…";
    }

    private static String authName(RouteDecision route) {
        return route.auth().name().toLowerCase(Locale.ROOT);
    }

    private static List<String> secretsOf(Credentials credentials, String mcpToken) {
        List<String> all = new ArrayList<>();
        switch (credentials) {
            case Credentials.Subscription s -> all.add(s.oauthToken());
            case Credentials.Gateway g -> all.add(g.virtualKey());
        }
        if (mcpToken != null) all.add(mcpToken);
        return all;
    }

    private List<String> keyModels(RouteDecision route) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        models.add(route.model());
        if (routingProps.smallModel() != null) models.add(routingProps.smallModel());
        return new ArrayList<>(models);
    }

    private static Map<String, Object> keyMetadata(Run run, String role) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("run_id", run.id().toString());
        meta.put("kind", run.kind().dbValue());
        meta.put("role", role);
        if (run.taskId() != null) meta.put("task_id", run.taskId().toString());
        return meta;
    }
}
