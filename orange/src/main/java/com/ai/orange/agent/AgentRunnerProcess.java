package com.ai.orange.agent;

import com.ai.orange.agent.protocol.Command;
import com.ai.orange.agent.protocol.Event;
import com.ai.orange.agent.protocol.ProtocolCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One running {@code agent-runner} subprocess. Reads NDJSON {@link Event}s on
 * stdout, writes NDJSON {@link Command}s on stdin, tees stderr to a logger.
 *
 * One process == one agent run. Acquire via {@link AgentRunnerLauncher#launch}.
 * Always close (use try-with-resources) so dangling subprocesses don't leak.
 */
public final class AgentRunnerProcess implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentRunnerProcess.class);
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

    private final int instanceId;
    private final Process process;
    private final OutputStream stdin;
    private final Consumer<Event> eventCallback;
    private final Duration cancelGrace;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final CountDownLatch stdoutDoneLatch = new CountDownLatch(1);
    private final Object stdinLock = new Object();
    private volatile boolean closed = false;

    private AgentRunnerProcess(Process process, Consumer<Event> cb, Duration cancelGrace) {
        this.instanceId = INSTANCE_COUNTER.incrementAndGet();
        this.process = process;
        this.eventCallback = cb;
        this.cancelGrace = cancelGrace;
        this.stdin = process.getOutputStream();
    }

    public static AgentRunnerProcess launch(
            List<String> command,
            Duration cancelGrace,
            Command.Start start,
            Consumer<Event> callback) throws IOException {
        log.info("launching agent-runner: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        Process p = pb.start();

        AgentRunnerProcess arp = new AgentRunnerProcess(p, callback, cancelGrace);
        arp.startReaders();
        try {
            arp.send(start);
        } catch (IOException e) {
            arp.close();
            throw e;
        }
        return arp;
    }

    /** Send a non-Start command (hook_response, signal, cancel). Thread-safe. */
    public void send(Command command) throws IOException {
        byte[] line = (ProtocolCodec.writeCommand(command) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (stdinLock) {
            if (closed) throw new IOException("agent-runner #" + instanceId + " is closed");
            stdin.write(line);
            stdin.flush();
        }
    }

    /**
     * Block until the runner has emitted its first {@code ready} event. Returns
     * {@code false} if the timeout elapses (or the process died first).
     */
    public boolean awaitReady(Duration timeout) throws InterruptedException {
        return readyLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Block until the subprocess exits AND all buffered events have been
     * delivered to the event callback, or {@code timeout} elapses. After this
     * returns true, no further events will arrive — anything the callback was
     * going to see, it has seen.
     */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return false;
        long remaining = Math.max(0, deadline - System.nanoTime());
        return stdoutDoneLatch.await(remaining, TimeUnit.NANOSECONDS);
    }

    /**
     * Cooperative shutdown: send a {@code cancel} command and wait up to
     * {@code orange.agent-runner.cancel-grace} for the process to exit. After
     * that we destroy and finally destroyForcibly. Idempotent.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;

        try {
            sendCancelBestEffort();
            if (!process.waitFor(cancelGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("agent-runner #{} ignored cancel; SIGTERM", instanceId);
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    log.warn("agent-runner #{} ignored SIGTERM; SIGKILL", instanceId);
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }

        try {
            stdin.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public int exitValue() {
        if (process.isAlive()) return -1;
        return process.exitValue();
    }

    int instanceId() {
        return instanceId;
    }

    // ─────────────────────────── internals ──────────────────────────────

    private void sendCancelBestEffort() {
        if (!process.isAlive()) return;
        try {
            // Bypass the closed-check so we can still send while close() is in progress.
            byte[] line = (ProtocolCodec.writeCommand(new Command.Cancel("orchestrator close")) + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (stdinLock) {
                stdin.write(line);
                stdin.flush();
            }
        } catch (IOException e) {
            log.debug("could not send cancel to agent-runner #{} (probably already exited): {}", instanceId, e.getMessage());
        }
    }

    private void startReaders() {
        Thread.ofVirtual()
                .name("agent-runner-" + instanceId + "-stdout")
                .start(this::pumpStdout);
        Thread.ofVirtual()
                .name("agent-runner-" + instanceId + "-stderr")
                .start(this::pumpStderr);
    }

    private void pumpStdout() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                Event event;
                try {
                    event = ProtocolCodec.readEvent(line);
                } catch (Exception parseErr) {
                    log.warn("malformed agent-runner #{} event: {}", instanceId, line, parseErr);
                    continue;
                }
                if (event instanceof Event.Ready) {
                    readyLatch.countDown();
                }
                log.debug("agent-runner #{} event: {}", instanceId, event);
                try {
                    eventCallback.accept(event);
                } catch (Exception cbErr) {
                    log.error("event callback threw on {}", event, cbErr);
                }
            }
        } catch (IOException ioErr) {
            log.debug("agent-runner #{} stdout reader: {}", instanceId, ioErr.getMessage());
        } finally {
            // Process exited (or stdout closed); release awaiters that never saw a ready.
            readyLatch.countDown();
            stdoutDoneLatch.countDown();
        }
    }

    private void pumpStderr() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.info("[agent-runner #{}] {}", instanceId, line);
            }
        } catch (IOException ioErr) {
            log.debug("agent-runner #{} stderr reader: {}", instanceId, ioErr.getMessage());
        }
    }
}
