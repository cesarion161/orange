package com.ai.orange.run;

import com.ai.orange.evidence.EvidenceDetector;
import com.ai.orange.evidence.EvidenceProperties;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.project.ProjectRepo;
import com.ai.orange.project.ProjectRepository;
import com.ai.orange.project.WorkConventions;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionRepository;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.GitException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The check of a session's work that a {@code verify} run makes (project-space spec §7): the work
 * must be committed, and every command of the project's {@code evidence} convention must pass at
 * the session's head. The commands run in a scratch checkout of that commit, so the user's working
 * tree is never touched; the first failure ends the check, because a session is waiting for it.
 *
 * <p>This reads the session's files, so it runs where they are: in-process until nodes exist
 * (spec §6.1).
 */
@Component
public class SessionChecks {

    private static final Logger log = LoggerFactory.getLogger(SessionChecks.class);

    /** How the verify executor runs one command; it owns the process, the lease and the timeout. */
    @FunctionalInterface
    interface Commands {
        Result run(Path dir, String command, Duration budget);

        record Result(int exitCode, String outputTail) {}
    }

    private final SessionRepository sessions;
    private final ProjectRepository projects;
    private final WorkConventions conventions;
    private final EvidenceRepository evidence;
    private final EvidenceProperties evidenceProps;
    private final HarnessProperties harness;

    public SessionChecks(SessionRepository sessions, ProjectRepository projects, WorkConventions conventions,
                         EvidenceRepository evidence, EvidenceProperties evidenceProps, HarnessProperties harness) {
        this.sessions = sessions;
        this.projects = projects;
        this.conventions = conventions;
        this.evidence = evidence;
        this.evidenceProps = evidenceProps;
        this.harness = harness;
    }

    /** Where a session's work is and what it integrates onto, read from its checkout. */
    record Place(Path dir, String head, String branch, String baseBranch, boolean hasOrigin, String baseRef) {}

    /** Empty with a reason when there is nothing orange can check or integrate. */
    record Located(Place place, String problem) {}

    Located locate(Session s) {
        String where = s.worktree() != null ? s.worktree() : s.repoRoot();
        if (where == null) return new Located(null, "the session is not in a git repository");
        Path dir = Path.of(where);
        if (!Files.exists(dir.resolve(".git"))) return new Located(null, "there is no checkout at " + dir);
        String branch;
        String head;
        try {
            head = Git.head(dir);
            branch = Git.exitCode(dir, Git.QUICK, "symbolic-ref", "--quiet", "HEAD") == 0
                    ? Git.run(dir, Git.QUICK, "symbolic-ref", "--short", "HEAD").strip() : null;
        } catch (GitException e) {
            return new Located(null, "git could not read " + dir + ": " + e.getMessage());
        }
        if (branch == null) return new Located(null, "the checkout at " + dir + " is on a detached head");
        boolean hasOrigin = Git.run(dir, Git.QUICK, "remote").lines().anyMatch("origin"::equals);
        String base = baseBranch(s, dir, hasOrigin);
        // The name comes from a reporter or a convention and goes to git as an argument.
        if (base != null && (base.startsWith("-")
                || Git.exitCode(dir, Git.QUICK, "check-ref-format", "--branch", base) != 0)) {
            return new Located(null, "\"" + base + "\" is not a branch name");
        }
        String baseRef = null;
        if (base != null) {
            if (hasOrigin && refExists(dir, "refs/remotes/origin/" + base)) baseRef = "origin/" + base;
            else if (refExists(dir, "refs/heads/" + base)) baseRef = base;
        }
        return new Located(new Place(dir, head, branch, baseRef == null ? null : base, hasOrigin, baseRef), null);
    }

    /** The {@code base-branch} convention, else what the reporter saw as the repo's default, else origin's. */
    private String baseBranch(Session s, Path dir, boolean hasOrigin) {
        Optional<String> named = conventions.baseBranch(s.projectId(), s.id());
        if (named.isPresent()) return named.get();
        if (s.repoRoot() != null) {
            Optional<String> reported = projects.findRepo(s.nodeId(), s.repoRoot()).map(ProjectRepo::defaultBranch)
                    .filter(b -> b != null && !b.isBlank());
            if (reported.isPresent()) return reported.get();
        }
        if (hasOrigin && Git.exitCode(dir, Git.QUICK, "symbolic-ref", "--quiet", "refs/remotes/origin/HEAD") == 0) {
            String ref = Git.run(dir, Git.QUICK, "symbolic-ref", "--short", "refs/remotes/origin/HEAD").strip();
            return ref.startsWith("origin/") ? ref.substring("origin/".length()) : ref;
        }
        return null;
    }

    static boolean refExists(Path dir, String ref) {
        return Git.exitCode(dir, Git.QUICK, "rev-parse", "--verify", "--quiet", ref) == 0;
    }

    RunOutcome.SessionCheck check(Run run, Commands commands, long deadlineNanos) {
        Session s = sessions.findById(run.attendedSessionId()).orElse(null);
        if (s == null) return RunOutcome.SessionCheck.unavailable("the session is gone");
        Located located = locate(s);
        if (located.place() == null) return RunOutcome.SessionCheck.unavailable(located.problem());
        Place p = located.place();

        Set<String> done = conventions.done(s.projectId(), s.id());
        List<EvidenceProperties.Command> required;
        try {
            required = done.contains(WorkConventions.EVIDENCE) ? conventions.evidence(s.projectId(), s.id()) : List.of();
        } catch (IllegalArgumentException e) {
            return RunOutcome.SessionCheck.unavailable(e.getMessage());
        }
        if (done.contains(WorkConventions.EVIDENCE) && required.isEmpty()) {
            return new RunOutcome.SessionCheck(RunOutcome.SessionCheck.NO_EVIDENCE, p.head(), null, p.branch(),
                    p.baseBranch(), List.of(), EvidenceDetector.detect(p.dir()), null);
        }
        String baseSha = null;
        if (p.baseRef() != null && Git.exitCode(p.dir(), Git.QUICK, "merge-base", p.baseRef(), p.head()) == 0) {
            baseSha = Git.run(p.dir(), Git.QUICK, "merge-base", p.baseRef(), p.head()).strip();
        }
        if (done.isEmpty()) return outcome(RunOutcome.SessionCheck.PASSED, p, baseSha, List.of());

        // Work that is not committed is not done, whatever else passes; it is cheap to say so first.
        List<String> dirty = Git.porcelain(p.dir());
        if (!dirty.isEmpty()) {
            String listing = tail(String.join("\n", dirty) + "\n");
            evidence.recordForSession(s.id(), run.id(), p.head(), "custom", EvidenceRunner.STATUS_COMMAND, null, listing,
                    false);
            return outcome(RunOutcome.SessionCheck.GAPS, p, baseSha,
                    List.of(new EvidenceGap("custom", EvidenceRunner.STATUS_COMMAND, null, listing)));
        }
        if (required.isEmpty()) return outcome(RunOutcome.SessionCheck.PASSED, p, baseSha, List.of());

        Path scratch = harness.runRoot().resolve(run.id().toString()).resolve("checkout");
        try {
            Files.createDirectories(scratch.getParent());
            Git.run(p.dir(), Git.SLOW, "worktree", "add", "--detach", "--quiet", scratch.toString(), p.head());
            for (EvidenceProperties.Command c : required) {
                Duration budget = Duration.ofNanos(Math.max(0, Math.min(c.timeout().toNanos(), deadlineNanos - System.nanoTime())));
                Commands.Result r = commands.run(scratch, c.command(), budget);
                boolean passed = r.exitCode() == 0;
                evidence.recordForSession(s.id(), run.id(), p.head(), c.kind(), c.command(), r.exitCode(), r.outputTail(),
                        passed);
                if (!passed) {
                    return outcome(RunOutcome.SessionCheck.GAPS, p, baseSha,
                            List.of(new EvidenceGap(c.kind(), c.command(), r.exitCode(), r.outputTail())));
                }
            }
            return outcome(RunOutcome.SessionCheck.PASSED, p, baseSha, List.of());
        } catch (IOException e) {
            throw new IllegalStateException("could not prepare " + scratch + ": " + e.getMessage(), e);
        } finally {
            removeScratch(p.dir(), scratch);
        }
    }

    private static RunOutcome.SessionCheck outcome(String status, Place p, String baseSha, List<EvidenceGap> gaps) {
        return new RunOutcome.SessionCheck(status, p.head(), baseSha, p.branch(), p.baseBranch(), gaps, List.of(), null);
    }

    private static void removeScratch(Path repo, Path scratch) {
        try {
            if (Files.exists(scratch)) Git.exitCode(repo, Git.QUICK, "worktree", "remove", "--force", scratch.toString());
            Git.exitCode(repo, Git.QUICK, "worktree", "prune");
        } catch (RuntimeException e) {
            log.warn("could not remove the scratch checkout {}: {}", scratch, e.getMessage());
        }
    }

    private String tail(String text) {
        int max = evidenceProps.outputTailChars();
        return text.length() <= max ? text : text.substring(text.length() - max);
    }
}
