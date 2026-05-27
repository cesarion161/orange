package com.ai.orange.planner;

import com.ai.orange.agent.Agent;
import com.ai.orange.agent.AgentRepository;
import com.ai.orange.agent.AgentRunnerLauncher;
import com.ai.orange.agent.AgentRunnerProcess;
import com.ai.orange.agent.protocol.Command;
import com.ai.orange.agent.protocol.Event;
import com.ai.orange.agent.protocol.ProtocolCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the {@code planner} agent on a free-text feature request and turns its
 * {@code plan.json} + {@code architecture.md} output into a reviewable
 * {@link PlanJson} DAG.
 *
 * <p>The planner is deliberately <b>plan-only</b>: it produces the plan
 * artifact and stops. It does not create tasks and does not dispatch anything —
 * that is the dispatcher's job ({@code com.ai.orange.dispatch.DispatchService},
 * reachable at {@code POST /dispatch}). This keeps the two halves independent:
 * a plan can be reviewed, saved, or re-imported before anyone runs it.
 *
 * <p>The planner agent is no different from dev/tester at the orchestrator
 * level — it just gets its own seeded {@code agents} row with a system prompt
 * that pins the artifact contract.
 */
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);
    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
    private static final Duration POLL = Duration.ofSeconds(2);

    private final AgentRepository agents;
    private final AgentRunnerLauncher launcher;
    private final PlanGraphService planGraph;
    private final PlannerProperties props;

    public PlannerService(AgentRepository agents,
                          AgentRunnerLauncher launcher,
                          PlanGraphService planGraph,
                          PlannerProperties props) {
        this.agents = agents;
        this.launcher = launcher;
        this.planGraph = planGraph;
        this.props = props;
    }

    @PostConstruct
    void ensureOutputRoot() throws IOException {
        Files.createDirectories(Path.of(props.outputRoot()).toAbsolutePath());
    }

    /**
     * Persist a {@link PlanJson} produced by a chat-driven planner (one that did
     * its thinking interactively rather than via the subprocess agent-runner):
     * write the artifact files and validate the DAG. Does <b>not</b> create
     * tasks — dispatch is a separate step.
     *
     * <p>Used by the chat-as-planner MCP surface: a planner chat session drafts
     * the graph, then saves it through {@code orange_submit_plan}; a later
     * {@code orange_dispatch} runs it.
     */
    public PlanResult savePlan(PlanJson parsed, String architectureMd) throws IOException {
        if (parsed == null) throw new IllegalArgumentException("plan required");

        UUID planId = UUID.randomUUID();
        Path outputDir = Path.of(props.outputRoot()).toAbsolutePath().resolve(planId.toString());

        if (parsed.tasks() == null || parsed.tasks().isEmpty()) {
            return new PlanResult(planId, outputDir, "empty_plan", parsed, null, null);
        }

        // Validate BEFORE writing anything so a malformed plan leaves no stale
        // artifacts on disk (and the caller can complete/fail the claim cleanly).
        planGraph.validateAcyclic(parsed);

        Files.createDirectories(outputDir);
        Path planPath = outputDir.resolve("plan.json");
        Files.writeString(planPath, ProtocolCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
        Path archPath = null;
        if (architectureMd != null && !architectureMd.isBlank()) {
            archPath = outputDir.resolve("architecture.md");
            Files.writeString(archPath, architectureMd);
        }

        log.info("chat-planner {} saved {} tasks (not dispatched)", planId, parsed.tasks().size());
        return new PlanResult(planId, outputDir, "success", parsed, archPath, planPath);
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
                    return new PlanResult(planId, outputDir, "timeout", null, null, null);
                }
            }
        }

        if (!"success".equals(finalStatus.get())) {
            log.warn("planner finished with status={} for plan {}", finalStatus.get(), planId);
            return new PlanResult(planId, outputDir, finalStatus.get(), null, null, null);
        }

        String archName = finalArtifacts.get().getOrDefault("architecture", "architecture.md");
        String planName = finalArtifacts.get().getOrDefault("plan", "plan.json");
        Path archPath = outputDir.resolve(archName);
        Path planPath = outputDir.resolve(planName);

        if (!Files.exists(planPath)) {
            log.error("planner emitted final.success but plan.json missing at {}", planPath);
            return new PlanResult(planId, outputDir, "missing_plan", null, null, null);
        }

        PlanJson parsed;
        try {
            parsed = ProtocolCodec.mapper().readValue(planPath.toFile(), PlanJson.class);
        } catch (Exception e) {
            log.error("planner produced unparseable plan.json: {}", e.getMessage());
            return new PlanResult(planId, outputDir, "bad_plan_json", null, null, null);
        }

        if (parsed.tasks() == null || parsed.tasks().isEmpty()) {
            return new PlanResult(planId, outputDir, "empty_plan", parsed, archPath, planPath);
        }

        try {
            planGraph.validateAcyclic(parsed);
        } catch (IllegalArgumentException e) {
            log.error("planner produced invalid plan: {}", e.getMessage());
            return new PlanResult(planId, outputDir, "invalid_plan", parsed, archPath, planPath);
        }

        log.info("planner {} produced {} tasks, {} edges (not dispatched)",
                planId, parsed.tasks().size(), parsed.edges() == null ? 0 : parsed.edges().size());
        return new PlanResult(planId, outputDir, "success", parsed, archPath, planPath);
    }

    /**
     * Outcome of a planner run or a chat-planner save. Carries the reviewable
     * {@link PlanJson} DAG (and where its artifacts live on disk) — never any
     * created tasks, because the planner does not create tasks.
     */
    public record PlanResult(
            UUID planId,
            Path outputDir,
            String status,
            PlanJson plan,
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
