package com.ai.orange.run;

import com.ai.orange.json.Json;
import com.ai.orange.task.TaskEventRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What every executor does around a run: renew its lease, announce it on the task's event
 * stream, and end it — post-run steps first, then the row, so a run stays {@code running} until
 * its effects have landed (the reactor's ordering rule).
 */
@Component
public class RunLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycle.class);

    private final RunRepository runs;
    private final TaskEventRepository taskEvents;
    private final PostRunSteps postRunSteps;
    private final RunsProperties props;

    public RunLifecycle(RunRepository runs, TaskEventRepository taskEvents, PostRunSteps postRunSteps,
                        RunsProperties props) {
        this.runs = runs;
        this.taskEvents = taskEvents;
        this.postRunSteps = postRunSteps;
        this.props = props;
    }

    /** True when renewed, false when the lease is gone (finished or reaped), null when the database failed. */
    public Boolean renewLease(Run run) {
        try {
            return runs.heartbeat(run.id(), props.workerId(), props.leaseTtl());
        } catch (RuntimeException e) {
            log.warn("run {}: could not renew its lease ({}); trying again", run.id(), e.getMessage());
            return null;
        }
    }

    /** The run as the database has it now, as a result; for runs that were finished elsewhere. */
    public RunResult fromRow(UUID runId) {
        return runs.findById(runId)
                .map(r -> new RunResult(r.id(), r.status(), r.error(), RunOutcome.parse(r.outcome()).orElse(null),
                        null, null, r.costUsd()))
                .orElseGet(() -> RunResult.failed(runId, "lease_expired"));
    }

    /** {@code run_started {run_id, kind, …details}} on the task's event stream. */
    public void started(Run run, ObjectNode details) {
        ObjectNode payload = Json.mapper().createObjectNode()
                .put("run_id", run.id().toString())
                .put("kind", run.kind().dbValue());
        if (details != null) payload.setAll(details);
        taskEvent(run, "run_started", payload);
    }

    public RunResult finish(Run run, RunResult result) {
        return finish(run, result, 0, 0, 0, null);
    }

    /** Post-run steps, then the row is finished, then {@code run_finished} on the task's event stream. */
    public RunResult finish(Run run, RunResult result, long tokensIn, long tokensOut, long cacheRead,
                            String sessionId) {
        RunResult fin;
        try {
            fin = postRunSteps.after(run, result);
        } catch (RuntimeException e) {
            log.error("run {}: post-run steps failed: {}", run.id(), e.toString(), e);
            fin = result;
        }
        BigDecimal cost = fin.costUsd() == null ? BigDecimal.ZERO : fin.costUsd();
        if (!runs.finish(run.id(), fin.status(), fin.reason(), cost, tokensIn, tokensOut, cacheRead, sessionId)) {
            log.warn("run {} was no longer running when it finished; its row was left as it was", run.id());
        }
        ObjectNode payload = Json.mapper().createObjectNode()
                .put("run_id", run.id().toString())
                .put("kind", run.kind().dbValue())
                .put("status", fin.status().dbValue())
                .put("cost_usd", cost);
        if (fin.reason() != null) payload.put("reason", fin.reason());
        taskEvent(run, "run_finished", payload);
        log.info("run {} {} finished status={} reason={} cost={}", run.id(), run.kind().dbValue(),
                fin.status().dbValue(), fin.reason(), cost);
        return fin;
    }

    void taskEvent(Run run, String type, ObjectNode payload) {
        if (run.taskId() == null) return;
        try {
            taskEvents.append(run.taskId(), run.role(), type, JSONB.valueOf(payload.toString()));
        } catch (RuntimeException e) {
            log.warn("could not persist {} for task {}: {}", type, run.taskId(), e.getMessage());
        }
    }
}
