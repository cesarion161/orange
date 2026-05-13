package com.ai.orange.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The set of pipelines the orchestrator knows how to run. Keyed by the value
 * stored in the {@code tasks.pipeline} column. New pipelines are added here as
 * the orchestrator's stage repertoire grows (Phase 5 will likely add review-
 * before-QA, deploy stages, etc.).
 */
@Component
public class PipelineRegistry {

    private final Map<String, Pipeline> byName = new LinkedHashMap<>();

    public PipelineRegistry() {
        register(new Pipeline("dev_only",
                List.of(Stage.required("dev", "dev"))));

        register(new Pipeline("dev_qa",
                List.of(
                        Stage.required("dev", "dev"),
                        Stage.retryPrevious("qa", "tester").withEnv())));

        register(new Pipeline("dev_review_qa",
                List.of(
                        Stage.required("dev", "dev"),
                        Stage.retryPrevious("review", "reviewer"),
                        Stage.retryPrevious("qa", "tester").withEnv())));
    }

    public Optional<Pipeline> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Pipeline getOrThrow(String name) {
        Pipeline p = byName.get(name);
        if (p == null) throw new IllegalArgumentException("unknown pipeline: " + name);
        return p;
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    private void register(Pipeline p) {
        byName.put(p.name(), p);
    }
}
