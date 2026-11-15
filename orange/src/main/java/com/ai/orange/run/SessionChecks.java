package com.ai.orange.run;

import com.ai.orange.evidence.EvidenceDetector;
import com.ai.orange.evidence.EvidenceProperties;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.json.Json;
import com.ai.orange.project.ProjectRepo;
import com.ai.orange.project.ProjectRepository;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.WorkConventions;
import com.ai.orange.repo.Mirrors;
import com.ai.orange.repo.Repo;
import com.ai.orange.repo.RepoKey;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionRepository;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.GitException;
import com.ai.orange.worktree.Remote;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The check of a session's work that a {@code verify} run makes, from GitHub (remote-first spec
 * §7.4): the work must be committed and pushed, and every command of the project's
 * {@code evidence} convention must pass at the head the session stopped at.
 *
 * <p>orange never reads the session's checkout. The reporter says at each stop what the head is
 * and what is not committed; orange fetches the repo's mirror and finds the head on the session's
 * branch there. A session that works on the base branch itself pushes to a side branch,
 * {@link #sideBranch}, instead (builder's call 11). The commands run in a clone of that head under
 * the run's directory, each in a container in container mode; the first failure ends the check,
 * because a session is waiting for it.
 */
@Component
public class SessionChecks {

    private static final Logger log = LoggerFactory.getLogger(SessionChecks.class);

    /** The gap command of work that is not on GitHub yet. */
    public static final String PUSH_COMMAND = "git push";

    /** How the verify executor runs one command; it owns the process, the container, the lease and the timeout. */
    @FunctionalInterface
    interface Commands {
        /** @param n the command's number in the check, from 1: it names the command's container */
        Result run(Path dir, int n, String command, Duration budget);

        record Result(int exitCode, String outputTail) {}
    }

    private final SessionRepository sessions;
    private final ProjectService projectService;
    private final ProjectRepository projects;
    private final Mirrors mirrors;
    private final WorkConventions conventions;
    private final EvidenceRepository evidence;
    private final EvidenceProperties evidenceProps;
    private final HarnessProperties harness;
    private final RunWorkspaces workspaces;

    public SessionChecks(SessionRepository sessions, ProjectService projectService, ProjectRepository projects,
                         Mirrors mirrors, WorkConventions conventions, EvidenceRepository evidence,
                         EvidenceProperties evidenceProps, HarnessProperties harness, RunWorkspaces workspaces) {
        this.sessions = sessions;
        this.projectService = projectService;
        this.projects = projects;
        this.mirrors = mirrors;
        this.conventions = conventions;
        this.evidence = evidence;
        this.evidenceProps = evidenceProps;
        this.harness = harness;
        this.workspaces = workspaces;
    }

    /** The branch a session on the base branch pushes its work to: {@code orange/session/<last 8 hex of its id>}. */
    public static String sideBranch(UUID sessionId) {
        String hex = sessionId.toString().replace("-", "");
        return "orange/session/" + hex.substring(hex.length() - 8);
    }

    /** The mirror's ref that keeps the head orange checks (and, after a rebase, integrates) for a session. */
    public static String checkRef(UUID sessionId) {
        return "refs/orange/sessions/" + sessionId;
    }

    /** Where a session's work is checked and integrated: the repo's mirror, the session's branch, the base. */
    public record Place(RepoKey key, Path mirror, String branch, String baseBranch, Remote remote) {
        /** The session works on the base branch itself. */
        public boolean onBase() {
            return branch.equals(baseBranch);
        }
    }

    /**
     * Empty with a reason when there is nothing orange can check or integrate.
     *
     * @param notOnGithub the session's repo has no GitHub remote, so orange cannot fetch its work at all
     */
    record Located(Place place, String problem, boolean notOnGithub) {
        static Located problem(String why) {
            return new Located(null, why, false);
        }
    }

    /** The mirror of the session's repo, when its repo is on GitHub (no clone, no fetch). */
    public Optional<Path> mirrorOf(Session s) {
        return projectService.repoOfCheckout(s.nodeId(), s.repoRoot()).map(r -> mirrors.path(r.key()));
    }

    /** The session's place; clones the mirror when there is none yet, does not fetch it. */
    Located locate(Session s) {
        Optional<Repo> repo = projectService.repoOfCheckout(s.nodeId(), s.repoRoot());
        if (repo.isEmpty()) {
            return new Located(null, s.repoRoot() == null ? "the session is not in a git repository"
                    : "the repo at " + s.repoRoot() + " has no GitHub remote, so orange cannot fetch its work", true);
        }
        if (s.branch() == null) return Located.problem("the session is on a detached head");
        RepoKey key = repo.get().key();
        Path mirror;
        try {
            mirror = mirrors.ensure(key);
        } catch (GitException e) {
            return Located.problem("could not clone " + key + " from GitHub: " + e.getMessage());
        }
        String base = baseBranch(s, repo.get(), mirror);
        // The names come from a reporter or a convention and go to git as arguments.
        for (String name : new String[] {s.branch(), base}) {
            if (name != null && (name.startsWith("-")
                    || Git.exitCode(mirror, Git.QUICK, "check-ref-format", "--branch", name) != 0)) {
                return Located.problem("\"" + name + "\" is not a branch name");
            }
        }
        return new Located(new Place(key, mirror, s.branch(), base, mirrors.remote(key)), null, false);
    }

    /**
     * The {@code base-branch} convention, else the repo's default branch as a checkout reported it,
     * else GitHub's ({@code origin/HEAD} in the mirror), else {@code main} or {@code master} when the
     * repo has one.
     */
    private String baseBranch(Session s, Repo repo, Path mirror) {
        Optional<String> named = conventions.baseBranch(s.projectId(), s.id());
        if (named.isPresent()) return named.get();
        if (repo.defaultBranch() != null && !repo.defaultBranch().isBlank()) return repo.defaultBranch();
        Optional<String> reported = projects.findRepo(s.nodeId(), s.repoRoot()).map(ProjectRepo::defaultBranch)
                .filter(b -> b != null && !b.isBlank());
        if (reported.isPresent()) return reported.get();
        Optional<String> github = mirrors.defaultBranch(repo.key());
        if (github.isPresent()) return github.get();
        for (String usual : List.of("main", "master")) {
            if (refExists(mirror, "refs/remotes/origin/" + usual)) return usual;
        }
        return null;
    }

    static boolean refExists(Path dir, String ref) {
        return Git.exitCode(dir, Git.QUICK, "rev-parse", "--verify", "--quiet", ref) == 0;
    }

    private static boolean hasCommit(Path repo, String sha) {
        return Git.exitCode(repo, Git.QUICK, "cat-file", "-e", sha + "^{commit}") == 0;
    }

    private static boolean isAncestor(Path repo, String ancestor, String of) {
        return Git.exitCode(repo, Git.QUICK, "merge-base", "--is-ancestor", ancestor, of) == 0;
    }

    RunOutcome.SessionCheck check(Run run, Commands commands, long deadlineNanos) {
        Session s = sessions.findById(run.attendedSessionId()).orElse(null);
        if (s == null) return RunOutcome.SessionCheck.unavailable("the session is gone");
        Located located = locate(s);
        if (located.notOnGithub()) {
            return new RunOutcome.SessionCheck(RunOutcome.SessionCheck.NO_REMOTE, null, null, s.branch(), null,
                    List.of(), List.of(), located.problem());
        }
        if (located.place() == null) return RunOutcome.SessionCheck.unavailable(located.problem());
        Place p = located.place();

        // After a rebase orange checks its own commit, which is in the mirror and nowhere else.
        JsonNode in = input(run);
        boolean rebased = in.path("rebased").asBoolean(false) && in.hasNonNull("head_sha");
        String head = rebased ? in.path("head_sha").asText() : s.headSha();
        if (head == null || head.isBlank()) {
            return RunOutcome.SessionCheck.unavailable("the session's stop did not say what its head is; update the "
                    + "orange plugin (0.2.2 or later)");
        }

        Set<String> done = conventions.done(s.projectId(), s.id());
        List<EvidenceProperties.Command> required;
        try {
            required = done.contains(WorkConventions.EVIDENCE) ? conventions.evidence(s.projectId(), s.id()) : List.of();
        } catch (IllegalArgumentException e) {
            return RunOutcome.SessionCheck.unavailable(e.getMessage());
        }
        try {
            mirrors.fetch(p.key());
        } catch (GitException e) {
            return RunOutcome.SessionCheck.unavailable("could not fetch " + p.key() + " from GitHub: " + e.getMessage());
        }
        String baseRef = p.baseBranch() != null && refExists(p.mirror(), "refs/remotes/origin/" + p.baseBranch())
                ? "origin/" + p.baseBranch() : null;
        Path runDir = harness.runRoot().resolve(run.id().toString()).toAbsolutePath();

        if (done.contains(WorkConventions.EVIDENCE) && required.isEmpty()) {
            // What the repo suggests: at the session's head when GitHub has it, else at GitHub's base.
            String from = baseRef != null ? "refs/remotes/" + baseRef : null;
            if (hasCommit(p.mirror(), head)) {
                Git.run(p.mirror(), Git.QUICK, "update-ref", checkRef(s.id()), head);
                from = checkRef(s.id());
            }
            List<String> detected = from == null ? List.of() : detect(run, p.mirror(), from, runDir);
            return new RunOutcome.SessionCheck(RunOutcome.SessionCheck.NO_EVIDENCE, head, null, p.branch(),
                    p.baseBranch(), List.of(), detected, null);
        }
        if (done.isEmpty()) return outcome(RunOutcome.SessionCheck.PASSED, p, head, null, List.of());

        // Work that is not committed is not done, whatever else passes; it is cheap to say so first.
        List<String> dirty = rebased || s.dirtyFiles() == null ? List.of() : s.dirtyFiles();
        if (!dirty.isEmpty()) {
            String listing = tail(String.join("\n", dirty) + "\n");
            evidence.recordForSession(s.id(), run.id(), head, "custom", EvidenceRunner.STATUS_COMMAND, null, listing,
                    false);
            return outcome(RunOutcome.SessionCheck.GAPS, p, head, null,
                    List.of(new EvidenceGap("custom", EvidenceRunner.STATUS_COMMAND, null, listing)));
        }
        // Committed is all this project asks: nothing is fetched, run or integrated.
        if (!done.contains(WorkConventions.EVIDENCE) && !done.contains(WorkConventions.INTEGRATED)) {
            return outcome(RunOutcome.SessionCheck.PASSED, p, head, null, List.of());
        }

        boolean known = hasCommit(p.mirror(), head);
        boolean nothingNew = known && baseRef != null && isAncestor(p.mirror(), head, baseRef);
        if (!rebased && !nothingNew) {
            String pushedTo = p.onBase() ? sideBranch(s.id()) : p.branch();
            String pushedRef = "refs/remotes/origin/" + pushedTo;
            // Pushed further since the stop is fine: the head the session stopped at is what is checked.
            boolean pushed = known && refExists(p.mirror(), pushedRef) && isAncestor(p.mirror(), head, pushedRef);
            if (!pushed) {
                String why = pushGap(p, s, head);
                evidence.recordForSession(s.id(), run.id(), head, "custom", PUSH_COMMAND, null, why, false);
                return outcome(RunOutcome.SessionCheck.GAPS, p, head, null,
                        List.of(new EvidenceGap("custom", PUSH_COMMAND, null, why)));
            }
        }
        Git.run(p.mirror(), Git.QUICK, "update-ref", checkRef(s.id()), head);
        String baseSha = null;
        if (baseRef != null && Git.exitCode(p.mirror(), Git.QUICK, "merge-base", baseRef, head) == 0) {
            baseSha = Git.run(p.mirror(), Git.QUICK, "merge-base", baseRef, head).strip();
        }
        if (required.isEmpty()) return outcome(RunOutcome.SessionCheck.PASSED, p, head, baseSha, List.of());

        Path where;
        try {
            where = workspaces.cloneRef(p.mirror(), checkRef(s.id()), runDir);
        } catch (IOException e) {
            throw new IllegalStateException("could not clone " + head + " under " + runDir + ": " + e.getMessage(), e);
        }
        try {
            int n = 0;
            for (EvidenceProperties.Command c : required) {
                n++;
                Duration budget = Duration.ofNanos(Math.max(0, Math.min(c.timeout().toNanos(), deadlineNanos - System.nanoTime())));
                Commands.Result r = commands.run(where, n, c.command(), budget);
                boolean passed = r.exitCode() == 0;
                evidence.recordForSession(s.id(), run.id(), head, c.kind(), c.command(), r.exitCode(), r.outputTail(),
                        passed);
                if (!passed) {
                    return outcome(RunOutcome.SessionCheck.GAPS, p, head, baseSha,
                            List.of(new EvidenceGap(c.kind(), c.command(), r.exitCode(), r.outputTail())));
                }
            }
            return outcome(RunOutcome.SessionCheck.PASSED, p, head, baseSha, List.of());
        } finally {
            workspaces.discard(run.id(), runDir);
        }
    }

    /** What the session is told when the head it stopped at is not on GitHub. */
    private static String pushGap(Place p, Session s, String head) {
        String sha = head.substring(0, Math.min(10, head.length()));
        if (p.onBase()) {
            String side = sideBranch(s.id());
            return "This session works on `" + p.baseBranch() + "` itself, and orange checks only what it can fetch "
                    + "from GitHub, never the base it is to move. Push this work to a side branch "
                    + "(`git push origin HEAD:" + side + "`) and stop again: orange checks it there, moves `"
                    + p.baseBranch() + "` to it and deletes the side branch.";
        }
        return "orange checks what is on GitHub, and `" + p.branch() + "` there does not have this session's head ("
                + sha + "). Push it (`git push origin " + p.branch() + "`) and stop again.";
    }

    /** {@link EvidenceDetector} on a clone of {@code ref}; nothing when it cannot be read. */
    private List<String> detect(Run run, Path mirror, String ref, Path runDir) {
        try {
            return EvidenceDetector.detect(workspaces.cloneRef(mirror, ref, runDir));
        } catch (IOException | RuntimeException e) {
            log.warn("verify run {}: could not read {} to suggest evidence commands: {}", run.id(), ref, e.toString());
            return List.of();
        } finally {
            workspaces.discard(runDir);
        }
    }

    private static RunOutcome.SessionCheck outcome(String status, Place p, String head, String baseSha,
                                                   List<EvidenceGap> gaps) {
        return new RunOutcome.SessionCheck(status, head, baseSha, p.branch(), p.baseBranch(), gaps, List.of(), null);
    }

    private static JsonNode input(Run run) {
        if (run.input() == null) return Json.mapper().createObjectNode();
        try {
            return Json.mapper().readTree(run.input().data());
        } catch (Exception e) {
            return Json.mapper().createObjectNode();
        }
    }

    private String tail(String text) {
        int max = evidenceProps.outputTailChars();
        return text.length() <= max ? text : text.substring(text.length() - max);
    }
}
