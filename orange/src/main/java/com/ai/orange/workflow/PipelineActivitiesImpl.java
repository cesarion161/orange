package com.ai.orange.workflow;

import com.ai.orange.agent.Agent;
import com.ai.orange.agent.AgentRepository;
import com.ai.orange.agent.AgentRunRequest;
import com.ai.orange.agent.AgentRunResult;
import com.ai.orange.agent.AgentRunService;
import com.ai.orange.devenv.DevEnv;
import com.ai.orange.devenv.DevEnvService;
import com.ai.orange.devenv.EnvComposeRunner;
import com.ai.orange.github.GithubPrService;
import com.ai.orange.github.PrInfo;
import com.ai.orange.json.Json;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.databind.JsonNode;
import io.temporal.spring.boot.ActivityImpl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = PipelineWorkflow.TASK_QUEUE)
public class PipelineActivitiesImpl implements PipelineActivities {

    private static final Logger log = LoggerFactory.getLogger(PipelineActivitiesImpl.class);
    /** Per-stage agent run timeout; the runStage activity's own start-to-close timeout is 2 h. */
    static final Duration STAGE_TIMEOUT = Duration.ofMinutes(60);

    private final TaskRepository tasks;
    private final TaskEventRepository taskEvents;
    private final AgentRepository agents;
    private final AgentRunService agentRunService;
    private final WorktreeService worktrees;
    private final GithubPrService prService;
    private final DevEnvService devEnvs;
    private final EnvComposeRunner composeRunner;
    private final Duration envAcquireTimeout;

    public PipelineActivitiesImpl(TaskRepository tasks,
                                   TaskEventRepository taskEvents,
                                   AgentRepository agents,
                                   AgentRunService agentRunService,
                                   WorktreeService worktrees,
                                   GithubPrService prService,
                                   DevEnvService devEnvs,
                                   EnvComposeRunner composeRunner,
                                   @Value("${orange.envs.acquire-timeout:30m}") Duration envAcquireTimeout) {
        this.tasks = tasks;
        this.taskEvents = taskEvents;
        this.agents = agents;
        this.agentRunService = agentRunService;
        this.worktrees = worktrees;
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
    public String prepareWorktree(UUID taskId) {
        try {
            Path p = worktrees.create(taskId);
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
            taskEvents.append(taskId, "orchestrator", "env_acquired", JSONB.valueOf(
                    "{\"env_id\":\"" + env.id() + "\",\"env_name\":" + jsonString(env.name()) + "}"));
            log.info("acquired env {} for task {}", env.name(), taskId);
            info = EnvInfo.from(env);
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

        String prompt = buildPrompt(task, augmentation, env);
        AgentRunResult run = agentRunService.run(new AgentRunRequest(taskId, agentRole, agent, prompt,
                Path.of(worktreePath), difficultyOf(task), riskOf(task), STAGE_TIMEOUT, agent.name()));

        String verdict = null;
        String report = null;
        if ("tester".equals(agentRole)) {
            Path reportPath = Path.of(worktreePath).resolve("report.md");
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
        } else if ("reviewer".equals(agentRole)) {
            // The reviewer is read-only (no report file); its verdict is the
            // VERDICT: line in its final summary. "fail" makes succeeded() false,
            // so the review stage's retryPrevious bounces back to dev with the
            // review attached as augmentation. No verdict line → null → the stage
            // passes (advisory), so a malformed review never wedges the pipeline.
            report = run.lastAssistantText();
            verdict = parseReviewVerdict(report);
            if (verdict == null) {
                log.warn("reviewer for task {} emitted no VERDICT line; treating review as non-blocking", taskId);
            }
        }

        return new StageOutcome(run.status(), verdict, report);
    }

    /** Planner-assigned difficulty from {@code tasks.metadata.difficulty}; M when absent. */
    static String difficultyOf(Task task) {
        JsonNode meta = metadataOf(task);
        String d = meta.path("difficulty").asText("");
        return d.isBlank() ? "M" : d;
    }

    /** Risk flag from {@code tasks.metadata.risk}; false when absent. */
    static boolean riskOf(Task task) {
        return metadataOf(task).path("risk").asBoolean(false);
    }

    private static JsonNode metadataOf(Task task) {
        if (task.metadata() == null) return Json.mapper().createObjectNode();
        try {
            return Json.mapper().readTree(task.metadata().data());
        } catch (Exception e) {
            return Json.mapper().createObjectNode();
        }
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
            // Augmentation is the prior failing stage's feedback — a tester's QA
            // report or a reviewer's code review (both bounce here via retryPrevious).
            sb.append("\n\n--- Feedback from the previous stage (QA report or code review) ---\n")
                    .append(augmentation);
        }
        return sb.toString();
    }

    static String parseVerdict(String reportMarkdown) {
        if (reportMarkdown == null) return null;
        String firstLine = reportMarkdown.lines().findFirst().orElse("").toUpperCase();
        if (firstLine.contains("VERDICT") && firstLine.contains("PASS")) return "pass";
        if (firstLine.contains("VERDICT") && firstLine.contains("FAIL")) return "fail";
        return null;
    }

    /**
     * Parse a reviewer's verdict from its final message. Unlike the tester
     * (whose VERDICT is the first line of report.md), the reviewer emits a
     * {@code VERDICT: PASS|FAIL} line at the END of a multi-line summary, so we
     * scan every line for a line that starts with VERDICT. The last such line
     * wins (guards against the word appearing earlier in prose). Returns
     * {@code null} when no verdict line is present.
     */
    static String parseReviewVerdict(String finalMessage) {
        if (finalMessage == null) return null;
        String verdict = null;
        for (String line : (Iterable<String>) finalMessage.lines()::iterator) {
            String u = line.trim().toUpperCase();
            if (u.startsWith("VERDICT")) {
                if (u.contains("FAIL")) verdict = "fail";
                else if (u.contains("PASS")) verdict = "pass";
            }
        }
        return verdict;
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        try {
            return Json.mapper().writeValueAsString(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
