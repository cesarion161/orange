package com.ai.orange.dispatch;

import com.ai.orange.agent.protocol.ProtocolCodec;
import com.ai.orange.planner.PlanGraphService;
import com.ai.orange.planner.PlanJson;
import com.ai.orange.planner.PlannerProperties;
import com.ai.orange.task.Task;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ingests a {@link PlanJson} DAG from anywhere — an inline body or a plan a
 * planner already saved to disk by {@code planId} — and runs it. Validation
 * and the actual graph insert live in {@link PlanGraphService}; once the root
 * tasks flip to {@code ready}, the existing {@code task_ready} →
 * {@code WorkflowStarter} machinery picks them up.
 *
 * <p>The dispatcher is intentionally independent of the planner: it neither
 * knows nor cares whether the plan came from orange's planner agent, a chat
 * session, or an external tool.
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final PlanGraphService planGraph;
    private final PlannerProperties plannerProps;

    public DispatchService(PlanGraphService planGraph, PlannerProperties plannerProps) {
        this.planGraph = planGraph;
        this.plannerProps = plannerProps;
    }

    /**
     * Dispatch a plan supplied inline. The plan is persisted under a fresh
     * {@code planId} for auditability (mirroring the planner's on-disk layout)
     * before the graph is inserted.
     */
    public DispatchResult dispatch(PlanJson plan, String architectureMd, String defaultPipeline)
            throws IOException {
        if (plan == null) throw new IllegalArgumentException("plan required");

        UUID planId = UUID.randomUUID();
        Path outputDir = Path.of(plannerProps.outputRoot()).toAbsolutePath().resolve(planId.toString());
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("plan.json"),
                ProtocolCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        if (architectureMd != null && !architectureMd.isBlank()) {
            Files.writeString(outputDir.resolve("architecture.md"), architectureMd);
        }

        List<Task> created = planGraph.ingest(plan, defaultPipeline);
        log.info("dispatched inline plan {}: {} tasks created", planId, created.size());
        return new DispatchResult(planId, "success", created);
    }

    /**
     * Dispatch a plan a planner already wrote to disk, referenced by its
     * {@code planId}.
     *
     * @throws PlanNotFoundException if no {@code plan.json} exists for that id.
     */
    public DispatchResult dispatchByPlanId(UUID planId, String defaultPipeline) throws IOException {
        if (planId == null) throw new IllegalArgumentException("planId required");

        Path planPath = Path.of(plannerProps.outputRoot()).toAbsolutePath()
                .resolve(planId.toString()).resolve("plan.json");
        if (!Files.exists(planPath)) {
            throw new PlanNotFoundException(planId);
        }

        PlanJson plan;
        try {
            plan = ProtocolCodec.mapper().readValue(planPath.toFile(), PlanJson.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("saved plan.json for " + planId + " is unparseable: " + e.getMessage());
        }

        List<Task> created = planGraph.ingest(plan, defaultPipeline);
        log.info("dispatched saved plan {}: {} tasks created", planId, created.size());
        return new DispatchResult(planId, "success", created);
    }

    public record DispatchResult(UUID planId, String status, List<Task> createdTasks) {}
}
