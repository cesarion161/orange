package com.ai.orange.harness;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supervises one harness process: feeds the prompt on stdin, turns stdout lines into events,
 * keeps raw copies of stdout and stderr in the run directory, and kills the whole process tree
 * on request.
 */
public final class ProcessRunHandle implements RunHandle {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunHandle.class);
    /** After the process exits, how long to wait for its output to drain (a stray grandchild may hold the pipe). */
    private static final long DRAIN_TIMEOUT_SECONDS = 10;

    private final Process process;
    private final CompletableFuture<RunExit> exit = new CompletableFuture<>();
    private final AtomicReference<String> killReason = new AtomicReference<>();
    private final AtomicReference<RunEvent.Result> lastResult = new AtomicReference<>();

    private ProcessRunHandle(Process process) {
        this.process = process;
    }

    /**
     * Starts {@code pb} and supervises it.
     *
     * @param stdin  written to the child's stdin, which is then closed
     * @param runDir receives {@code stream.jsonl} (raw stdout) and {@code stderr.log}
     * @param parser turns one stdout line into events
     */
    public static ProcessRunHandle start(ProcessBuilder pb, String stdin, Path runDir,
                                         Function<String, List<RunEvent>> parser,
                                         Consumer<RunEvent> sink) throws IOException {
        Files.createDirectories(runDir);
        Process process = pb.start();
        ProcessRunHandle handle = new ProcessRunHandle(process);
        handle.supervise(stdin, runDir, parser, sink);
        return handle;
    }

    private void supervise(String stdin, Path runDir, Function<String, List<RunEvent>> parser,
                           Consumer<RunEvent> sink) {
        Thread.ofVirtual().name("run-stdin-" + process.pid()).start(() -> writeStdin(stdin));

        Thread stdout = Thread.ofVirtual().name("run-stdout-" + process.pid()).start(() ->
                pump(process.getInputStream(), runDir.resolve("stream.jsonl"), line -> {
                    for (RunEvent event : parser.apply(line)) {
                        if (event instanceof RunEvent.Result r) lastResult.set(r);
                        deliver(sink, event);
                    }
                }));
        Thread stderr = Thread.ofVirtual().name("run-stderr-" + process.pid()).start(() ->
                pump(process.getErrorStream(), runDir.resolve("stderr.log"),
                        line -> deliver(sink, new RunEvent.Stderr(line))));

        Thread.ofVirtual().name("run-wait-" + process.pid()).start(() -> {
            try {
                int code = process.waitFor();
                stdout.join(TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS));
                stderr.join(TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS));
                exit.complete(new RunExit(code, killReason.get(), lastResult.get()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exit.completeExceptionally(e);
            }
        });
    }

    private void writeStdin(String stdin) {
        try (OutputStream out = process.getOutputStream()) {
            if (stdin != null) out.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The child exited or closed stdin before reading it all; its exit code will say why.
            log.debug("stdin write to pid {} ended early: {}", process.pid(), e.getMessage());
        }
    }

    private static void pump(InputStream in, Path copy, Consumer<String> onLine) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             BufferedWriter writer = Files.newBufferedWriter(copy, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
                onLine.accept(line);
            }
        } catch (IOException e) {
            log.debug("output pump for {} stopped: {}", copy.getFileName(), e.getMessage());
        }
    }

    private static void deliver(Consumer<RunEvent> sink, RunEvent event) {
        try {
            sink.accept(event);
        } catch (RuntimeException e) {
            log.warn("run event sink threw on {}: {}", event.getClass().getSimpleName(), e.toString());
        }
    }

    @Override
    public CompletableFuture<RunExit> exit() {
        return exit;
    }

    @Override
    public void kill(String reason) {
        killReason.compareAndSet(null, reason);
        // Children first: once the parent dies they are re-parented and no longer its descendants.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }
}
