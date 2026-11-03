package com.ai.orange.harness;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Starts runs on one agent harness (Claude Code today, OpenCode later). The coordinator never
 * talks to a harness directly: it builds a {@link RunSpec} and receives normalized
 * {@link RunEvent}s, so a harness's flags or output format can change behind its adapter.
 */
public interface HarnessAdapter {

    String id();

    /**
     * Starts the run. {@code sink} receives every event in order on a reader thread; an exception
     * it throws is logged and does not affect the run.
     */
    RunHandle start(RunSpec spec, Consumer<RunEvent> sink) throws IOException;
}
