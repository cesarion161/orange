package com.ai.orange.repo;

import com.ai.orange.project.WorkConventions;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.worktree.TaskBase;
import com.ai.orange.worktree.TaskBases;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The base of a task of a project's repo (remote-first spec §7.1): the repo's mirror, the
 * project's {@code base-branch} convention (else the repo's default branch, as a checkout reported
 * it or GitHub says, else {@code main}), and GitHub as {@code origin}.
 */
@Component
public class RepoTaskBases implements TaskBases {

    private final TaskRepository tasks;
    private final RepoRepository repos;
    private final Mirrors mirrors;
    private final WorkConventions conventions;

    public RepoTaskBases(TaskRepository tasks, RepoRepository repos, Mirrors mirrors, WorkConventions conventions) {
        this.tasks = tasks;
        this.repos = repos;
        this.mirrors = mirrors;
        this.conventions = conventions;
    }

    @Override
    public Optional<TaskBase> of(UUID taskId) {
        Task t = tasks.findById(taskId).orElse(null);
        if (t == null || t.repoId() == null) return Optional.empty();
        Repo repo = repos.findById(t.repoId())
                .orElseThrow(() -> new IllegalStateException("task " + taskId + " has a repo that does not exist"));
        Path mirror = mirrors.ensure(repo.key());
        String branch = conventions.baseBranch(t.projectId(), null)
                .or(() -> Optional.ofNullable(repo.defaultBranch()).filter(b -> !b.isBlank()))
                .or(() -> mirrors.defaultBranch(repo.key()))
                .orElse("main");
        return Optional.of(new TaskBase(mirror, branch, mirrors.remote(repo.key())));
    }
}
