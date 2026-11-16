package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.TASKS;

import com.ai.orange.db.jooq.tables.records.TasksRecord;
import com.ai.orange.task.exception.IllegalStatusTransitionException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TaskRepository {

    /** A whole task id, or at least its first 8 characters. */
    private static final Pattern ID_PREFIX = Pattern.compile("[0-9a-f-]{8,36}");

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
                .set(TASKS.PROJECT_ID, def.placement().projectId())
                .set(TASKS.REPO_ID, def.placement().repoId())
                .set(TASKS.PLAN_ID, def.planId())
                .set(TASKS.MILESTONE_ID, def.milestoneId())
                // status defaults to 'pending' from the schema
                .returning()
                .fetchOne();
        return toDomain(rec);
    }

    public Optional<Task> findById(UUID id) {
        return dsl.selectFrom(TASKS).where(TASKS.ID.eq(id)).fetchOptional().map(TaskRepository::toDomain);
    }

    /**
     * Tasks whose id starts with {@code prefix} (a full id, or at least 8 of its leading characters, any
     * case), at most two: one is the task, two means the prefix is ambiguous.
     *
     * @throws IllegalArgumentException when the prefix is shorter than 8 characters or not part of an id
     */
    public List<Task> findByIdPrefix(String prefix) {
        String p = prefix == null ? "" : prefix.strip().toLowerCase(Locale.ROOT);
        if (!ID_PREFIX.matcher(p).matches()) {
            throw new IllegalArgumentException("a task id is a UUID or at least 8 of its first characters");
        }
        // Only hex and dashes get here, so the pattern has no wildcard of the caller's.
        return dsl.selectFrom(TASKS).where(TASKS.ID.cast(String.class).like(p + "%"))
                .orderBy(TASKS.CREATED_AT.desc()).limit(2)
                .fetch().map(TaskRepository::toDomain);
    }

    /** Tasks found by {@link #findByRef}, and how many matched in all. */
    public record RefMatch(int count, List<Task> tasks) {}

    /**
     * The tasks a person's reference names: a full id, or at least 8 hex digits (dashes allowed, any case)
     * that an id starts or ends with. People see the short id, the last 8 ({@link Task#shortId}); a prefix
     * of 8 is only uuidv7's timestamp, which tasks of one plan share.
     *
     * @return the count of matches and at most {@code limit} of them, newest first
     * @throws IllegalArgumentException when the reference is shorter than 8 characters or not part of an id
     */
    public RefMatch findByRef(String ref, int limit) {
        String p = ref == null ? "" : ref.strip().toLowerCase(Locale.ROOT);
        if (!ID_PREFIX.matcher(p).matches()) {
            throw new IllegalArgumentException("a task id is a UUID, or at least 8 of its first or last characters");
        }
        try {
            UUID id = UUID.fromString(p);
            if (p.length() == 36) {
                List<Task> one = findById(id).map(List::of).orElse(List.of());
                return new RefMatch(one.size(), one);
            }
        } catch (IllegalArgumentException notAFullId) {
            // a part of one
        }
        // Only hex and dashes get here, so the pattern has no wildcard of the caller's.
        Condition matches = TASKS.ID.cast(String.class).like(p + "%").or(TASKS.ID.cast(String.class).like("%" + p));
        int count = dsl.fetchCount(TASKS, matches);
        List<Task> tasks = count == 0 ? List.of() : dsl.selectFrom(TASKS).where(matches)
                .orderBy(TASKS.CREATED_AT.desc(), TASKS.ID.desc()).limit(Math.max(1, limit))
                .fetch().map(TaskRepository::toDomain);
        return new RefMatch(count, tasks);
    }

    /**
     * Reads the task and locks its row until the caller's transaction ends ({@code FOR UPDATE}),
     * so concurrent reactors decide one at a time.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Task> lockForUpdate(UUID id) {
        return dsl.selectFrom(TASKS).where(TASKS.ID.eq(id)).forUpdate().fetchOptional().map(TaskRepository::toDomain);
    }

    /** Tasks the reactor may have something to do for: ready, running, verifying or verified. */
    public List<UUID> findActiveIds() {
        return dsl.select(TASKS.ID).from(TASKS)
                .where(TASKS.STATUS.in(TaskStatus.READY.dbValue(), TaskStatus.RUNNING.dbValue(),
                        TaskStatus.VERIFYING.dbValue(), TaskStatus.VERIFIED.dbValue()))
                .orderBy(TASKS.PRIORITY.asc(), TASKS.CREATED_AT.asc())
                .fetch(TASKS.ID);
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
                r.getUpdatedAt(),
                r.getProjectId(),
                r.getRepoId(),
                r.getPlanId(),
                r.getMilestoneId());
    }
}
