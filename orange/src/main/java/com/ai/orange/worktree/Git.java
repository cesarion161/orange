package com.ai.orange.worktree;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The one place orange runs git: in a directory, with a timeout, capturing stdout and stderr
 * separately, with no repository hooks. git inherits the coordinator's environment minus orange's
 * own secrets, so pushes use the host's git credentials.
 */
public final class Git {

    /** orange's own credentials, kept out of every git process. */
    static final Set<String> ORANGE_SECRETS = Set.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY",
            "ANTHROPIC_AUTH_TOKEN", "LITELLM_MASTER_KEY", "GITHUB_WEBHOOK_SECRET", "ORANGE_GITHUB_TOKEN");

    /** For quick local commands (status, rev-parse, update-ref). */
    public static final Duration QUICK = Duration.ofSeconds(60);
    /** For commands that may talk to a remote or rewrite history (fetch, push, rebase). */
    public static final Duration SLOW = Duration.ofMinutes(3);

    private Git() {}

    /** Runs git and returns its stdout. Throws {@link GitException} on a non-zero exit or a timeout. */
    public static String run(Path dir, Duration timeout, String... args) {
        Result r = exec(dir, timeout, Integer.MAX_VALUE, args);
        if (r.exitCode != 0) throw new GitException(List.of(args), r.exitCode, join(r.stdout, r.stderr));
        return r.stdout;
    }

    /** Like {@link #run}, but keeps at most {@code maxChars} of stdout (the rest is read and dropped). */
    public static Output runCapped(Path dir, Duration timeout, int maxChars, String... args) {
        Result r = exec(dir, timeout, maxChars, args);
        if (r.exitCode != 0) throw new GitException(List.of(args), r.exitCode, join(r.stdout, r.stderr));
        return new Output(r.stdout, r.truncated);
    }

    /** Runs git and returns its exit code. Only a timeout throws. */
    public static int exitCode(Path dir, Duration timeout, String... args) {
        Result r = exec(dir, timeout, Integer.MAX_VALUE, args);
        if (r.exitCode == -1) throw new GitException(List.of(args), -1, join(r.stdout, r.stderr));
        return r.exitCode;
    }

    /** Commits on HEAD that {@code baseRef} does not have. */
    public static int commitsAhead(Path dir, String baseRef) {
        return Integer.parseInt(run(dir, QUICK, "rev-list", "--count", baseRef + "..HEAD").strip());
    }

    public static String head(Path dir) {
        return run(dir, QUICK, "rev-parse", "HEAD").strip();
    }

    /** {@code git status --porcelain}: one line per uncommitted or untracked path; empty when clean. */
    public static List<String> porcelain(Path dir) {
        return run(dir, QUICK, "status", "--porcelain").lines().filter(l -> !l.isBlank()).toList();
    }

    /**
     * git's environment: the coordinator's own, minus orange's own secrets. git needs none of them,
     * and a hook (or an alias, or a credential helper) must never see them. The host's git
     * credentials ({@code SSH_AUTH_SOCK}, {@code GH_TOKEN}, a credential helper's own config) stay.
     */
    static Map<String, String> environment(Map<String, String> host) {
        Map<String, String> env = new HashMap<>(host);
        ORANGE_SECRETS.forEach(env::remove);
        return env;
    }

    /** stdout, possibly cut at the cap the caller asked for. */
    public record Output(String text, boolean truncated) {}

    private record Result(int exitCode, String stdout, String stderr, boolean truncated) {}

    private static Result exec(Path dir, Duration timeout, int maxChars, String... args) {
        List<String> cmd = new ArrayList<>(args.length + 3);
        cmd.add("git");
        // No repository hooks: a relative core.hooksPath resolves inside a task's worktree, which an
        // agent writes, and orange's rebases, pushes and checkouts must not run agent-written code.
        cmd.add("-c");
        cmd.add("core.hooksPath=/dev/null");
        cmd.addAll(List.of(args));
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
            Map<String, String> env = pb.environment();
            Map<String, String> scrubbed = environment(env);
            env.clear();
            env.putAll(scrubbed);
            p = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start git in " + dir, e);
        }
        try {
            p.getOutputStream().close(); // git never reads stdin here
        } catch (IOException ignored) {
            // already closed
        }
        Capture out = new Capture(p.getInputStream(), maxChars);
        Capture err = new Capture(p.getErrorStream(), 64 * 1024);
        Thread outThread = Thread.ofVirtual().start(out);
        Thread errThread = Thread.ofVirtual().start(err);
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                outThread.join(1000);
                errThread.join(1000);
                return new Result(-1, out.text(), err.text() + "\n(timed out after " + timeout + ")", out.truncated);
            }
            outThread.join();
            errThread.join();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException(List.of(args), -1, "interrupted");
        }
        return new Result(p.exitValue(), out.text(), err.text(), out.truncated);
    }

    private static String join(String stdout, String stderr) {
        if (stdout == null || stdout.isBlank()) return stderr;
        if (stderr == null || stderr.isBlank()) return stdout;
        return stdout.strip() + "\n" + stderr.strip();
    }

    /** Reads a stream to its end, keeping at most {@code max} bytes (so at most {@code max} characters). */
    private static final class Capture implements Runnable {
        private final InputStream in;
        private final int max;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        volatile boolean truncated;

        Capture(InputStream in, int max) {
            this.in = in;
            this.max = max;
        }

        @Override
        public void run() {
            byte[] buf = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    int room = max - kept.size();
                    if (room > 0) kept.write(buf, 0, Math.min(room, n));
                    if (n > room) truncated = true;
                }
            } catch (IOException ignored) {
                // the process went away; keep what we have
            }
        }

        String text() {
            synchronized (kept) {
                return kept.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
