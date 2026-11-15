package com.ai.orange.repo;

import static com.ai.orange.db.jooq.Tables.PROJECT_REPOS;
import static com.ai.orange.db.jooq.Tables.REPOS;

import com.ai.orange.db.jooq.tables.records.ReposRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class RepoRepository {

    private final DSLContext dsl;

    public RepoRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Repo> findById(UUID id) {
        return dsl.selectFrom(REPOS).where(REPOS.ID.eq(id)).fetchOptional().map(RepoRepository::toDomain);
    }

    public Optional<Repo> findByKey(RepoKey key) {
        return dsl.selectFrom(REPOS).where(REPOS.KEY.eq(key.toString())).fetchOptional().map(RepoRepository::toDomain);
    }

    /** A project's repos, oldest first. */
    public List<Repo> ofProject(UUID projectId) {
        return dsl.selectFrom(REPOS).where(REPOS.PROJECT_ID.eq(projectId))
                .orderBy(REPOS.CREATED_AT.asc(), REPOS.ID.asc()).fetch().map(RepoRepository::toDomain);
    }

    /** The repo of {@code key}, inserted into {@code projectId} when it is new; an existing one stays where it is. */
    public Repo insertIfAbsent(UUID projectId, RepoKey key, String defaultBranch) {
        dsl.insertInto(REPOS)
                .set(REPOS.PROJECT_ID, projectId)
                .set(REPOS.KEY, key.toString())
                .set(REPOS.DEFAULT_BRANCH, defaultBranch)
                .onConflict(REPOS.KEY).doNothing()
                .execute();
        return findByKey(key).orElseThrow();
    }

    /** Moves a repo, and every checkout of it, to another project. */
    public void moveToProject(UUID repoId, UUID projectId) {
        dsl.update(REPOS).set(REPOS.PROJECT_ID, projectId).where(REPOS.ID.eq(repoId)).execute();
        dsl.update(PROJECT_REPOS).set(PROJECT_REPOS.PROJECT_ID, projectId).where(PROJECT_REPOS.REPO_ID.eq(repoId))
                .execute();
    }

    /** What a checkout reported as the default branch, kept when the repo has none yet. */
    public void setDefaultBranchIfNone(UUID repoId, String branch) {
        if (branch == null || branch.isBlank()) return;
        dsl.update(REPOS).set(REPOS.DEFAULT_BRANCH, branch)
                .where(REPOS.ID.eq(repoId).and(REPOS.DEFAULT_BRANCH.isNull()))
                .execute();
    }

    static Repo toDomain(ReposRecord r) {
        return new Repo(r.getId(), r.getProjectId(), RepoKey.of(r.getKey()), r.getDefaultBranch());
    }
}
