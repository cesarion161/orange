package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.TASKS;

import com.ai.orange.db.jooq.tables.records.TasksRecord;
import com.ai.orange.task.exception.IllegalStatusTransitionException;
import java.time.OffsetDateTime;
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
                .set(TASKS.PIPELINE, def.pipelineOrDefault())
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
     * Idempotent claim of a specific task. If the task is {@code READY}, takes
     * it. If it's already {@code IN_PROGRESS} and claimed by {@code workerId}
     * (e.g. an activity retry), returns the existing claim. Otherwise returns
     * empty (someone else owns it, or it's in a terminal state).
     */
    @Transactional
    public Optional<Task> claimSpecific(UUID id, String workerId) {
        OffsetDateTime now = OffsetDateTime.now();
        var updated = dsl.update(TASKS)
                .set(TASKS.STATUS, TaskStatus.IN_PROGRESS.dbValue())
                .set(TASKS.CLAIMED_BY, workerId)
                .set(TASKS.CLAIMED_AT, now)
                .set(TASKS.HEARTBEAT_AT, now)
                .where(TASKS.ID.eq(id)
                        .and(TASKS.STATUS.eq(TaskStatus.READY.dbValue())
                                .or(TASKS.STATUS.eq(TaskStatus.IN_PROGRESS.dbValue())
                                        .and(TASKS.CLAIMED_BY.eq(workerId)))))
                .returning()
                .fetchOne();
        return Optional.ofNullable(updated).map(TaskRepository::toDomain);
    }

    /**
     * Atomically claim the next {@code READY} task for {@code role} using
     * {@code SELECT … FOR UPDATE SKIP LOCKED}. Two concurrent callers will
     * never see the same row.
     */
    @Transactional
    public Optional<Task> claimNextReady(String role, String workerId) {
        TasksRecord candidate = dsl.selectFrom(TASKS)
                .where(TASKS.STATUS.eq(TaskStatus.READY.dbValue()).and(TASKS.ROLE.eq(role)))
                .orderBy(TASKS.PRIORITY.asc(), TASKS.CREATED_AT.asc())
                .limit(1)
                .forUpdate()
                .skipLocked()
                .fetchOne();
        if (candidate == null) return Optional.empty();

        OffsetDateTime now = OffsetDateTime.now();
        TasksRecord claimed = dsl.update(TASKS)
                .set(TASKS.STATUS, TaskStatus.IN_PROGRESS.dbValue())
                .set(TASKS.CLAIMED_BY, workerId)
                .set(TASKS.CLAIMED_AT, now)
                .set(TASKS.HEARTBEAT_AT, now)
                .where(TASKS.ID.eq(candidate.getId()))
                .returning()
                .fetchOne();
        return Optional.ofNullable(claimed).map(TaskRepository::toDomain);
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

    public boolean updateHeartbeat(UUID id, OffsetDateTime at) {
        return dsl.update(TASKS)
                .set(TASKS.HEARTBEAT_AT, at)
                .where(TASKS.ID.eq(id))
                .execute() > 0;
    }

    /** Tasks stuck in_progress with a heartbeat older than {@code threshold}. */
    public java.util.List<Task> findStaleInProgress(OffsetDateTime threshold) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.STATUS.eq(TaskStatus.IN_PROGRESS.dbValue())
                        .and(TASKS.HEARTBEAT_AT.lessThan(threshold)))
                .fetch()
                .map(TaskRepository::toDomain);
    }

    /**
     * Recent tasks, newest first. Optional status filter — pass {@code null}
     * to get every status. Caller-provided limit is clamped to {@code 200} so
     * an over-eager UI can't load 50k rows.
     */
    public java.util.List<Task> findRecent(TaskStatus statusOrNull, int limit) {
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

    /** Count of in_progress tasks for {@code role}. Used by the concurrency gate. */
    public int countInProgress(String role) {
        return dsl.fetchCount(TASKS,
                TASKS.STATUS.eq(TaskStatus.IN_PROGRESS.dbValue())
                        .and(TASKS.ROLE.eq(role)));
    }

    /**
     * Tasks currently in {@code IN_PROGRESS}, optionally filtered by role.
     * Used by {@code GET /events/live} so the chat orchestrator can narrate
     * what every active worker is doing.
     */
    public java.util.List<Task> findInProgress(String roleOrNull) {
        var base = dsl.selectFrom(TASKS)
                .where(TASKS.STATUS.eq(TaskStatus.IN_PROGRESS.dbValue()));
        var ordered = (roleOrNull == null || roleOrNull.isBlank())
                ? base.orderBy(TASKS.CLAIMED_AT.asc())
                : base.and(TASKS.ROLE.eq(roleOrNull)).orderBy(TASKS.CLAIMED_AT.asc());
        return ordered.fetch().map(TaskRepository::toDomain);
    }

    /**
     * Terminal tasks (test_done / failed / cancelled) whose worktree hasn't
     * been swept yet — used by the post-merge worktree disposer. We treat
     * {@code metadata.worktree_removed_at = null} as the live marker; the
     * sweeper stamps it once disposal succeeds so re-runs become no-ops.
     */
    public java.util.List<Task> findTerminalWithLiveWorktree() {
        return dsl.selectFrom(TASKS)
                .where("status IN ('test_done','failed','cancelled')"
                        + " AND metadata->>'worktree_removed_at' IS NULL")
                .orderBy(TASKS.UPDATED_AT.asc())
                .fetch()
                .map(TaskRepository::toDomain);
    }

    /** Tasks currently in {@code READY} state. Used by WorkflowStarter on boot to drain pending tasks. */
    public java.util.List<Task> findReady() {
        return dsl.selectFrom(TASKS)
                .where(TASKS.STATUS.eq(TaskStatus.READY.dbValue()))
                .orderBy(TASKS.PRIORITY.asc(), TASKS.CREATED_AT.asc())
                .fetch()
                .map(TaskRepository::toDomain);
    }

    /**
     * Look up a task by the {@code (pr_repo, pr_number)} pair stamped into
     * {@code metadata} by {@code GithubPrService.openPrForTask}. Used by the
     * webhook router to map an incoming GitHub event to its task.
     */
    public Optional<Task> findByPullRequest(String repo, int prNumber) {
        return dsl.selectFrom(TASKS)
                .where("metadata->>'pr_repo' = ? AND (metadata->>'pr_number')::int = ?", repo, prNumber)
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
                r.getPipeline(),
                r.getPriority(),
                r.getClaimedBy(),
                r.getClaimedAt(),
                r.getHeartbeatAt(),
                r.getMetadata(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
