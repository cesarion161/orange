package com.ai.orange.harness.container;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Test utility for container mode: the image the fake Claude Code runs in
 * ({@code src/test/resources/worker-test/Dockerfile}: a JDK, git and {@code FakeClaude.java}),
 * built once per JVM with the Docker CLI, and the settings that point orange at it. The first
 * build pulls the base image; later ones hit Docker's build cache.
 */
public final class ContainerTestSupport {

    public static final String IMAGE = "orange-worker-test:local";
    /** How the image starts the fake. */
    public static final List<String> FAKE_CLAUDE = List.of("java", "/opt/fake-claude/FakeClaude.java");

    private static boolean built;

    private ContainerTestSupport() {}

    /** Builds the test image when this JVM has not done so yet, and returns its name. */
    public static synchronized String image() {
        if (!built) {
            try {
                Path context = Files.createTempDirectory("orange-worker-test-");
                try {
                    copy("/worker-test/Dockerfile", context.resolve("Dockerfile"));
                    copy("/fake-claude/FakeClaude.java", context.resolve("FakeClaude.java"));
                    docker("build", "-q", "-t", IMAGE, context.toString());
                } finally {
                    Files.deleteIfExists(context.resolve("Dockerfile"));
                    Files.deleteIfExists(context.resolve("FakeClaude.java"));
                    Files.deleteIfExists(context);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            built = true;
        }
        return IMAGE;
    }

    /** Points a Spring test context at container mode with the test image and the fake inside it. */
    public static void register(DynamicPropertyRegistry r) {
        r.add("orange.runner.isolation", () -> "container");
        r.add("orange.runner.container.image", ContainerTestSupport::image);
        for (int i = 0; i < FAKE_CLAUDE.size(); i++) {
            String value = FAKE_CLAUDE.get(i);
            r.add("orange.runner.container.claude-command[" + i + "]", () -> value);
        }
    }

    /** The same settings, for tests without Spring. */
    public static RunnerProperties.Container settings() {
        RunnerProperties.Container d = RunnerProperties.Container.defaults();
        return new RunnerProperties.Container(d.docker(), image(), FAKE_CLAUDE, d.network(), d.user(), d.home(),
                d.memory(), d.cpus(), d.pidsLimit(), d.volumes());
    }

    /** The ids of every container, running or not, that carries the run's label. */
    public static List<String> containersOf(UUID runId) {
        return docker("ps", "-aq", "--filter", "label=" + DockerCommand.LABEL_RUN + "=" + runId)
                .lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    /** Runs the Docker CLI with the test JVM's environment and returns its stdout. Throws on a non-zero exit. */
    public static String docker(String... args) {
        List<String> argv = new ArrayList<>();
        argv.add("docker");
        argv.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(argv).start();
            p.getOutputStream().close();
            AtomicReference<String> err = new AtomicReference<>("");
            Thread errThread = Thread.ofVirtual().start(() -> err.set(read(p.getErrorStream())));
            String out = read(p.getInputStream());
            // A first build pulls the base image.
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IllegalStateException(String.join(" ", argv) + " timed out");
            }
            errThread.join(5000);
            if (p.exitValue() != 0) {
                throw new IllegalStateException(String.join(" ", argv) + " exited " + p.exitValue() + ": " + err.get().strip());
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("the container tests need the Docker CLI on the PATH", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void copy(String resource, Path target) throws IOException {
        try (InputStream in = ContainerTestSupport.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException(resource.substring(1) + " not on the test classpath");
            Files.copy(in, target);
        }
    }
}
