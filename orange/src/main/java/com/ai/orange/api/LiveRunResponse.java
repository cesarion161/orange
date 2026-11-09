package com.ai.orange.api;

import com.ai.orange.run.Run;
import com.ai.orange.run.RunEventRow;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A running run and its latest events (newest first), for the live view. */
public record LiveRunResponse(
        @JsonProperty("run_id") UUID runId,
        @JsonProperty("task_id") UUID taskId,
        @JsonProperty("task_title") String taskTitle,
        String kind,
        String role,
        String model,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        List<RunEventResponse> recent) {

    public static LiveRunResponse of(Run run, String taskTitle, List<RunEventRow> recent) {
        return new LiveRunResponse(run.id(), run.taskId(), taskTitle, run.kind().dbValue(), run.role(), run.model(),
                run.startedAt(), recent.stream().map(RunEventResponse::of).toList());
    }
}
