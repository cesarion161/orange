package com.ai.orange.run;

import com.ai.orange.evidence.EvidenceProperties;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.claude.ClaudeCodeCommand;
import com.ai.orange.json.Json;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.WorktreeService;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Executes {@code verify} runs: orange itself, not an agent, checks the task's head. The worktree
 * must be at the head the run was queued for and clean, and every configured evidence command
 * must pass. Each check becomes an {@code evidence} row; the failures come back as gaps.
 *
 * <p>Commands run as {@code sh -c} in the worktree with only the harness's allowlisted
 * environment, so they never see the coordinator's own secrets.
 */
@Component
public class EvidenceRunner implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRunner.class);
    static final String STATUS_COMMAND = "git status --porcelain";
    /** How long a killed command gets to release its output pipe. */
    private static final long DRAIN_MILLIS = 2_000;

    private final RunRepository runs;
    private final EvidenceRepository evidence;
    private final WorktreeService worktrees;
    private final EvidenceProperties props;
    private final HarnessProperties harnessProps;
    private final RunsProperties runsProps;
    private final RunLifecycle lifecycle;
    private final Supplier<Map<String, String>> hostEnv;

    @Autowired
    public EvidenceRunner(RunRepository runs, EvidenceRepository evidence, WorktreeService worktrees,
                          EvidenceProperties props, HarnessProperties harnessProps, RunsProperties runsProps,
                          RunLifecycle lifecycle) {
        this(runs, evidence, worktrees, props, harnessProps, runsProps, lifecycle, System::getenv);
    }

    public EvidenceRunner(RunRepository runs, EvidenceRepository evidence, WorktreeService worktrees,
                          EvidenceProperties props, HarnessProperties harnessProps, RunsProperties runsProps,
                          RunLifecycle lifecycle, Supplier<Map<String, String>> hostEnv) {
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

    @Override
    public RunResult execute(Run run) {
        if (Boolean.FALSE.equals(lifecycle.renewLease(run))) {
            log.warn("verify run {} is no longer this worker's to run; skipping it", run.id());
            return lifecycle.fromRow(run.id());
        }
        String head = inputHead(run);
        lifecycle.started(run, Json.mapper().createObjectNode().put("head_sha", head));
        try {
            return verify(run, head);
        } catch (LeaseLost e) {
            log.warn("verify run {} lost its lease; stopping", run.id());
            return lifecycle.fromRow(run.id());
        } catch (Interrupted e) {
            return lifecycle.finish(run, RunResult.failed(run.id(), "coordinator_restart"));
        } catch (RuntimeException e) {
            log.error("verify run {} failed: {}", run.id(), e.toString());
            return lifecycle.finish(run, RunResult.failed(run.id(), "workspace_failed"));
        }
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
        for (EvidenceProperties.Command c : props.commands()) {
            Duration budget = Duration.ofNanos(Math.max(0, Math.min(c.timeout().toNanos(), deadline - System.nanoTime())));
            CommandResult r = runCommand(run, wt, c.command(), budget);
            boolean passed = r.exitCode() == 0;
            evidence.record(run.taskId(), run.id(), head, c.kind(), c.command(), r.exitCode(), r.outputTail(), passed);
            if (!passed) gaps.add(new EvidenceGap(c.kind(), c.command(), r.exitCode(), r.outputTail()));
        }
        return conclude(run, new RunOutcome.Verification(false, gaps.isEmpty(), gaps));
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

    private CommandResult runCommand(Run run, Path wt, String command, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command).directory(wt.toFile()).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(ClaudeCodeCommand.passThrough(hostEnv.get(), harnessProps.claudeCode().passEnv()));
        Process p;
        try {
            p = pb.start();
            p.getOutputStream().close();
        } catch (IOException e) {
            return new CommandResult(-1, "could not start sh: " + e.getMessage());
        }
        Tail tail = new Tail(props.outputTailChars());
        Thread reader = Thread.ofVirtual().start(() -> tail.readAll(p.getInputStream()));
        long deadline = System.nanoTime() + timeout.toNanos();
        long nextBeat = System.nanoTime() + runsProps.heartbeat().toNanos();
        try {
            while (true) {
                long now = System.nanoTime();
                if (now >= deadline) {
                    kill(p);
                    reader.join(DRAIN_MILLIS);
                    return new CommandResult(-1, tail.withNote("[orange: timed out after " + timeout + "]"));
                }
                if (now >= nextBeat) {
                    nextBeat = now + runsProps.heartbeat().toNanos();
                    if (Boolean.FALSE.equals(lifecycle.renewLease(run))) {
                        kill(p);
                        throw new LeaseLost();
                    }
                }
                if (p.waitFor(Math.max(Math.min(deadline, nextBeat) - now, 1_000_000), TimeUnit.NANOSECONDS)) {
                    reader.join(DRAIN_MILLIS);
                    return new CommandResult(p.exitValue(), tail.text());
                }
            }
        } catch (InterruptedException e) {
            kill(p);
            Thread.currentThread().interrupt();
            throw new Interrupted();
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
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
