package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.TASKS;

import com.ai.orange.db.jooq.tables.records.TasksRecord;
import com.ai.orange.task.exception.IllegalStatusTransitionException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TaskRepository {

    private final DSLContext dsl;

    public TaskRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Task insert(TaskDef def) {
        TasksRecord rec = dsl.insertInto(TASKS)
                .set(TASKS.TITLE, def.title())
                .set(TASKS.DESCRIPTION, def.description())
                .set(TASKS.ROLE, def.role())
                .set(TASKS.PRIORITY, def.priorityOrDefault())
                .set(TASKS.METADATA, def.metadataOrEmpty())
                // status defaults to 'pending' from the schema
                .returning()
                .fetchOne();
        return toDomain(rec);
    }

    public Optional<Task> findById(UUID id) {
        return dsl.selectFrom(TASKS).where(TASKS.ID.eq(id)).fetchOptional().map(TaskRepository::toDomain);
    }

    /**
     * Atomically transitions a task's status. Returns true if the row was
     * updated; false if the current status no longer matches {@code expected}.
     * Throws if the transition isn't allowed by the FSM.
     */
    @Transactional
    public boolean transitionStatus(UUID id, TaskStatus expected, TaskStatus next) {
        if (!expected.allowedNext().contains(next)) {
            throw new IllegalStatusTransitionException(id, expected, next);
        }
        return dsl.update(TASKS)
                .set(TASKS.STATUS, next.dbValue())
                .where(TASKS.ID.eq(id).and(TASKS.STATUS.eq(expected.dbValue())))
                .execute() > 0;
    }

    /**
     * Operator retry: {@code expected} (FAILED or NEEDS_HUMAN) → READY with the failure count
     * reset, so the next run starts on the task's base tier again. False if the status moved.
     */
    @Transactional
    public boolean reopen(UUID id, TaskStatus expected) {
        if (!expected.allowedNext().contains(TaskStatus.READY)) {
            throw new IllegalStatusTransitionException(id, expected, TaskStatus.READY);
        }
        return dsl.update(TASKS)
                .set(TASKS.STATUS, TaskStatus.READY.dbValue())
                .set(TASKS.FAILURES, 0)
                .where(TASKS.ID.eq(id).and(TASKS.STATUS.eq(expected.dbValue())))
                .execute() > 0;
    }

    /**
     * Used by {@link TaskService#createGraph} to flip a freshly inserted task
     * with no dependencies from {@code pending} to {@code ready}. Idempotent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markReadyIfPending(UUID id) {
        return dsl.update(TASKS)
                .set(TASKS.STATUS, TaskStatus.READY.dbValue())
                .where(TASKS.ID.eq(id).and(TASKS.STATUS.eq(TaskStatus.PENDING.dbValue())))
                .execute() > 0;
    }

    /**
     * Counts one more model failure against the task (routing escalates on the count, the reactor
     * gives up on it). Returns the new count, or empty if the task does not exist.
     */
    public java.util.Optional<Integer> incrementFailures(UUID id) {
        return dsl.update(TASKS)
                .set(TASKS.FAILURES, TASKS.FAILURES.plus(1))
                .where(TASKS.ID.eq(id))
                .returningResult(TASKS.FAILURES)
                .fetchOptional(TASKS.FAILURES);
    }

    /** Records the branch head that verification and integration work from. */
    public boolean recordHead(UUID id, String branch, String headSha) {
        return dsl.update(TASKS)
                .set(TASKS.BRANCH, branch)
                .set(TASKS.HEAD_SHA, headSha)
                .where(TASKS.ID.eq(id))
                .execute() > 0;
    }

    /** PR integration mode (a later phase): stores where the task's PR lives. */
    public boolean recordPublished(UUID id, String repo, String branch, int prNumber, String prUrl, String headSha) {
        return dsl.update(TASKS)
                .set(TASKS.REPO, repo)
                .set(TASKS.BRANCH, branch)
                .set(TASKS.PR_NUMBER, prNumber)
                .set(TASKS.PR_URL, prUrl)
                .set(TASKS.HEAD_SHA, headSha)
                .where(TASKS.ID.eq(id))
                .execute() > 0;
    }

    /**
     * Recent tasks, newest first. Optional status filter — pass {@code null}
     * to get every status. Caller-provided limit is clamped to {@code 200} so
     * an over-eager UI can't load 50k rows.
     */
    public List<Task> findRecent(TaskStatus statusOrNull, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        var query = dsl.selectFrom(TASKS);
        var condition = statusOrNull == null
                ? null
                : TASKS.STATUS.eq(statusOrNull.dbValue());
        return (condition == null
                ? query.orderBy(TASKS.CREATED_AT.desc()).limit(safeLimit)
                : query.where(condition).orderBy(TASKS.CREATED_AT.desc()).limit(safeLimit))
                .fetch()
                .map(TaskRepository::toDomain);
    }

    /** Tasks in {@code status}, optionally for one role, longest-waiting first. */
    public List<Task> findByStatus(TaskStatus status, String roleOrNull) {
        var condition = TASKS.STATUS.eq(status.dbValue());
        if (roleOrNull != null && !roleOrNull.isBlank()) condition = condition.and(TASKS.ROLE.eq(roleOrNull));
        return dsl.selectFrom(TASKS).where(condition).orderBy(TASKS.UPDATED_AT.asc())
                .fetch()
                .map(TaskRepository::toDomain);
    }

    public int countByStatus(TaskStatus status) {
        return dsl.fetchCount(TASKS, TASKS.STATUS.eq(status.dbValue()));
    }

    /**
     * Terminal tasks (integrated / failed / cancelled) whose worktree hasn't
     * been swept yet — used by the post-merge worktree disposer. We treat
     * {@code metadata.worktree_removed_at = null} as the live marker; the
     * sweeper stamps it once disposal succeeds so re-runs become no-ops.
     */
    public List<Task> findTerminalWithLiveWorktree() {
        return dsl.selectFrom(TASKS)
                .where("status IN ('integrated','failed','cancelled')"
                        + " AND metadata->>'worktree_removed_at' IS NULL")
                .orderBy(TASKS.UPDATED_AT.asc())
                .fetch()
                .map(TaskRepository::toDomain);
    }

    /**
     * Look up a task by its pull request. Rows migrated from v1 may have no {@code repo}, so a
     * null repo matches any repository (orange serves one target repo per deployment).
     */
    public Optional<Task> findByPullRequest(String repo, int prNumber) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.PR_NUMBER.eq(prNumber).and(TASKS.REPO.eq(repo).or(TASKS.REPO.isNull())))
                .orderBy(TASKS.CREATED_AT.desc())
                .limit(1)
                .fetchOptional()
                .map(TaskRepository::toDomain);
    }

    public boolean updateMetadata(UUID id, JSONB metadata) {
        return dsl.update(TASKS)
                .set(TASKS.METADATA, metadata)
                .where(TASKS.ID.eq(id))
                .execute() > 0;
    }

    static Task toDomain(TasksRecord r) {
        return new Task(
                r.getId(),
                r.getTitle(),
                r.getDescription(),
                r.getRole(),
                TaskStatus.fromDb(r.getStatus()),
                r.getPriority(),
                r.getFailures(),
                r.getRepo(),
                r.getBranch(),
                r.getPrNumber(),
                r.getPrUrl(),
                r.getHeadSha(),
                r.getMetadata(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
