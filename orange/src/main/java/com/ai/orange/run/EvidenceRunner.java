package com.ai.orange.run;

import com.ai.orange.evidence.EvidenceProperties;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.claude.ClaudeCodeCommand;
import com.ai.orange.harness.container.ContainerRuntime;
import com.ai.orange.harness.container.ContainerSpec;
import com.ai.orange.harness.container.DockerCommand;
import com.ai.orange.json.Json;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.WorktreeService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Executes {@code verify} runs: orange itself, not an agent, checks the head of a task or of one
 * of the user's sessions. A task's worktree
 * must be at the head the run was queued for and clean, and every configured evidence command
 * must pass. Each check becomes an {@code evidence} row; the failures come back as gaps.
 *
 * <p>Commands run as {@code sh -c} in the worktree with only the harness's allowlisted
 * environment, so they never see the coordinator's own secrets.
 *
 * <p>In container mode a task's commands execute what its agent wrote, so each runs in a container
 * of its own, on a clone of the head under the run's directory: the task's worktree, in which
 * orange runs git, is never written by them. A session's commands stay on the host; they are the
 * user's own, on the user's own code.
 */
@Component
public class EvidenceRunner implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRunner.class);
    static final String STATUS_COMMAND = "git status --porcelain";
    /** How long a killed command gets to release its output pipe. */
    private static final long DRAIN_MILLIS = 2_000;
    /** How long a removed container's client gets to end before the container is removed once more. */
    private static final long CLIENT_EXIT_MILLIS = 5_000;
    /** docker's own exit code when it could not create or start a container. */
    private static final int DOCKER_ERROR = 125;

    private final RunRepository runs;
    private final EvidenceRepository evidence;
    private final WorktreeService worktrees;
    private final EvidenceProperties props;
    private final HarnessProperties harnessProps;
    private final RunsProperties runsProps;
    private final RunLifecycle lifecycle;
    private final Supplier<Map<String, String>> hostEnv;
    private final SessionChecks sessionChecks;
    private final ContainerRuntime containers;
    private final RunWorkspaces workspaces;
    private final Set<Started> live = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    @Autowired
    public EvidenceRunner(RunRepository runs, EvidenceRepository evidence, WorktreeService worktrees,
                          EvidenceProperties props, HarnessProperties harnessProps, RunsProperties runsProps,
                          RunLifecycle lifecycle, SessionChecks sessionChecks, ContainerRuntime containers,
                          RunWorkspaces workspaces) {
        this(runs, evidence, worktrees, props, harnessProps, runsProps, lifecycle, System::getenv, sessionChecks,
                containers, workspaces);
    }

    public EvidenceRunner(RunRepository runs, EvidenceRepository evidence, WorktreeService worktrees,
                          EvidenceProperties props, HarnessProperties harnessProps, RunsProperties runsProps,
                          RunLifecycle lifecycle, Supplier<Map<String, String>> hostEnv) {
        this(runs, evidence, worktrees, props, harnessProps, runsProps, lifecycle, hostEnv, null, null, null);
    }

    public EvidenceRunner(RunRepository runs, EvidenceRepository evidence, WorktreeService worktrees,
                          EvidenceProperties props, HarnessProperties harnessProps, RunsProperties runsProps,
                          RunLifecycle lifecycle, Supplier<Map<String, String>> hostEnv,
                          SessionChecks sessionChecks, ContainerRuntime containers, RunWorkspaces workspaces) {
        this.sessionChecks = sessionChecks;
        this.containers = containers;
        this.workspaces = workspaces;
        this.runs = runs;
        this.evidence = evidence;
        this.worktrees = worktrees;
        this.props = props;
        this.harnessProps = harnessProps;
        this.runsProps = runsProps;
        this.lifecycle = lifecycle;
        this.hostEnv = hostEnv;
    }

    @Override
    public Set<RunKind> kinds() {
        return EnumSet.of(RunKind.VERIFY);
    }

    /** Evidence commands running right now. */
    public int liveCommands() {
        return live.size();
    }

    /**
     * Stopping orange stops its evidence commands: each is killed (process tree included) and its
     * verify run ends {@code coordinator_restart}, recording no evidence for the killed command.
     */
    @PreDestroy
    public void shutdown() {
        stopping = true;
        if (live.isEmpty()) return;
        log.warn("stopping: killing {} running evidence command(s)", live.size());
        live.forEach(s -> s.kill().run());
        long deadline = System.nanoTime() + WorkerRunner.SHUTDOWN_GRACE.toNanos();
        while (!live.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public RunResult execute(Run run) {
        if (Boolean.FALSE.equals(lifecycle.renewLease(run))) {
            log.warn("verify run {} is no longer this worker's to run; skipping it", run.id());
            return lifecycle.fromRow(run.id());
        }
        if (lifecycle.taskEnded(run)) return lifecycle.finishCancelled(run);
        if (stopping) return lifecycle.finish(run, RunResult.failed(run.id(), "coordinator_restart"));
        String head = inputHead(run);
        lifecycle.started(run, Json.mapper().createObjectNode().put("head_sha", head));
        try {
            return run.attendedSessionId() != null ? verifySession(run) : verify(run, head);
        } catch (LeaseLost e) {
            log.warn("verify run {} lost its lease; stopping", run.id());
            return lifecycle.fromRow(run.id());
        } catch (Interrupted e) {
            return lifecycle.finish(run, RunResult.failed(run.id(), "coordinator_restart"));
        } catch (NoContainer e) {
            // No verdict on the work: nothing is recorded for the command, and nothing counts.
            log.error("verify run {}: docker could not run an evidence command: {}. Is the image {} built "
                    + "(orange/worker/build.sh) and the Docker daemon running?", run.id(), e.getMessage(),
                    containers.settings().image());
            return lifecycle.finish(run, RunResult.failed(run.id(), "container_unavailable"));
        } catch (RuntimeException e) {
            log.error("verify run {} failed: {}", run.id(), e.toString());
            return lifecycle.finish(run, RunResult.failed(run.id(), "workspace_failed"));
        }
    }

    /** A session's work is checked where it is, at whatever its head is now (see {@link SessionChecks}). */
    private RunResult verifySession(Run run) {
        if (sessionChecks == null) throw new IllegalStateException("this runner does not check sessions");
        long deadline = System.nanoTime() + runsProps.timeout(RunKind.VERIFY).toNanos();
        RunOutcome.SessionCheck outcome = sessionChecks.check(run, (dir, command, budget) -> {
            CommandResult r = runCommand(run, dir, command, budget, null);
            return new SessionChecks.Commands.Result(r.exitCode(), r.outputTail());
        }, deadline);
        runs.recordOutcome(run.id(), outcome.toJsonb());
        log.info("verify run {} for session {}: {}", run.id(), run.attendedSessionId(), outcome.status());
        return lifecycle.finish(run, new RunResult(run.id(), RunStatus.SUCCEEDED, null, outcome, null, null,
                BigDecimal.ZERO));
    }

    private RunResult verify(Run run, String head) {
        Path wt = run.taskId() == null ? null : worktrees.resolve(run.taskId());
        if (wt == null || head.isBlank() || !Files.exists(wt.resolve(".git"))) {
            log.error("verify run {}: no worktree or head to verify (task {}, head '{}')", run.id(), run.taskId(), head);
            return lifecycle.finish(run, RunResult.failed(run.id(), "workspace_failed"));
        }
        String actual = Git.head(wt);
        if (!actual.equals(head)) {
            log.info("verify run {}: worktree is at {}, not {}; nothing to verify", run.id(), actual, head);
            return conclude(run, RunOutcome.Verification.ofStale());
        }

        List<EvidenceGap> gaps = new ArrayList<>();
        List<String> dirty = Git.porcelain(wt);
        if (!dirty.isEmpty()) {
            String listing = tail(String.join("\n", dirty) + "\n");
            evidence.record(run.taskId(), run.id(), head, "custom", STATUS_COMMAND, null, listing, false);
            gaps.add(new EvidenceGap("custom", STATUS_COMMAND, null, listing));
        }

        long deadline = System.nanoTime() + runsProps.timeout(RunKind.VERIFY).toNanos();
        boolean contained = containers != null && containers.enabled();
        boolean ownClone = contained && !props.commands().isEmpty();
        Path runDir = harnessProps.runRoot().resolve(run.id().toString()).toAbsolutePath();
        Path where = wt;
        try {
            if (ownClone) {
                // The commands execute what the agent wrote: they get a copy of the head, never the
                // worktree orange runs git in.
                where = cloneOf(run, head, runDir);
                // A big branch takes its time to clone, and the first command's first beat is a heartbeat away.
                if (Boolean.FALSE.equals(lifecycle.renewLease(run))) throw new LeaseLost();
            }
            int n = 0;
            for (EvidenceProperties.Command c : props.commands()) {
                n++;
                Duration budget = Duration.ofNanos(Math.max(0, Math.min(c.timeout().toNanos(), deadline - System.nanoTime())));
                CommandResult r = runCommand(run, where, c.command(), budget,
                        contained ? DockerCommand.runName(run.id()) + "-c" + n : null);
                boolean passed = r.exitCode() == 0;
                evidence.record(run.taskId(), run.id(), head, c.kind(), c.command(), r.exitCode(), r.outputTail(), passed);
                if (!passed) gaps.add(new EvidenceGap(c.kind(), c.command(), r.exitCode(), r.outputTail()));
            }
        } finally {
            if (ownClone) workspaces.discard(run.id(), runDir);
        }
        return conclude(run, new RunOutcome.Verification(false, gaps.isEmpty(), gaps));
    }

    /** {@code <runDir>/workspace}: a clone of the task's branch, which must be at {@code head} like the worktree. */
    private Path cloneOf(Run run, String head, Path runDir) {
        Path clone;
        try {
            clone = workspaces.cloneOf(run.taskId(), runDir);
        } catch (IOException e) {
            throw new IllegalStateException("could not clone the task's branch: " + e.getMessage(), e);
        }
        // Nothing but orange's own git has touched the clone yet.
        String cloned = Git.head(clone);
        if (!cloned.equals(head)) {
            throw new IllegalStateException("the task's branch is at " + cloned + ", but its worktree at " + head);
        }
        return clone;
    }

    /** {@code input.head_sha}, or "" when the run has none. */
    static String inputHead(Run run) {
        if (run.input() == null) return "";
        try {
            return Json.mapper().readTree(run.input().data()).path("head_sha").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private RunResult conclude(Run run, RunOutcome.Verification outcome) {
        runs.recordOutcome(run.id(), outcome.toJsonb());
        return lifecycle.finish(run, new RunResult(run.id(), RunStatus.SUCCEEDED, null, outcome, null, null,
                BigDecimal.ZERO));
    }

    private record CommandResult(int exitCode, String outputTail) {}

    /**
     * Runs one command to its end, its timeout, or the end of the run's lease.
     *
     * @param container the name of the container the command gets; null = a process on the host
     */
    private CommandResult runCommand(Run run, Path dir, String command, Duration timeout, String container) {
        if (stopping) throw new Interrupted();
        Started s;
        try {
            s = container == null ? startOnHost(dir, command) : startInContainer(run, container, dir, command);
        } catch (IOException e) {
            // ContainerUnavailableException included: no docker binary, no daemon, no run network.
            if (container != null) throw new NoContainer(e.getMessage());
            return new CommandResult(-1, "could not start sh: " + e.getMessage());
        }
        Process p = s.process();
        live.add(s);
        try {
            // So the reaper of the next coordinator can kill it if this one dies mid-command.
            runs.recordPid(run.id(), p.pid());
        } catch (RuntimeException e) {
            log.warn("verify run {}: could not record the command's pid: {}", run.id(), e.getMessage());
        }
        Tail tail = new Tail(props.outputTailChars());
        Thread reader = Thread.ofVirtual().start(() -> tail.readAll(p.getInputStream()));
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextBeat = System.nanoTime() + runsProps.heartbeat().toNanos();
        boolean killed = false;
        try {
            while (true) {
                long now = System.nanoTime();
                if (now >= deadline) {
                    killed = true;
                    s.kill().run();
                    reader.join(DRAIN_MILLIS);
                    return new CommandResult(-1, tail.withNote("[orange: timed out after " + timeout + "]"));
                }
                if (now >= nextBeat) {
                    nextBeat = now + runsProps.heartbeat().toNanos();
                    if (Boolean.FALSE.equals(lifecycle.renewLease(run))) {
                        killed = true;
                        s.kill().run();
                        throw new LeaseLost();
                    }
                }
                if (p.waitFor(Math.max(Math.min(deadline, nextBeat) - now, 1_000_000), TimeUnit.NANOSECONDS)) {
                    // Killed by a shutdown: that is no verdict on the work.
                    if (stopping) throw new Interrupted();
                    // Nor is the end of a command whose container the reaper took with the run's lease
                    // (the row was finished first: a database that was away, a laptop that slept).
                    if (container != null && Boolean.FALSE.equals(lifecycle.renewLease(run))) throw new LeaseLost();
                    reader.join(DRAIN_MILLIS);
                    // docker's own exit code for "the container could not be created or started": the
                    // command never ran. 126 and 127 are also what sh says of the user's own command.
                    if (container != null && p.exitValue() == DOCKER_ERROR) throw new NoContainer(dockersReason(tail.text()));
                    return new CommandResult(p.exitValue(), tail.text());
                }
            }
        } catch (InterruptedException e) {
            killed = true;
            s.kill().run();
            Thread.currentThread().interrupt();
            throw new Interrupted();
        } finally {
            if (container != null && (killed || stopping)) {
                // A kill can arrive while the daemon is still creating the container: once more, now that the client is gone.
                removeAgain(p, container);
            } else if (container != null) {
                // The client's end is not the container's (a client that was killed, or lost its daemon):
                // nothing may still run in the clone when it is deleted.
                containers.remove(container);
            }
            live.remove(s);
        }
    }

    /** A command that was started, and how to stop it with everything it started. */
    private record Started(Process process, Runnable kill) {}

    private Started startOnHost(Path dir, String command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(ClaudeCodeCommand.passThrough(hostEnv.get(), harnessProps.claudeCode().passEnv()));
        Process p = pb.start();
        p.getOutputStream().close();
        return new Started(p, () -> kill(p));
    }

    /**
     * The command in a container that holds {@code dir} and nothing else of the host: none of
     * orange's environment, not even the allowlisted part. The process is the docker client.
     */
    private Started startInContainer(Run run, String name, Path dir, String command) throws IOException {
        ContainerSpec spec = new ContainerSpec(name,
                DockerCommand.labels(run.id(), run.kind().dbValue(), runsProps.workerId()), dir,
                List.of(new ContainerSpec.Mount(dir, false)), Map.of("HOME", containers.settings().home()), Map.of(),
                true, List.of("sh", "-c", command));
        Process p = containers.command(spec).redirectErrorStream(true).start();
        p.getOutputStream().close();
        return new Started(p, () -> {
            containers.remove(name);
            kill(p);
        });
    }

    private void removeAgain(Process client, String container) {
        boolean interrupted = Thread.interrupted();
        try {
            client.waitFor(CLIENT_EXIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        containers.remove(container);
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    /** docker's reason: its last line that is not the pointer to its own help, which comes after. */
    private static String dockersReason(String output) {
        return output.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("Run 'docker "))
                .reduce((a, b) -> b).orElse("");
    }

    private String tail(String text) {
        int max = props.outputTailChars();
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static final class LeaseLost extends RuntimeException {
        LeaseLost() {
            super(null, null, false, false);
        }
    }

    private static final class Interrupted extends RuntimeException {
        Interrupted() {
            super(null, null, false, false);
        }
    }

    /** Docker could not run a command's container; the message is docker's reason. */
    private static final class NoContainer extends RuntimeException {
        NoContainer(String why) {
            super(why, null, false, false);
        }
    }

    /** The last {@code max} characters of a stream. */
    private static final class Tail {
        private final int max;
        private final StringBuilder buf = new StringBuilder();

        Tail(int max) {
            this.max = max;
        }

        void readAll(java.io.InputStream in) {
            char[] chunk = new char[8192];
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                int n;
                while ((n = r.read(chunk)) != -1) {
                    synchronized (buf) {
                        buf.append(chunk, 0, n);
                        if (buf.length() > 2 * max) buf.delete(0, buf.length() - max);
                    }
                }
            } catch (IOException ignored) {
                // the process went away; keep what was read
            }
        }

        String text() {
            synchronized (buf) {
                return buf.length() <= max ? buf.toString() : buf.substring(buf.length() - max);
            }
        }

        /** The tail with {@code note} on its own last line, still within {@code max}. */
        String withNote(String note) {
            String suffix = "\n" + note + "\n";
            String body = text();
            int room = Math.max(0, max - suffix.length());
            if (body.length() > room) body = body.substring(body.length() - room);
            return body + suffix;
        }
    }
}
