package com.ai.orange.harness.container;

import com.ai.orange.harness.claude.ClaudeCodeCommand;
import com.sun.security.auth.module.UnixSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The one place orange talks to Docker, through the Docker CLI: a child process is what orange
 * already supervises, and {@code --env NAME} keeps a secret's value out of every argv.
 *
 * <p>The docker client itself gets a cleared environment: the few variables it needs to find the
 * daemon, plus the secrets of the container it starts. Nothing else of orange's environment
 * reaches even the client.
 */
@Component
public class ContainerRuntime {

    private static final Logger log = LoggerFactory.getLogger(ContainerRuntime.class);
    /** What the docker client needs of the host's environment to find its daemon. */
    static final List<String> CLIENT_ENV = List.of("PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG",
            "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY", "XDG_RUNTIME_DIR");
    /** For docker's own short commands (rm, ps, network, image inspect). */
    private static final Duration SHORT = Duration.ofSeconds(15);
    /** How much of a command's output (the end) is kept. */
    private static final int OUTPUT_TAIL = 64 * 1024;

    private final RunnerProperties props;
    private final Supplier<Map<String, String>> hostEnv;
    private volatile List<String> subnets = List.of();

    @Autowired
    public ContainerRuntime(RunnerProperties props) {
        this(props, System::getenv);
    }

    public ContainerRuntime(RunnerProperties props, Supplier<Map<String, String>> hostEnv) {
        this.props = props;
        this.hostEnv = hostEnv;
        // The settings refuse a root user; orange's own uid is only looked at when it is the one used.
        if (enabled() && props.container().user().isBlank() && Own.UID == 0) {
            throw new IllegalStateException(
                    "container runs must not be root: run orange as a normal user, or set orange.runner.container.user");
        }
    }

    /** Whether a task's runs go into containers ({@code orange.runner.isolation=container}). */
    public boolean enabled() {
        return props.contained();
    }

    public RunnerProperties.Container settings() {
        return props.container();
    }

    /** {@code uid[:gid]} every container runs as: the setting, else this process's own uid and gid. */
    public String user() {
        String configured = props.container().user();
        return configured.isBlank() ? Own.UID + ":" + Own.GID : configured;
    }

    /**
     * The docker client that runs {@code c}, not started: its stdin, stdout, stderr and exit code
     * are the command's. An online container joins the run network, which is created when missing.
     */
    public ProcessBuilder command(ContainerSpec c) throws ContainerUnavailableException {
        if (c.online()) ensureNetwork();
        ProcessBuilder pb = new ProcessBuilder(DockerCommand.run(c, settings(), user()));
        clientEnvironment(pb);
        // --env NAME: the client reads the value here and hands it to the daemon.
        pb.environment().putAll(c.secretEnv());
        return pb;
    }

    /**
     * Runs {@code c} to its end with nothing on stdin. At the timeout the container is removed and
     * the exit code is -1.
     */
    public Result run(ContainerSpec c, Duration timeout) throws ContainerUnavailableException {
        ProcessBuilder pb = command(c).redirectErrorStream(true);
        Process client;
        try {
            client = pb.start();
        } catch (IOException e) {
            throw new ContainerUnavailableException("could not start " + pb.command().getFirst() + ": " + e.getMessage(), e);
        }
        closeStdin(client);
        Tail out = new Tail(client.getInputStream(), OUTPUT_TAIL);
        Thread reader = Thread.ofVirtual().name("container-out-" + c.name()).start(out);
        try {
            if (!client.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("container {} did not end within {}: removing it", c.name(), timeout);
                kill(c.name(), client);
                reader.join(1000);
                return new Result(-1, out.text());
            }
            reader.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            kill(c.name(), client);
            Thread.currentThread().interrupt();
            return new Result(-1, out.text());
        }
        return new Result(client.exitValue(), out.text());
    }

    /** {@code docker rm -f}: kills and removes the container, if there is one. Never throws. */
    public void remove(String name) {
        // A kill must not be cut short by the interrupt that asked for it.
        boolean interrupted = Thread.interrupted();
        try {
            Ran removed = exec(DockerCommand.remove(settings().docker(), List.of(name)), SHORT);
            if (removed.exitCode() != 0) {
                log.warn("could not remove container {}: {}", name, removed.lastStderrLine());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("could not remove container {}: {}", name, e.toString());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Removes every container that carries the run's label, running or not. Never throws.
     *
     * @return how many were removed; 0 when docker cannot be reached
     */
    public int removeByRun(UUID runId) {
        boolean interrupted = Thread.interrupted();
        try {
            Ran listed = exec(DockerCommand.listByRun(settings().docker(), runId), SHORT);
            if (listed.exitCode() != 0) {
                log.warn("could not list the containers of run {}: {}", runId, listed.lastStderrLine());
                return 0;
            }
            List<String> ids = lines(listed.stdout());
            if (ids.isEmpty()) return 0;
            Ran removed = exec(DockerCommand.remove(settings().docker(), ids), SHORT);
            if (removed.exitCode() != 0) {
                log.warn("could not remove the containers of run {}: {}", runId, removed.lastStderrLine());
                return 0;
            }
            return ids.size();
        } catch (IOException | RuntimeException e) {
            log.warn("could not remove the containers of run {}: {}", runId, e.toString());
            return 0;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** The run network's subnets (CIDR), as last seen; empty until the network has been looked at. */
    public List<String> subnets() {
        return subnets;
    }

    /**
     * @param exitCode the command's, or docker's own (125, 126, 127: {@link DockerCommand#dockerFailed}); -1 at the timeout
     * @param output   the last 64 KiB of stdout and stderr
     */
    public record Result(int exitCode, String output) {}

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!enabled()) return;
        try {
            ensureNetwork();
        } catch (ContainerUnavailableException e) {
            log.warn("container mode, but Docker is not usable yet: {}. Runs of tasks will pause the scheduler until it is.",
                    e.getMessage());
        }
        String image = settings().image();
        try {
            Ran inspected = exec(docker("image", "inspect", "--format", "{{.Id}}", image), SHORT);
            if (inspected.exitCode() != 0) {
                log.warn("container mode, but the image {} is not on this host ({}): build it with orange/worker/build.sh",
                        image, inspected.lastStderrLine());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("container mode, but the image {} could not be looked up ({}): build it with orange/worker/build.sh",
                    image, e.toString());
        }
    }

    /**
     * Makes sure the run network exists and remembers its subnets: a bridge of orange's own, with
     * traffic between its containers off.
     */
    private synchronized void ensureNetwork() throws ContainerUnavailableException {
        String network = settings().network();
        try {
            Ran seen = inspectNetwork(network);
            if (seen.exitCode() == -1) {
                throw new ContainerUnavailableException("docker did not answer within " + SHORT.toSeconds() + "s");
            }
            if (seen.exitCode() != 0) {
                Ran created = exec(docker("network", "create", "--driver", "bridge",
                        "--opt", "com.docker.network.bridge.enable_icc=false", "--label", "orange.managed=true", network), SHORT);
                if (created.exitCode() == 0) log.info("created the run network {}", network);
                // Another coordinator on this daemon may have created it in between: what counts is that it is there.
                seen = inspectNetwork(network);
                if (seen.exitCode() != 0) {
                    String why = created.exitCode() != 0 ? created.lastStderrLine() : seen.lastStderrLine();
                    throw new ContainerUnavailableException("the docker network " + network + " could not be created: " + why);
                }
            }
            subnets = lines(seen.stdout());
        } catch (ContainerUnavailableException e) {
            throw e;
        } catch (IOException e) {
            throw new ContainerUnavailableException("could not start " + settings().docker().getFirst() + ": " + e.getMessage(), e);
        }
    }

    private Ran inspectNetwork(String network) throws IOException {
        return exec(docker("network", "inspect", "--format", "{{range .IPAM.Config}}{{println .Subnet}}{{end}}", network), SHORT);
    }

    private List<String> docker(String... args) {
        List<String> argv = new ArrayList<>(settings().docker());
        argv.addAll(List.of(args));
        return argv;
    }

    /** Removes the container, ends the client, and removes once more: the first removal may have come before the daemon created it. */
    private void kill(String name, Process client) {
        remove(name);
        client.descendants().forEach(ProcessHandle::destroyForcibly);
        client.destroyForcibly();
        try {
            client.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        remove(name);
    }

    private void clientEnvironment(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(ClaudeCodeCommand.passThrough(hostEnv.get(), CLIENT_ENV));
    }

    /** One short docker command, with the client's cleared environment. Exit code -1 at the timeout. */
    private Ran exec(List<String> argv, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        clientEnvironment(pb);
        Process p = pb.start();
        closeStdin(p);
        Tail out = new Tail(p.getInputStream(), OUTPUT_TAIL);
        Tail err = new Tail(p.getErrorStream(), OUTPUT_TAIL);
        Thread outThread = Thread.ofVirtual().start(out);
        Thread errThread = Thread.ofVirtual().start(err);
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                outThread.join(1000);
                errThread.join(1000);
                return new Ran(-1, out.text(), "timed out after " + timeout.toSeconds() + "s");
            }
            outThread.join(1000);
            errThread.join(1000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Ran(-1, out.text(), "interrupted");
        }
        return new Ran(p.exitValue(), out.text(), lastLine(err.text()));
    }

    private record Ran(int exitCode, String stdout, String lastStderrLine) {}

    private static void closeStdin(Process p) {
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static String lastLine(String text) {
        List<String> lines = lines(text);
        return lines.isEmpty() ? "" : lines.getLast();
    }

    /** This process's uid and gid; looked up only when no user is configured. */
    private static final class Own {
        static final long UID;
        static final long GID;

        static {
            UnixSystem unix = new UnixSystem();
            UID = unix.getUid();
            GID = unix.getGid();
        }
    }

    /** Reads a stream to its end, keeping its last {@code max} bytes. */
    private static final class Tail implements Runnable {
        private final InputStream in;
        private final byte[] ring;
        private long total;

        Tail(InputStream in, int max) {
            this.in = in;
            this.ring = new byte[max];
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buf)) != -1) keep(buf, n);
            } catch (IOException ignored) {
                // the process went away; keep what we have
            }
        }

        private synchronized void keep(byte[] buf, int n) {
            for (int i = 0; i < n; i++) ring[(int) ((total + i) % ring.length)] = buf[i];
            total += n;
        }

        synchronized String text() {
            if (total <= ring.length) return new String(ring, 0, (int) total, StandardCharsets.UTF_8);
            int start = (int) (total % ring.length);
            byte[] ordered = new byte[ring.length];
            System.arraycopy(ring, start, ordered, 0, ring.length - start);
            System.arraycopy(ring, 0, ordered, ring.length - start, start);
            return new String(ordered, StandardCharsets.UTF_8);
        }
    }
}
