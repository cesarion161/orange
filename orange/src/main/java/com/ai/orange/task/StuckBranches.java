package com.ai.orange.task;

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

    public StuckBranches(RepoRepository repos, Mirrors mirrors, WorktreeService worktrees, WorkConventions conventions,
                         IntegrationProperties integration, TaskEventRepository events) {
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
     * Pushes the task's branch to GitHub as {@link #branchName}, for a task of a repo whose project
     * pushes ({@code remote-push}) and whose branch has commits, and records {@code branch_pushed}.
     *
     * @return the branch pushed; empty when there was nothing to push or the push failed (logged)
     */
    public Optional<String> push(Task task) {
        if (task.repoId() == null || task.projectId() == null) return Optional.empty();
        try {
            if (!integration.remotePush() || !conventions.remotePush(task.projectId(), null)) return Optional.empty();
            Repo repo = repos.findById(task.repoId()).orElse(null);
            Path wt = worktrees.resolve(task.id());
            if (repo == null || !Files.isDirectory(wt)) return Optional.empty();
            if (Git.commitsAhead(wt, worktrees.base(task.id()).ref()) == 0) return Optional.empty();
            String head = Git.head(wt);
            String branch = branchName(task.id());
            // Forced: a task stuck again after a rebase has a branch that no longer fast-forwards.
            mirrors.remote(repo.key()).push(wt, "+refs/heads/orange/" + task.id() + ":refs/heads/" + branch);
            events.append(task.id(), ACTOR, PUSHED, JSONB.valueOf(Json.mapper().createObjectNode()
                    .put("branch", branch).put("head_sha", head).put("repo", repo.key().toString()).toString()));
            log.info("task {} waits for a person: pushed its branch as {} to {}", task.id(), branch, repo.key());
            return Optional.of(branch);
        } catch (RuntimeException e) {
            log.warn("task {}: could not push its branch for the user to look at: {}", task.id(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deletes the task's branch from GitHub when {@link #push} put it there and it has not been deleted
     * since; records {@code branch_deleted}. Failures are logged.
     */
    public void delete(Task task) {
        if (task.repoId() == null) return;
        try {
            Optional<TaskEvent> pushed = events.findLatestOfType(task.id(), PUSHED);
            if (pushed.isEmpty()) return;
            Optional<TaskEvent> deleted = events.findLatestOfType(task.id(), DELETED);
            if (deleted.isPresent() && deleted.get().id() > pushed.get().id()) return;
            Repo repo = repos.findById(task.repoId()).orElse(null);
            if (repo == null) return;
            String branch = branchName(task.id());
            mirrors.remote(repo.key()).push(mirrors.ensure(repo.key()), ":refs/heads/" + branch);
            events.append(task.id(), ACTOR, DELETED, JSONB.valueOf(Json.mapper().createObjectNode()
                    .put("branch", branch).put("repo", repo.key().toString()).toString()));
            log.info("task {}: deleted its branch {} from {}", task.id(), branch, repo.key());
        } catch (RuntimeException e) {
            log.warn("task {}: could not delete its branch {} from GitHub: {}", task.id(), branchName(task.id()),
                    e.getMessage());
        }
    }
}
