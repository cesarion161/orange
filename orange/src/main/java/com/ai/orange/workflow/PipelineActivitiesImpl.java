package com.ai.orange.workflow;

import com.ai.orange.agent.Agent;
import com.ai.orange.agent.AgentRepository;
import com.ai.orange.agent.AgentRun;
import com.ai.orange.agent.AgentRunRepository;
import com.ai.orange.agent.AgentRunStatus;
import com.ai.orange.agent.AgentRunnerLauncher;
import com.ai.orange.agent.AgentRunnerProcess;
import com.ai.orange.agent.protocol.Command;
import com.ai.orange.agent.protocol.Event;
import com.ai.orange.agent.protocol.ProtocolCodec;
import com.ai.orange.devenv.DevEnv;
import com.ai.orange.devenv.DevEnvService;
import com.ai.orange.devenv.EnvComposeRunner;
import com.ai.orange.github.GithubPrService;
import com.ai.orange.github.PrInfo;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.core.type.TypeReference;
import io.temporal.spring.boot.ActivityImpl;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = PipelineWorkflow.TASK_QUEUE)
public class PipelineActivitiesImpl implements PipelineActivities {

    private static final Logger log = LoggerFactory.getLogger(PipelineActivitiesImpl.class);
    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
    private static final Duration POLL = Duration.ofSeconds(2);

    private final TaskRepository tasks;
    private final TaskEventRepository taskEvents;
    private final AgentRepository agents;
    private final AgentRunRepository agentRuns;
    private final WorktreeService worktrees;
    private final AgentRunnerLauncher launcher;
    private final GithubPrService prService;
    private final DevEnvService devEnvs;
    private final EnvComposeRunner composeRunner;
    private final Duration envAcquireTimeout;

    public PipelineActivitiesImpl(TaskRepository tasks,
                                   TaskEventRepository taskEvents,
                                   AgentRepository agents,
                                   AgentRunRepository agentRuns,
                                   WorktreeService worktrees,
                                   AgentRunnerLauncher launcher,
                                   GithubPrService prService,
                                   DevEnvService devEnvs,
                                   EnvComposeRunner composeRunner,
                                   @Value("${orange.envs.acquire-timeout:30m}") Duration envAcquireTimeout) {
        this.tasks = tasks;
        this.taskEvents = taskEvents;
        this.agents = agents;
        this.agentRuns = agentRuns;
        this.worktrees = worktrees;
        this.launcher = launcher;
        this.prService = prService;
        this.devEnvs = devEnvs;
        this.composeRunner = composeRunner;
        this.envAcquireTimeout = envAcquireTimeout;
    }

    @Override
    public boolean claim(UUID taskId, String workerId) {
        return tasks.claimSpecific(taskId, workerId).isPresent();
    }

    @Override
    public String prepareWorktree(UUID taskId, String baseRef) {
        try {
            Path p = worktrees.create(taskId, baseRef);
            return p.toString();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("worktree create failed: " + e.getMessage(), e);
        }
    }

    @Override
    public EnvInfo acquireDevEnv(UUID taskId) {
        EnvInfo info;
        try {
            DevEnv env = devEnvs.acquire("task-" + taskId, envAcquireTimeout)
                    .orElseThrow(() -> new IllegalStateException(
                            "dev_env acquire timed out after " + envAcquireTimeout + " for task " + taskId));
            Map<String, Integer> ports = parsePorts(env);
            Map<String, Object> meta = parseMetadata(env);
            String dataDir = (String) meta.get("data_dir");
            String authBypassToken = (String) meta.get("auth_bypass_token");
            String fixtureSet = (String) meta.get("fixture_set");
            taskEvents.append(taskId, "orchestrator", "env_acquired", JSONB.valueOf(
                    "{\"env_id\":\"" + env.id() + "\",\"env_name\":" + jsonString(env.name()) + "}"));
            log.info("acquired env {} for task {}", env.name(), taskId);
            info = new EnvInfo(env.id(), env.name(), ports, dataDir, authBypassToken, fixtureSet);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("acquireDevEnv interrupted for task " + taskId, e);
        }

        // Bring up the env's compose stack BEFORE handing the lease to the
        // agent. If compose fails, release the lease and bubble — the stage
        // will fail with a clear cause instead of the tester staring at a
        // dead port.
        try {
            composeRunner.up(info);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("compose up failed for env {} task {}: {}", info.name(), taskId, e.getMessage());
            taskEvents.append(taskId, "orchestrator", "env_compose_failed", JSONB.valueOf(
                    "{\"env_name\":" + jsonString(info.name()) + ",\"error\":" + jsonString(e.getMessage()) + "}"));
            devEnvs.release(info.envId());
            throw new RuntimeException("compose up failed: " + e.getMessage(), e);
        }
        return info;
    }

    @Override
    public void releaseDevEnv(EnvInfo env) {
        if (env == null) return;
        // Tear down compose FIRST so containers don't outlive the lease.
        try {
            composeRunner.down(env);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("compose down for env {} threw: {}", env.name(), e.getMessage());
        }
        if (devEnvs.release(env.envId())) {
            log.info("released env {}", env.name());
        } else {
            log.warn("releaseDevEnv: env {} not in leased state (already freed?)", env.name());
        }
    }

    @Override
    public StageOutcome runStage(UUID taskId, String agentRole, String worktreePath,
                                  String augmentation, EnvInfo env) {
        Task task = tasks.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("task " + taskId + " not found"));
        Agent agent = agents.findEnabledByRole(agentRole).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no enabled agent for role " + agentRole));

        int attempt = agentRuns.nextAttempt(taskId);
        String prompt = buildPrompt(task, augmentation, env);
        AgentRun run = agentRuns.start(taskId, agent.id(), attempt, prompt, null);

        Command.StartOptions options = new Command.StartOptions(
                agent.model(),
                agent.fallbackModel(),
                agent.systemPrompt(),
                jsonbToStringList(agent.allowedTools()),
                jsonbToStringList(agent.disallowedTools()),
                worktreePath,
                agent.maxTurns(),
                null,
                agent.permissionMode(),
                null, null, null);
        Command.Start start = new Command.Start(prompt, options);

        AtomicReference<String> finalStatus = new AtomicReference<>("failure");
        AtomicReference<Map<String, String>> finalArtifacts = new AtomicReference<>(Map.of());
        AtomicReference<BigDecimal> totalCost = new AtomicReference<>(BigDecimal.ZERO);

        Consumer<Event> sink = event -> persistEvent(taskId, agent.name(), event,
                finalStatus, finalArtifacts, totalCost);

        try (AgentRunnerProcess proc = launcher.launch(start, sink)) {
            while (!proc.awaitTermination(POLL)) {
                tasks.updateHeartbeat(taskId, OffsetDateTime.now());
            }
        } catch (IOException e) {
            log.error("runStage IO error for task {}", taskId, e);
            agentRuns.finish(run.id(), AgentRunStatus.FAILED, totalCost.get(), 0, 0);
            return new StageOutcome("failure", null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            agentRuns.finish(run.id(), AgentRunStatus.CANCELLED, totalCost.get(), 0, 0);
            return new StageOutcome("cancelled", null, null);
        }

        AgentRunStatus runStatus = switch (finalStatus.get()) {
            case "success" -> AgentRunStatus.SUCCEEDED;
            case "cancelled" -> AgentRunStatus.CANCELLED;
            default -> AgentRunStatus.FAILED;
        };
        agentRuns.finish(run.id(), runStatus, totalCost.get(), 0, 0);

        String verdict = null;
        String report = null;
        if ("tester".equals(agentRole)) {
            String reportName = finalArtifacts.get().getOrDefault("report", "report.md");
            Path reportPath = Path.of(worktreePath).resolve(reportName);
            if (Files.exists(reportPath)) {
                try {
                    report = Files.readString(reportPath);
                    verdict = parseVerdict(report);
                } catch (IOException e) {
                    log.warn("could not read tester report at {}: {}", reportPath, e.getMessage());
                }
            } else {
                log.warn("tester for task {} produced no report at {}", taskId, reportPath);
            }
        }

        return new StageOutcome(finalStatus.get(), verdict, report);
    }

    @Override
    public String openPr(UUID taskId, String worktreePath) {
        try {
            PrInfo info = prService.openPrForTask(taskId, Path.of(worktreePath));
            log.info("opened PR #{} for task {} at {}", info.number(), taskId, info.htmlUrl());
            return info.htmlUrl();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("openPr failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void markPrOpen(UUID taskId) {
        boolean ok = tasks.transitionStatus(taskId, TaskStatus.IN_PROGRESS, TaskStatus.PR_OPEN);
        if (!ok) log.warn("markPrOpen no-op (task {} not in_progress)", taskId);
    }

    @Override
    public void markDevReady(UUID taskId) {
        boolean ok = tasks.transitionStatus(taskId, TaskStatus.PR_OPEN, TaskStatus.DEV_READY);
        if (!ok) log.warn("markDevReady no-op (task {} not pr_open)", taskId);
    }

    @Override
    public void markFailed(UUID taskId, String reason) {
        Task t = tasks.findById(taskId).orElse(null);
        if (t == null) return;
        if (t.status().isTerminal()) return;
        try {
            tasks.transitionStatus(taskId, t.status(), TaskStatus.FAILED);
        } catch (Exception e) {
            log.warn("markFailed for task {} from {}: {}", taskId, t.status(), e.getMessage());
        }
        taskEvents.append(taskId, "orchestrator", "task_failed",
                JSONB.valueOf("{\"reason\":" + jsonString(reason) + "}"));
    }

    @Override
    public void cleanupWorktree(UUID taskId) {
        try {
            worktrees.remove(taskId);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("cleanupWorktree for {} failed: {}", taskId, e.getMessage());
        }
    }

    // ─────────────────────────── helpers ────────────────────────────────

    private static String buildPrompt(Task task, String augmentation, EnvInfo env) {
        StringBuilder sb = new StringBuilder(task.title());
        if (task.description() != null && !task.description().isBlank()) {
            sb.append("\n\n").append(task.description());
        }
        if (env != null) {
            sb.append("\n\n--- Leased dev environment: ").append(env.name()).append(" ---\n");
            sb.append("Use the ports + URLs below to hit the running app. Do not allocate your own ports; do not run `docker compose up` for a different env.\n");
            if (env.ports() != null) {
                env.ports().forEach((key, port) ->
                        sb.append("  ").append(key).append(": http://localhost:").append(port).append('\n'));
            }
            if (env.dataDir() != null) {
                sb.append("  data_dir: ").append(env.dataDir()).append('\n');
            }
            if (env.fixtureSet() != null) {
                sb.append("  fixture_set: ").append(env.fixtureSet()).append('\n');
            }
            if (env.authBypassToken() != null) {
                sb.append("  auth_bypass_token: ").append(env.authBypassToken()).append('\n');
                sb.append("If you need an authenticated session, prefer the token above (the running ")
                  .append("app honors it only when bound to this leased env) or run `./login.sh` ")
                  .append("from the worktree root if it exists. Do not screen-scrape a login form.\n");
            }
        }
        if (augmentation != null && !augmentation.isBlank()) {
            sb.append("\n\n--- Previous attempt's QA report ---\n").append(augmentation);
        }
        return sb.toString();
    }

    private static Map<String, Integer> parsePorts(DevEnv env) {
        if (env.ports() == null) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = ProtocolCodec.mapper().readValue(env.ports().data(), Map.class);
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() instanceof Number n) out.put(e.getKey(), n.intValue());
            }
            return out;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static Map<String, Object> parseMetadata(DevEnv env) {
        if (env.metadata() == null) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = ProtocolCodec.mapper().readValue(env.metadata().data(), Map.class);
            return out == null ? Map.of() : out;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private void persistEvent(UUID taskId, String actor, Event event,
                              AtomicReference<String> finalStatus,
                              AtomicReference<Map<String, String>> finalArtifacts,
                              AtomicReference<BigDecimal> totalCost) {
        try {
            String json = ProtocolCodec.mapper().writeValueAsString(event);
            taskEvents.append(taskId, actor, eventType(event), JSONB.valueOf(json));
        } catch (Exception e) {
            log.warn("could not persist event for task {}: {}", taskId, e.getMessage());
        }
        tasks.updateHeartbeat(taskId, OffsetDateTime.now());
        if (event instanceof Event.Final f) {
            finalStatus.set(f.status());
            if (f.artifacts() != null) finalArtifacts.set(f.artifacts());
        } else if (event instanceof Event.Cost c) {
            totalCost.updateAndGet(prev -> prev.add(BigDecimal.valueOf(c.usd())));
        }
    }

    static String parseVerdict(String reportMarkdown) {
        if (reportMarkdown == null) return null;
        String firstLine = reportMarkdown.lines().findFirst().orElse("").toUpperCase();
        if (firstLine.contains("VERDICT") && firstLine.contains("PASS")) return "pass";
        if (firstLine.contains("VERDICT") && firstLine.contains("FAIL")) return "fail";
        return null;
    }

    private static String eventType(Event e) {
        return switch (e) {
            case Event.Ready r -> "ready";
            case Event.AssistantMessage a -> "assistant_message";
            case Event.Thinking t -> "thinking";
            case Event.ToolUse u -> "tool_use";
            case Event.ToolResult r -> "tool_result";
            case Event.HookRequest h -> "hook_request";
            case Event.SubagentStart s -> "subagent_start";
            case Event.SubagentEnd s -> "subagent_end";
            case Event.Cost c -> "cost";
            case Event.Final f -> "final";
            case Event.ErrorEvent err -> "error";
        };
    }

    private static List<String> jsonbToStringList(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            return ProtocolCodec.mapper().readValue(jsonb.data(), STR_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        try {
            return ProtocolCodec.mapper().writeValueAsString(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
