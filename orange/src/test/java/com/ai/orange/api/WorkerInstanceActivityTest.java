package com.ai.orange.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEvent;
import com.ai.orange.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

class WorkerInstanceActivityTest {

    static Task devTask() {
        OffsetDateTime now = OffsetDateTime.now();
        return new Task(UUID.randomUUID(), "t", null, "dev", TaskStatus.IN_PROGRESS, "dev_only", 100,
                "w", now, now, JSONB.valueOf("{}"), now, now);
    }

    static TaskEvent event(Task t, String type) {
        return new TaskEvent(1, t.id(), "dev", type, JSONB.valueOf("{}"), OffsetDateTime.now());
    }

    @Test
    void runFinishedMeansCompleting() {
        Task t = devTask();
        // newest first, as WorkerService reads them
        var w = WorkerService.WorkerInstance.from(t, List.of(event(t, "run_finished"), event(t, "final")));
        assertThat(w.activity()).isEqualTo(WorkerService.Activity.COMPLETING);
    }

    @Test
    void toolUseMeansWorking() {
        Task t = devTask();
        var w = WorkerService.WorkerInstance.from(t, List.of(event(t, "tool_use"), event(t, "ready")));
        assertThat(w.activity()).isEqualTo(WorkerService.Activity.WORKING);
    }
}
