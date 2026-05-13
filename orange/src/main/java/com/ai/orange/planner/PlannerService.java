package com.ai.orange.planner;

import com.ai.orange.agent.Agent;
import com.ai.orange.agent.AgentRepository;
import com.ai.orange.agent.AgentRunnerLauncher;
import com.ai.orange.agent.AgentRunnerProcess;
import com.ai.orange.agent.protocol.Command;
import com.ai.orange.agent.protocol.Event;
import com.ai.orange.agent.protocol.ProtocolCodec;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import com.ai.orange.workflow.PipelineRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the {@code planner} agent on a free-text feature request and turns the
 * resulting {@code plan.json} + {@code architecture.md} into a real task graph
 * via {@link TaskService#createGraph}.
 *
 * The planner agent is no different from dev/tester at the orchestrator
 * level — it just gets its own seeded {@code agents} row with a system prompt
 * that pins the artifact contract.
 */
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);
    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
    private static final Duration POLL = Duration.ofSeconds(2);

    private static final String DEFAULT_PIPELINE = "dev_only";

    private final AgentRepository agents;
    private final AgentRunnerLauncher launcher;
    private final TaskService taskService;
    private final PlannerProperties props;
    private final PipelineRegistry pipelines;

    public PlannerService(AgentRepository agents,
                          AgentRunnerLauncher launcher,
                          TaskService taskService,
                          PlannerProperties props,
                          PipelineRegistry pipelines) {
        this.agents = agents;
        this.launcher = launcher;
        this.taskService = taskService;
        this.props = props;
        this.pipelines = pipelines;
    }

    @PostConstruct
    void ensureOutputRoot() throws IOException {
        Files.createDirectories(Path.of(props.outputRoot()).toAbsolutePath());
    }

    /**
     * Ingest a {@link PlanJson} produced by a chat-driven planner (i.e. one
     * that didn't go through the subprocess agent-runner). The validation +
     * DAG-insert logic is identical to {@link #plan} — this just skips the
     * subprocess hop and the artifact-files-on-disk step.
     *
     * Used by the chat-as-executor MCP surface: a planner chat session does
     * its thinking interactively, then submits the resulting graph through
     * {@code orange_submit_plan}.
     */
    public PlanResult submitPlan(PlanJson parsed, String architectureMd) throws IOException {
        return submitPlan(parsed, architectureMd, null);
    }

    /**
     * @param defaultPipeline applied to any task that doesn't carry an explicit
     *     {@code pipeline}. Null/blank falls back to the planner-level default
     *     ({@value #DEFAULT_PIPELINE}). Must resolve in {@link PipelineRegistry}.
     */
    public PlanResult submitPlan(PlanJson parsed, String architectureMd, String defaultPipeline) throws IOException {
        if (parsed == null) throw new IllegalArgumentException("plan required");

        UUID planId = UUID.randomUUID();
        Path outputDir = Path.of(props.outputRoot()).toAbsolutePath().resolve(planId.toString());
        Files.createDirectories(outputDir);

        Path planPath = outputDir.resolve("plan.json");
        Files.writeString(planPath, ProtocolCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
        Path archPath = null;
        if (architectureMd != null && !architectureMd.isBlank()) {
            archPath = outputDir.resolve("architecture.md");
            Files.writeString(archPath, architectureMd);
        }

        if (parsed.tasks() == null || parsed.tasks().isEmpty()) {
            return new PlanResult(planId, outputDir, "empty_plan", List.of(), archPath, planPath);
        }

        List<TaskDef> defs = toDefs(parsed.tasks(), defaultPipeline);
        List<EdgeKey> edges = toEdges(parsed.edges());
        List<Task> created = taskService.createGraph(defs, edges);
        log.info("chat-planner {} produced {} tasks, {} edges", planId, created.size(), edges.size());
        return new PlanResult(planId, outputDir, "success", created, archPath, planPath);
    }

    public PlanResult plan(String description) throws IOException, InterruptedException {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description required");
        }

        Agent planner = agents.findEnabledByRole("planner").stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no enabled agent for role 'planner'"));

        UUID planId = UUID.randomUUID();
        Path outputDir = Path.of(props.outputRoot()).toAbsolutePath().resolve(planId.toString());
        Files.createDirectories(outputDir);

        Command.StartOptions options = new Command.StartOptions(
                planner.model(),
                planner.fallbackModel(),
                planner.systemPrompt(),
                jsonbToStringList(planner.allowedTools()),
                jsonbToStringList(planner.disallowedTools()),
                outputDir.toString(),
                planner.maxTurns(),
                null,
                planner.permissionMode(),
                null, null, null);
        Command.Start start = new Command.Start(description, options);

        AtomicReference<String> finalStatus = new AtomicReference<>("failure");
        AtomicReference<Map<String, String>> finalArtifacts = new AtomicReference<>(Map.of());
        try (AgentRunnerProcess proc = launcher.launch(start, event -> {
            if (event instanceof Event.Final f) {
                finalStatus.set(f.status());
                if (f.artifacts() != null) finalArtifacts.set(f.artifacts());
            }
        })) {
            long deadline = System.nanoTime() + props.runTimeout().toNanos();
            while (!proc.awaitTermination(POLL)) {
                if (System.nanoTime() > deadline) {
                    log.error("planner exceeded {} for plan {}; cancelling", props.runTimeout(), planId);
                    return new PlanResult(planId, outputDir, "timeout", List.of(), null, null);
                }
            }
        }

        if (!"success".equals(finalStatus.get())) {
            log.warn("planner finished with status={} for plan {}", finalStatus.get(), planId);
            return new PlanResult(planId, outputDir, finalStatus.get(), List.of(), null, null);
        }

        String archName = finalArtifacts.get().getOrDefault("architecture", "architecture.md");
        String planName = finalArtifacts.get().getOrDefault("plan", "plan.json");
        Path archPath = outputDir.resolve(archName);
        Path planPath = outputDir.resolve(planName);

        if (!Files.exists(planPath)) {
            log.error("planner emitted final.success but plan.json missing at {}", planPath);
            return new PlanResult(planId, outputDir, "missing_plan", List.of(), null, null);
        }

        PlanJson parsed;
        try {
            parsed = ProtocolCodec.mapper().readValue(planPath.toFile(), PlanJson.class);
        } catch (Exception e) {
            log.error("planner produced unparseable plan.json: {}", e.getMessage());
            return new PlanResult(planId, outputDir, "bad_plan_json", List.of(), null, null);
        }

        if (parsed.tasks() == null || parsed.tasks().isEmpty()) {
            return new PlanResult(planId, outputDir, "empty_plan", List.of(), null, null);
        }

        List<TaskDef> defs;
        try {
            defs = toDefs(parsed.tasks(), null);
        } catch (IllegalArgumentException e) {
            log.error("planner produced invalid plan: {}", e.getMessage());
            return new PlanResult(planId, outputDir, "invalid_plan", List.of(), archPath, planPath);
        }
        List<EdgeKey> edges = toEdges(parsed.edges());

        List<Task> created = taskService.createGraph(defs, edges);
        log.info("planner {} produced {} tasks, {} edges", planId, created.size(), edges.size());
        return new PlanResult(planId, outputDir, "success", created, archPath, planPath);
    }

    // ─────────────────────────── helpers ────────────────────────────────

    /**
     * Convert each {@link PlanJson.PlanTask} into a {@link TaskDef}. Resolves
     * each task's pipeline (task value → caller default → service default),
     * validates against {@link PipelineRegistry}, and stamps {@code overrides}
     * onto {@code metadata} so the executor side can read them later.
     *
     * @throws IllegalArgumentException if any task references an unknown pipeline.
     */
    private List<TaskDef> toDefs(List<PlanJson.PlanTask> tasks, String callerDefault) {
        String fallback = (callerDefault == null || callerDefault.isBlank())
                ? DEFAULT_PIPELINE : callerDefault;
        if (!pipelines.contains(fallback)) {
            throw new IllegalArgumentException(
                    "default pipeline '" + fallback + "' is not registered");
        }
        List<TaskDef> defs = new ArrayList<>(tasks.size());
        for (PlanJson.PlanTask t : tasks) {
            String pipeline = (t.pipeline() == null || t.pipeline().isBlank())
                    ? fallback : t.pipeline();
            if (!pipelines.contains(pipeline)) {
                throw new IllegalArgumentException(
                        "task '" + t.key() + "' references unknown pipeline '" + pipeline + "'");
            }
            JSONB metadata = overridesToMetadata(t.overrides());
            defs.add(new TaskDef(
                    t.key(),
                    t.title(),
                    t.description(),
                    (t.role() == null || t.role().isBlank()) ? "dev" : t.role(),
                    pipeline,
                    t.priority(),
                    metadata));
        }
        return defs;
    }

    private static List<EdgeKey> toEdges(List<PlanJson.PlanEdge> raw) {
        return raw == null ? List.of()
                : raw.stream().map(e -> new EdgeKey(e.from(), e.to())).toList();
    }

    private static JSONB overridesToMetadata(Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) return null;
        try {
            String json = ProtocolCodec.mapper().writeValueAsString(Map.of("overrides", overrides));
            return JSONB.valueOf(json);
        } catch (Exception e) {
            // Overrides are advisory — never block plan ingestion over a serialization quirk.
            return null;
        }
    }

    public record PlanResult(
            UUID planId,
            Path outputDir,
            String status,
            List<Task> createdTasks,
            Path architectureMd,
            Path planJson) {
    }

    private static List<String> jsonbToStringList(JSONB jsonb) {
        if (jsonb == null) return List.of();
        try {
            return ProtocolCodec.mapper().readValue(jsonb.data(), STR_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
