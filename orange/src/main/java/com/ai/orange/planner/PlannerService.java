package com.ai.orange.planner;

import com.ai.orange.json.Json;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunResult;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.WorkerRunner;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
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
 * <p>The planner is an ordinary agent run of kind {@code plan} with no task; its
 * role prompt ({@code roles/planner.md}) pins the artifact contract. In container mode it runs in a
 * container that holds its directory (writable: it writes the plan there); on the host, a planner
 * whose directory holds the repository gets no {@code WebFetch}.
 */
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private final WorkerRunner runner;
    private final RunsProperties runsProps;
    private final PlanGraphService planGraph;
    private final PlannerProperties props;

    public PlannerService(WorkerRunner runner,
                          RunsProperties runsProps,
                          PlanGraphService planGraph,
                          PlannerProperties props) {
        this.runner = runner;
        this.runsProps = runsProps;
        this.planGraph = planGraph;
        this.props = props;
    }

    @PostConstruct
    void ensureOutputRoot() throws IOException {
        Files.createDirectories(Path.of(props.outputRoot()).toAbsolutePath());
    }

    /** Plans {@code description} in a fresh directory of its own under the output root. */
    public PlanResult plan(String description) throws IOException, InterruptedException {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description required");
        }
        Path outputDir = Path.of(props.outputRoot()).toAbsolutePath().resolve(UUID.randomUUID().toString());
        return plan(outputDir, description);
    }

    /**
     * Runs the planner on {@code prompt} in {@code outputDir}, which the caller may have prepared (a
     * plan of a project gets its repository at {@code repo/}). The result's {@code planId} is the
     * directory's name when that is a UUID.
     */
    public PlanResult plan(Path outputDir, String prompt) throws IOException, InterruptedException {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt required");
        }
        UUID planId = idOf(outputDir);
        Files.createDirectories(outputDir);

        // A planner that reads the repository (agent-written text) gets no WebFetch on the host: nothing it
        // reads there could send what the host holds anywhere. In a container it keeps it (only its mounts are there).
        Set<String> notOnHost = Files.isDirectory(outputDir.resolve("repo")) ? Set.of("WebFetch") : Set.of();
        RunResult run = runner.runDirect(RunKind.PLAN, "planner", outputDir, prompt,
                runsProps.timeout(RunKind.PLAN), JSONB.valueOf("{}"), notOnHost);
        UUID runId = run.runId();

        if (!run.succeeded()) {
            String status = "timeout".equals(run.reason()) ? "timeout"
                    : run.status() == RunStatus.CANCELLED ? "cancelled" : "failure";
            log.warn("planner run {} finished with status={} reason={} for plan {}", runId, status,
                    run.reason(), planId);
            return new PlanResult(planId, outputDir, status, null, null, null, runId);
        }

        Path archPath = outputDir.resolve("architecture.md");
        Path planPath = outputDir.resolve("plan.json");

        if (!Files.exists(planPath)) {
            log.error("planner emitted final.success but plan.json missing at {}", planPath);
            return new PlanResult(planId, outputDir, "missing_plan", null, null, null, runId);
        }

        PlanJson parsed;
        try {
            parsed = Json.mapper().readValue(planPath.toFile(), PlanJson.class);
        } catch (Exception e) {
            log.error("planner produced unparseable plan.json: {}", e.getMessage());
            return new PlanResult(planId, outputDir, "bad_plan_json", null, null, null, runId);
        }

        if (parsed.tasks() == null || parsed.tasks().isEmpty()) {
            return new PlanResult(planId, outputDir, "empty_plan", parsed, archPath, planPath, runId);
        }

        try {
            planGraph.validateAcyclic(parsed);
        } catch (IllegalArgumentException e) {
            log.error("planner produced invalid plan: {}", e.getMessage());
            return new PlanResult(planId, outputDir, "invalid_plan", parsed, archPath, planPath, runId);
        }

        log.info("planner {} produced {} tasks, {} edges (not dispatched)",
                planId, parsed.tasks().size(), parsed.edges() == null ? 0 : parsed.edges().size());
        return new PlanResult(planId, outputDir, "success", parsed, archPath, planPath, runId);
    }

    /** The plan's id: its directory's name when that is one, else a new one. */
    private static UUID idOf(Path outputDir) {
        Path name = outputDir.getFileName();
        if (name != null) {
            try {
                return UUID.fromString(name.toString());
            } catch (IllegalArgumentException notAnId) {
                // a directory the caller named otherwise
            }
        }
        return UUID.randomUUID();
    }

    /**
     * Outcome of a planner run or a chat-planner save. Carries the reviewable
     * {@link PlanJson} DAG (and where its artifacts live on disk) — never any
     * created tasks, because the planner does not create tasks. {@code runId} is the planner's run;
     * null for a plan that was saved rather than run.
     */
    public record PlanResult(
            UUID planId,
            Path outputDir,
            String status,
            PlanJson plan,
            Path architectureMd,
            Path planJson,
            UUID runId) {

        public PlanResult(UUID planId, Path outputDir, String status, PlanJson plan, Path architectureMd,
                          Path planJson) {
            this(planId, outputDir, status, plan, architectureMd, planJson, null);
        }
    }
}
