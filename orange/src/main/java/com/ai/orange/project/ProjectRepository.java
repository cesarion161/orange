package com.ai.orange.project;

import static com.ai.orange.db.jooq.Tables.PROJECTS;
import static com.ai.orange.db.jooq.Tables.PROJECT_REPOS;

import com.ai.orange.db.jooq.tables.records.ProjectReposRecord;
import com.ai.orange.db.jooq.tables.records.ProjectsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private final DSLContext dsl;

    public ProjectRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Project insert(String name, String status, String summary) {
        return toDomain(dsl.insertInto(PROJECTS)
                .set(PROJECTS.NAME, name)
                .set(PROJECTS.STATUS, status)
                .set(PROJECTS.SUMMARY, summary)
                .returning().fetchOne());
    }

    public Optional<Project> findById(UUID id) {
        return dsl.selectFrom(PROJECTS).where(PROJECTS.ID.eq(id)).fetchOptional().map(ProjectRepository::toDomain);
    }

    public Optional<Project> findByName(String name) {
        return dsl.selectFrom(PROJECTS).where(PROJECTS.NAME.equalIgnoreCase(name)).limit(1).fetchOptional()
                .map(ProjectRepository::toDomain);
    }

    /** Locks the project row for the caller's transaction, so changes to its space happen one at a time. */
    public boolean lock(UUID id) {
        return dsl.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.ID.eq(id)).forUpdate().fetchOptional().isPresent();
    }

    /** Names are compared without case, so a lookup by name finds one project. */
    public boolean nameTaken(String name) {
        return dsl.fetchExists(PROJECTS, PROJECTS.NAME.equalIgnoreCase(name));
    }

    /** Every project, oldest first. */
    public List<Project> findAll() {
        return dsl.selectFrom(PROJECTS).orderBy(PROJECTS.CREATED_AT.asc(), PROJECTS.ID.asc()).fetch()
                .map(ProjectRepository::toDomain);
    }

    public void update(UUID id, String name, String status, String summary) {
        dsl.update(PROJECTS)
                .set(PROJECTS.NAME, name)
                .set(PROJECTS.STATUS, status)
                .set(PROJECTS.SUMMARY, summary)
                .where(PROJECTS.ID.eq(id))
                .execute();
    }

    public Optional<ProjectRepo> findRepo(String nodeId, String repoRoot) {
        return dsl.selectFrom(PROJECT_REPOS)
                .where(PROJECT_REPOS.NODE_ID.eq(nodeId).and(PROJECT_REPOS.REPO_ROOT.eq(repoRoot)))
                .fetchOptional().map(ProjectRepository::toDomain);
    }

    public List<ProjectRepo> repos(UUID projectId) {
        return dsl.selectFrom(PROJECT_REPOS).where(PROJECT_REPOS.PROJECT_ID.eq(projectId))
                .orderBy(PROJECT_REPOS.CREATED_AT.asc(), PROJECT_REPOS.ID.asc()).fetch()
                .map(ProjectRepository::toDomain);
    }

    /** Attaches a repo to a project, moving it when another project had it. */
    public ProjectRepo putRepo(UUID projectId, String nodeId, String repoRoot, String remoteUrl, String defaultBranch) {
        return toDomain(dsl.insertInto(PROJECT_REPOS)
                .set(PROJECT_REPOS.PROJECT_ID, projectId)
                .set(PROJECT_REPOS.NODE_ID, nodeId)
                .set(PROJECT_REPOS.REPO_ROOT, repoRoot)
                .set(PROJECT_REPOS.REMOTE_URL, remoteUrl)
                .set(PROJECT_REPOS.DEFAULT_BRANCH, defaultBranch)
                .onConflict(PROJECT_REPOS.NODE_ID, PROJECT_REPOS.REPO_ROOT)
                .doUpdate()
                .set(PROJECT_REPOS.PROJECT_ID, projectId)
                .set(PROJECT_REPOS.REMOTE_URL, org.jooq.impl.DSL.coalesce(
                        org.jooq.impl.DSL.val(remoteUrl), PROJECT_REPOS.REMOTE_URL))
                .set(PROJECT_REPOS.DEFAULT_BRANCH, org.jooq.impl.DSL.coalesce(
                        org.jooq.impl.DSL.val(defaultBranch), PROJECT_REPOS.DEFAULT_BRANCH))
                .returning().fetchOne());
    }

    static Project toDomain(ProjectsRecord r) {
        return new Project(r.getId(), r.getName(), r.getStatus(), r.getSummary(), r.getCreatedAt());
    }

    static ProjectRepo toDomain(ProjectReposRecord r) {
        return new ProjectRepo(r.getId(), r.getProjectId(), r.getNodeId(), r.getRepoRoot(), r.getRemoteUrl(),
                r.getDefaultBranch());
    }
}
