package com.ai.orange.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.ai.orange.agent.protocol.Command;
import com.ai.orange.agent.protocol.Event;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Exercises {@link AgentRunnerProcess} against a stub Python script that emits
 * canned NDJSON. This tests the Java protocol layer (subprocess management,
 * NDJSON read/write, hook round-trip, cancellation) without touching the real
 * Claude SDK or any network.
 */
@EnabledIf("isPython3Available")
class AgentRunnerProcessIT {

    private static final Duration CANCEL_GRACE = Duration.ofSeconds(5);

    private static Path stubPath;

    @BeforeAll
    static void copyStubFromResources() throws IOException {
        // Copy the stub to a stable temp file so the subprocess can exec it
        // regardless of whether resources are on disk or inside a jar.
        Path tmp = Files.createTempFile("stub_runner-", ".py");
        try (var in = AgentRunnerProcessIT.class.getResourceAsStream("/stub_runner.py")) {
            if (in == null) fail("stub_runner.py not on classpath");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        stubPath = tmp;
        tmp.toFile().deleteOnExit();
    }

    static boolean isPython3Available() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void emits_ready_then_drains_event_stream_through_to_final_success() throws Exception {
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        Command.Start start = new Command.Start("hello", null);

        try (AgentRunnerProcess runner = AgentRunnerProcess.launch(
                List.of("python3", "-u", stubPath.toString()), CANCEL_GRACE, start, events::add)) {

            assertThat(runner.awaitReady(Duration.ofSeconds(5))).isTrue();

            // Drain events up to and including the hook_request.
            Event.HookRequest hook = waitForEvent(events, Event.HookRequest.class, runner);
            assertThat(hook.id()).isEqualTo("h1");
            assertThat(hook.tool()).isEqualTo("Write");

            // Reply allow → stub emits final.success and exits.
            runner.send(Command.HookResponse.allow(hook.id()));

            Event.Final fin = waitForEvent(events, Event.Final.class);
            assertThat(fin.status()).isEqualTo("success");

            assertThat(runner.awaitTermination(Duration.ofSeconds(5))).isTrue();
            assertThat(runner.exitValue()).isZero();
        }
    }

    @Test
    void cancel_drives_subprocess_to_clean_termination() throws Exception {
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        Command.Start start = new Command.Start("hello", null);

        AgentRunnerProcess runner = AgentRunnerProcess.launch(
                List.of("python3", "-u", stubPath.toString()), CANCEL_GRACE, start, events::add);
        try {
            assertThat(runner.awaitReady(Duration.ofSeconds(5))).isTrue();
            // Wait for the hook_request so we know the runner is in its loop.
            waitForEvent(events, Event.HookRequest.class);
        } finally {
            runner.close(); // sends cancel, waits for graceful exit
        }

        Event.Final fin = waitForEvent(events, Event.Final.class);
        assertThat(fin.status()).isEqualTo("cancelled");
        assertThat(runner.exitValue()).isZero();
    }

    @Test
    void signal_command_round_trips_to_subprocess() throws Exception {
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        Command.Start start = new Command.Start("hello", null);

        try (AgentRunnerProcess runner = AgentRunnerProcess.launch(
                List.of("python3", "-u", stubPath.toString()), CANCEL_GRACE, start, events::add)) {

            runner.awaitReady(Duration.ofSeconds(5));
            // Drain through the initial hook_request so we don't see it later.
            waitForEvent(events, Event.HookRequest.class);

            runner.send(new Command.Signal("pr_merged", java.util.Map.of("pr", 42)));

            Event.AssistantMessage echo = waitForEvent(events, Event.AssistantMessage.class);
            assertThat(echo.text()).contains("got signal: pr_merged");
            // Now finish cleanly.
            runner.send(Command.HookResponse.allow("h1"));
            waitForEvent(events, Event.Final.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Event> T waitForEvent(BlockingQueue<Event> q, Class<T> type) throws InterruptedException {
        return waitForEvent(q, type, null);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Event> T waitForEvent(BlockingQueue<Event> q, Class<T> type, AgentRunnerProcess runner) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Event e = q.poll(500, TimeUnit.MILLISECONDS);
            if (e == null) continue;
            if (type.isInstance(e)) return (T) e;
        }
        String state = runner == null ? "" : " (alive=" + runner.isAlive() + " exit=" + runner.exitValue() + ")";
        throw new AssertionError("never saw event of type " + type.getSimpleName() + state + " (queue: " + q + ")");
    }
}
