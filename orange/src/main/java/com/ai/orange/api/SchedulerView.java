package com.ai.orange.api;

import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.SchedulerPause;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The scheduler's state as JSON-ready maps, shared by {@code /scheduler} and {@code /health/aggregate}. */
@Component
class SchedulerView {

    private final SchedulerPause pause;
    private final RunRepository runs;
    private final RunsProperties props;

    SchedulerView(SchedulerPause pause, RunRepository runs, RunsProperties props) {
        this.pause = pause;
        this.runs = runs;
        this.props = props;
    }

    /** {@code {reason, until, since}}, or null when not paused. */
    Map<String, Object> paused() {
        return pause.current().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("reason", p.reason());
            m.put("until", p.until() == null ? null : p.until().toString());
            m.put("since", p.since() == null ? null : p.since().toString());
            return m;
        }).orElse(null);
    }

    /** {@code {<kind>: {queued, running, slots}}} for every run kind. */
    Map<String, Object> runs() {
        Map<String, Object> byKind = new LinkedHashMap<>();
        for (RunKind kind : RunKind.values()) {
            byKind.put(kind.dbValue(), Map.of(
                    "queued", runs.countByStatus(kind, RunStatus.QUEUED),
                    "running", runs.countByStatus(kind, RunStatus.RUNNING),
                    "slots", props.slots(kind)));
        }
        return byKind;
    }

    int maxConcurrent() {
        return props.maxConcurrent();
    }
}
