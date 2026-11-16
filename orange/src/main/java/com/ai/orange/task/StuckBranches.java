package com.ai.orange.task;

import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.json.Json;
import com.ai.orange.project.WorkConventions;
import com.ai.orange.repo.Mirrors;
import com.ai.orange.repo.Repo;
import com.ai.orange.repo.RepoRepository;
import com.ai.orange.run.IntegrationProperties;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.WorktreeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A stuck task's branch on GitHub (remote-first spec, builder's call 13). Task branches stay in the
 * mirror, except the branch of a task that waits for a person: it is pushed as
 * {@code orange/task/<short id>} so the user can look at it from the laptop, and deleted once the task
 * is integrated or cancelled.
 *
 * <p>A task whose most recent diff review found gaps is not pushed: code a reviewer rejected never
 * reaches GitHub, where the repo's CI could run it.
 *
 * <p>The branch is the user's to look at, and to add to: a push leases the branch at the head orange
 * pushed last ({@code --force-with-lease}, the SHA its {@code branch_pushed} event recorded), and so
 * does the delete, so a commit the user pushed there survives both. A branch orange never pushed is
 * pushed without force, as git does by default.
 *
 * <p>Both go through {@link Mirrors#remote}, so the owner's token reaches only that one git command.
 * The push runs in the task's worktree, which is orange's own (a container's clone never pushes); the
 * delete runs in the repo's mirror, since the worktree may be gone by then. Neither ever fails its
 * caller: a branch the user cannot see is worse than none, but never worth a lost attention item.
 */
@Component
public class StuckBranches {

    private static final Logger log = LoggerFactory.getLogger(StuckBranches.class);

    static final String PUSHED = "branch_pushed";
    static final String DELETED = "branch_deleted";
    private static final String ACTOR = "orange";

    private final RepoRepository repos;
    private final Mirrors mirrors;
    private final WorktreeService worktrees;
    private final WorkConventions conventions;
    private final IntegrationProperties integration;
    private final TaskEventRepository events;
    private final EvidenceRepository evidence;

    public StuckBranches(RepoRepository repos, Mirrors mirrors, WorktreeService worktrees, WorkConventions conventions,
                         IntegrationProperties integration, TaskEventRepository events, EvidenceRepository evidence) {
        this.evidence = evidence;
        this.repos = repos;
        this.mirrors = mirrors;
        this.worktrees = worktrees;
        this.conventions = conventions;
        this.integration = integration;
        this.events = events;
    }

    /** {@code orange/task/<last 8 hex of the task's id>}. */
    public static String branchName(UUID taskId) {
        return "orange/task/" + Task.shortId(taskId);
    }

    /**
     * What {@link #push} did: the branch it pushed, or why it pushed none on purpose (for the user to
     * read); both null when there was nothing to push or the push failed.
     */
    public record Pushed(String branch, String withheld) {
        static final Pushed NONE = new Pushed(null, null);
    }

    /**
     * Pushes the task's branch to GitHub as {@link #branchName}, for a task of a repo whose project
     * pushes ({@code remote-push}), whose branch has commits and whose last diff review did not find
     * gaps, and records {@code branch_pushed} with the head it pushed.
     */
    public Pushed push(Task task) {
        if (task.repoId() == null || task.projectId() == null) return Pushed.NONE;
        try {
            if (!integration.remotePush() || !conventions.remotePush(task.projectId(), null)) return Pushed.NONE;
            Repo repo = repos.findById(task.repoId()).orElse(null);
            Path wt = worktrees.resolve(task.id());
            if (repo == null || !Files.isDirectory(wt)) return Pushed.NONE;
            if (Git.commitsAhead(wt, worktrees.base(task.id()).ref()) == 0) return Pushed.NONE;
            if (evidence.lastDiffReviewFoundGaps(task.id())) {
                log.info("task {} waits for a person; its branch stays off GitHub: its last review found gaps", task.id());
                return new Pushed(null, "its last diff review found gaps, and the repo's CI could run it there");
            }
            String head = Git.head(wt);
            String branch = branchName(task.id());
            String ref = "refs/heads/" + branch;
            // A task stuck again after a rebase has a branch that no longer fast-forwards: the lease
            // replaces it only while it is where orange left it.
            List<String> args = new ArrayList<>();
            lastPushed(task.id()).ifPresent(sha -> args.add("--force-with-lease=" + ref + ":" + sha));
            args.add("refs/heads/orange/" + task.id() + ":" + ref);
            mirrors.remote(repo.key()).push(wt, args.toArray(String[]::new));
            events.append(task.id(), ACTOR, PUSHED, JSONB.valueOf(Json.mapper().createObjectNode()
                    .put("branch", branch).put("head_sha", head).put("repo", repo.key().toString()).toString()));
            log.info("task {} waits for a person: pushed its branch as {} to {}", task.id(), branch, repo.key());
            return new Pushed(branch, null);
        } catch (RuntimeException e) {
            log.warn("task {}: could not push its branch for the user to look at: {}", task.id(), e.getMessage());
            return Pushed.NONE;
        }
    }

    /**
     * The head orange last pushed as the task's branch, when the branch is still orange's on GitHub: a
     * {@code branch_pushed} event with its SHA and no {@code branch_deleted} after it.
     */
    private Optional<String> lastPushed(UUID taskId) {
        Optional<TaskEvent> pushed = events.findLatestOfType(taskId, PUSHED);
        if (pushed.isEmpty()) return Optional.empty();
        Optional<TaskEvent> deleted = events.findLatestOfType(taskId, DELETED);
        if (deleted.isPresent() && deleted.get().id() > pushed.get().id()) return Optional.empty();
        try {
            String sha = Json.mapper().readTree(pushed.get().payload().data()).path("head_sha").asText("");
            return sha.isBlank() ? Optional.empty() : Optional.of(sha);
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Deletes the task's branch from GitHub when {@link #push} put it there and it has not been deleted
     * since, and only while it is still at the head orange pushed: commits the user added keep it.
     * Records {@code branch_deleted}. Failures are logged.
     */
    public void delete(Task task) {
        if (task.repoId() == null) return;
        try {
            Optional<String> pushed = lastPushed(task.id());
            if (pushed.isEmpty()) return;
            Repo repo = repos.findById(task.repoId()).orElse(null);
            if (repo == null) return;
            String branch = branchName(task.id());
            String ref = "refs/heads/" + branch;
            mirrors.remote(repo.key()).push(mirrors.ensure(repo.key()),
                    "--force-with-lease=" + ref + ":" + pushed.get(), ":" + ref);
            events.append(task.id(), ACTOR, DELETED, JSONB.valueOf(Json.mapper().createObjectNode()
                    .put("branch", branch).put("repo", repo.key().toString()).toString()));
            log.info("task {}: deleted its branch {} from {}", task.id(), branch, repo.key());
        } catch (RuntimeException e) {
            log.warn("task {}: could not delete its branch {} from GitHub: {}", task.id(), branchName(task.id()),
                    e.getMessage());
        }
    }
}
