package com.ai.orange.evidence;

import static com.ai.orange.db.jooq.Tables.EVIDENCE;

import com.ai.orange.db.jooq.tables.records.EvidenceRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class EvidenceRepository {

    public static final String DIFF_REVIEW = "diff_review";

    private final DSLContext dsl;

    public EvidenceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Evidence record(UUID taskId, UUID runId, String headSha, String kind, String command, Integer exitCode,
                           String outputTail, boolean passed) {
        return toDomain(dsl.insertInto(EVIDENCE)
                .set(EVIDENCE.TASK_ID, taskId)
                .set(EVIDENCE.RUN_ID, runId)
                .set(EVIDENCE.HEAD_SHA, headSha)
                .set(EVIDENCE.KIND, kind)
                .set(EVIDENCE.COMMAND, command)
                .set(EVIDENCE.EXIT_CODE, exitCode)
                .set(EVIDENCE.OUTPUT_TAIL, outputTail)
                .set(EVIDENCE.PASSED, passed)
                .returning()
                .fetchOne());
    }

    /** Evidence for one head of a task, oldest first. */
    public List<Evidence> findByTaskAndHead(UUID taskId, String headSha) {
        return dsl.selectFrom(EVIDENCE)
                .where(EVIDENCE.TASK_ID.eq(taskId).and(EVIDENCE.HEAD_SHA.eq(headSha)))
                .orderBy(EVIDENCE.CREATED_AT.asc(), EVIDENCE.ID.asc())
                .fetch().map(EvidenceRepository::toDomain);
    }

    /** Whether the head has a passing diff review (its own, or one carried over by a clean rebase). */
    public boolean hasPassingDiffReview(UUID taskId, String headSha) {
        return dsl.fetchExists(EVIDENCE, EVIDENCE.TASK_ID.eq(taskId)
                .and(EVIDENCE.HEAD_SHA.eq(headSha))
                .and(EVIDENCE.KIND.eq(DIFF_REVIEW))
                .and(EVIDENCE.PASSED.isTrue()));
    }

    /** All evidence of a task, newest first. */
    public List<Evidence> findByTask(UUID taskId) {
        return dsl.selectFrom(EVIDENCE)
                .where(EVIDENCE.TASK_ID.eq(taskId))
                .orderBy(EVIDENCE.CREATED_AT.desc(), EVIDENCE.ID.desc())
                .fetch().map(EvidenceRepository::toDomain);
    }

    static Evidence toDomain(EvidenceRecord r) {
        return new Evidence(r.getId(), r.getTaskId(), r.getRunId(), r.getHeadSha(), r.getKind(), r.getCommand(),
                r.getExitCode(), r.getOutputTail(), Boolean.TRUE.equals(r.getPassed()), r.getCreatedAt());
    }
}
