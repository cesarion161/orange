package com.ai.orange.run;

import com.ai.orange.harness.claude.ClaudeCodeCommand;
import com.ai.orange.json.Json;
import com.ai.orange.project.WorkConventions;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionRepository;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.GitException;
import com.ai.orange.worktree.WorktreeService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Executes {@code integrate} runs in merge mode (project-space spec §8), for a task's branch or
 * for the branch of one of the user's sessions: rebase the verified
 * branch onto the base; if that changed the head, send it back for re-verification; otherwise
 * fast-forward the base (push it when there is a remote) and move the local base branch along.
 * The {@code integrate} slot is 1, so integrations never race each other; a push rejected
 * because the base moved anyway just asks for another attempt.
 */
@Component
public class Integrator implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(Integrator.class);

    private final RunRepository runs;
    private final WorktreeService worktrees;
    private final IntegrationProperties props;
    private final RunsProperties runsProps;
    private final RunLifecycle lifecycle;
    private final SessionRepository sessions;
    private final SessionChecks sessionChecks;
    private final WorkConventions conventions;

    public Integrator(RunRepository runs, WorktreeService worktrees, IntegrationProperties props,
                      RunsProperties runsProps, RunLifecycle lifecycle) {
        this(runs, worktrees, props, runsProps, lifecycle, null, null, null);
    }

    @Autowired
    public Integrator(RunRepository runs, WorktreeService worktrees, IntegrationProperties props,
                      RunsProperties runsProps, RunLifecycle lifecycle, SessionRepository sessions,
                      SessionChecks sessionChecks, WorkConventions conventions) {
        this.sessions = sessions;
        this.sessionChecks = sessionChecks;
        this.conventions = conventions;
        this.runs = runs;
        this.worktrees = worktrees;
        this.props = props;
        this.runsProps = runsProps;
        this.lifecycle = lifecycle;
    }

    @Override
    public Set<RunKind> kinds() {
        return EnumSet.of(RunKind.INTEGRATE);
    }

    @Override
    public RunResult execute(Run run) {
        if (Boolean.FALSE.equals(lifecycle.renewLease(run))) {
            log.warn("integrate run {} is no longer this worker's to run; skipping it", run.id());
            return lifecycle.fromRow(run.id());
        }
        if (ended(run)) return lifecycle.finishCancelled(run);
        String head = EvidenceRunner.inputHead(run);
        lifecycle.started(run, Json.mapper().createObjectNode().put("head_sha", head));
        RunOutcome.Integration outcome;
        try {
            outcome = integrate(run, head, System.nanoTime() + runsProps.timeout(RunKind.INTEGRATE).toNanos());
        } catch (LeaseLost e) {
            log.warn("integrate run {} lost its lease; stopping", run.id());
            return lifecycle.fromRow(run.id());
        } catch (TaskEnded e) {
            return lifecycle.finishCancelled(run);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("integrate run {} failed: {}", run.id(), e.getMessage());
            RunOutcome.Integration failed = new RunOutcome.Integration("failed", head, null, null, List.of(),
                    e.getMessage());
            runs.recordOutcome(run.id(), failed.toJsonb());
            return lifecycle.finish(run, new RunResult(run.id(), RunStatus.FAILED, "integrate_failed", failed, null,
                    null, BigDecimal.ZERO));
        }
        runs.recordOutcome(run.id(), outcome.toJsonb());
        log.info("integrate run {} for {}: {}", run.id(),
                run.taskId() != null ? "task " + run.taskId() : "session " + run.attendedSessionId(), outcome.status());
        return lifecycle.finish(run, new RunResult(run.id(), RunStatus.SUCCEEDED, null, outcome, null, null,
                BigDecimal.ZERO));
    }

    /**
     * Where one integration happens.
     *
     * @param wt         the checkout holding the verified branch
     * @param repo       any checkout of the same repository; the base branch is looked up from here
     * @param push       whether the base is pushed to origin (and so integrated onto {@code origin/<base>})
     * @param onBase     the work was done on the base branch itself (a session in the main checkout)
     * @param asBot      rebase as orange's bot (task branches) or as whoever the checkout's git config says
     */
    private record Target(Path wt, Path repo, String baseBranch, boolean hasOrigin, boolean push, boolean onBase,
                          boolean asBot) {
        String ref() {
            return push ? "origin/" + baseBranch : baseBranch;
        }

        void fetch() {
            if (hasOrigin) Git.run(repo, Git.SLOW, "fetch", "--quiet", "origin", baseBranch);
        }
    }

    private Target target(Run run) throws Exception {
        if (run.taskId() != null) {
            boolean origin = worktrees.hasOrigin();
            return new Target(worktrees.ensure(run.taskId()), worktrees.baseRepo(), worktrees.baseBranch(), origin,
                    origin && props.remotePush(), false, true);
        }
        Session s = sessions.findById(run.attendedSessionId())
                .orElseThrow(() -> new IllegalStateException("session " + run.attendedSessionId() + " does not exist"));
        SessionChecks.Located located = sessionChecks.locate(s);
        if (located.place() == null) throw new IllegalStateException(located.problem());
        SessionChecks.Place p = located.place();
        if (p.baseBranch() == null) {
            throw new IllegalStateException("no base branch is known for " + p.dir()
                    + "; set the project's base-branch convention");
        }
        return new Target(p.dir(), p.dir(), p.baseBranch(), p.hasOrigin(),
                p.hasOrigin() && conventions.remotePush(s.projectId(), s.id()), p.branch().equals(p.baseBranch()), false);
    }

    /** Whether the work this run integrates has ended or moved on, so the run must not land it. */
    private boolean ended(Run run) {
        if (run.attendedSessionId() == null) return lifecycle.taskEnded(run);
        String stop = stopEventId(run);
        return sessions.findById(run.attendedSessionId())
                .map(s -> !Session.VERIFYING.equals(s.status()) || !stop.equals(s.stopEventId())).orElse(true);
    }

    private static String stopEventId(Run run) {
        try {
            return Json.mapper().readTree(run.input().data()).path("stop_event_id").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private RunOutcome.Integration integrate(Run run, String head, long deadline) throws Exception {
        if ((run.taskId() == null && run.attendedSessionId() == null) || head.isBlank()) {
            throw new IllegalStateException("integrate run " + run.id() + " has no task or session, or no head_sha");
        }
        Target t = target(run);
        Path wt = t.wt();
        t.fetch();
        renew(run, deadline);

        if (!Git.head(wt).equals(head)) return RunOutcome.Integration.of("stale");
        if (t.onBase()) return integrateOnBase(run, t, head);
        // A session's checkout is the user's: tracked changes made since the check mean it has moved on.
        if (run.attendedSessionId() != null
                && !Git.run(wt, Git.QUICK, "status", "--porcelain", "--untracked-files=no").isBlank()) {
            return RunOutcome.Integration.of("stale");
        }

        // Pushing integrates onto origin's base; without a push, onto the local base branch.
        String target = t.ref();
        try {
            List<String> rebase = new java.util.ArrayList<>();
            if (t.asBot()) {
                rebase.addAll(List.of("-c", "user.name=" + ClaudeCodeCommand.BOT_NAME,
                        "-c", "user.email=" + ClaudeCodeCommand.BOT_EMAIL));
            }
            rebase.addAll(List.of("rebase", "--quiet", target));
            Git.run(wt, Git.SLOW, rebase.toArray(String[]::new));
        } catch (GitException e) {
            List<String> conflicted = e.timedOut() ? List.of() : conflictedFiles(wt);
            abortRebase(wt);
            if (conflicted.isEmpty()) throw e;
            return new RunOutcome.Integration("conflict", null, null, null, conflicted, null);
        }
        String rebased = Git.head(wt);
        if (!rebased.equals(head)) {
            return new RunOutcome.Integration("rebased", rebased, null, null, List.of(), null);
        }
        renew(run, deadline);
        // The last moment to stop: work cancelled, or a session the user spoke to, while this run
        // waited or rebased must not land.
        if (ended(run)) throw new TaskEnded();

        if (t.push()) {
            try {
                Git.run(wt, Git.SLOW, "push", "--quiet", "origin", "HEAD:refs/heads/" + t.baseBranch());
            } catch (GitException e) {
                if (rejectedAsNonFastForward(e)) return RunOutcome.Integration.of("base_moved");
                throw e;
            }
            // The push integrated the work; bringing the local checkout along is best effort.
            String local;
            try {
                t.fetch();
                local = updateLocalBase(t, head);
            } catch (RuntimeException e) {
                log.warn("integrate run {}: pushed, but could not move the local {}: {}", run.id(),
                        t.baseBranch(), e.getMessage());
                local = "skipped";
            }
            return new RunOutcome.Integration("integrated", head, true, "moved".equals(local) ? "skipped" : local,
                    List.of(), null);
        }

        // Without a push, the local base branch is the integration target, so it must move.
        String local = updateLocalBase(t, head);
        return switch (local) {
            case "updated" -> new RunOutcome.Integration("integrated", head, false, local, List.of(), null);
            case "moved" -> RunOutcome.Integration.of("base_moved");
            case "dirty" -> throw new IllegalStateException("the checkout of " + t.baseBranch()
                    + " has uncommitted changes, so it was left alone and nothing was integrated");
            default -> throw new IllegalStateException("could not move the local " + t.baseBranch()
                    + " branch to " + head);
        };
    }

    /**
     * A session that worked on the base branch itself (spec decision 11): orange never moves refs
     * under the user's working tree, so integrating is only the push. When origin has commits the
     * session lacks, that is for the session to sort out ({@code behind}).
     */
    private RunOutcome.Integration integrateOnBase(Run run, Target t, String head) {
        if (!t.push()) return new RunOutcome.Integration("integrated", head, false, "updated", List.of(), null);
        if (SessionChecks.refExists(t.wt(), "refs/remotes/origin/" + t.baseBranch())
                && Git.exitCode(t.wt(), Git.QUICK, "merge-base", "--is-ancestor", "origin/" + t.baseBranch(), head) != 0) {
            return RunOutcome.Integration.of("behind");
        }
        if (ended(run)) throw new TaskEnded();
        try {
            Git.run(t.wt(), Git.SLOW, "push", "--quiet", "origin", head + ":refs/heads/" + t.baseBranch());
        } catch (GitException e) {
            if (rejectedAsNonFastForward(e)) return RunOutcome.Integration.of("behind");
            throw e;
        }
        return new RunOutcome.Integration("integrated", head, true, "updated", List.of(), null);
    }

    /**
     * Moves the repo's {@code <base>} branch to {@code head}: by {@code merge --ff-only} in the
     * checkout that has it checked out (only when that checkout is clean), else by a
     * compare-and-swap {@code update-ref}.
     *
     * @return {@code updated}; {@code dirty} (its checkout has uncommitted changes, left alone);
     *         {@code moved} (it has commits {@code head} lacks); {@code skipped} (no such branch)
     */
    private String updateLocalBase(Target t, String head) {
        String base = t.baseBranch();
        Path repo = t.repo();
        Optional<String> old = localHead(repo, base);
        if (old.isEmpty()) return "skipped";
        if (old.get().equals(head)) return "updated";
        if (Git.exitCode(repo, Git.QUICK, "merge-base", "--is-ancestor", old.get(), head) != 0) return "moved";

        Optional<Path> checkout = checkoutOf(repo, base);
        if (checkout.isPresent()) {
            if (!Git.run(checkout.get(), Git.QUICK, "status", "--porcelain", "--untracked-files=no").isBlank()) {
                return "dirty";
            }
            Git.run(checkout.get(), Git.QUICK, "merge", "--ff-only", "--quiet", head);
            return "updated";
        }
        try {
            Git.run(repo, Git.QUICK, "update-ref", "refs/heads/" + base, head, old.get());
        } catch (GitException e) {
            if (!localHead(repo, base).equals(old)) return "moved";
            throw e;
        }
        return "updated";
    }

    private static Optional<String> localHead(Path repo, String branch) {
        try {
            return Optional.of(Git.run(repo, Git.QUICK, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch).strip());
        } catch (GitException e) {
            return Optional.empty();
        }
    }

    /** The worktree (main or linked) that has {@code branch} checked out, if any. */
    private static Optional<Path> checkoutOf(Path repo, String branch) {
        String current = null;
        for (String line : Git.run(repo, Git.QUICK, "worktree", "list", "--porcelain").lines().toList()) {
            if (line.startsWith("worktree ")) current = line.substring("worktree ".length());
            else if (line.equals("branch refs/heads/" + branch) && current != null) return Optional.of(Path.of(current));
        }
        return Optional.empty();
    }

    private static List<String> conflictedFiles(Path wt) {
        try {
            return Git.run(wt, Git.QUICK, "diff", "--name-only", "--diff-filter=U").lines()
                    .filter(l -> !l.isBlank()).toList();
        } catch (GitException e) {
            return List.of();
        }
    }

    private static void abortRebase(Path wt) {
        try {
            Git.exitCode(wt, Git.QUICK, "rebase", "--abort");
        } catch (GitException e) {
            log.warn("could not abort the rebase in {}: {}", wt, e.getMessage());
        }
    }

    private static boolean rejectedAsNonFastForward(GitException e) {
        String out = e.output();
        return out.contains("[rejected]") || out.contains("non-fast-forward") || out.contains("fetch first");
    }

    /** Keeps the lease between steps, and gives up once the integrate timeout has passed. */
    private void renew(Run run, long deadline) {
        if (Boolean.FALSE.equals(lifecycle.renewLease(run))) throw new LeaseLost();
        if (System.nanoTime() > deadline) {
            throw new IllegalStateException("integration took longer than " + runsProps.timeout(RunKind.INTEGRATE));
        }
    }

    private static final class LeaseLost extends RuntimeException {
        LeaseLost() {
            super(null, null, false, false);
        }
    }

    private static final class TaskEnded extends RuntimeException {
        TaskEnded() {
            super(null, null, false, false);
        }
    }
}
