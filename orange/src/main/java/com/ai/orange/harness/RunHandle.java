package com.ai.orange.harness;

import java.util.concurrent.CompletableFuture;

/** A started run. */
public interface RunHandle {

    /** Completes once the process has exited and its output has been fully read. */
    CompletableFuture<RunExit> exit();

    /** Kills the run and every process it started. The first reason given is the one reported. */
    void kill(String reason);

    boolean isAlive();

    /** The operating-system process id of the harness process. */
    long pid();
}
