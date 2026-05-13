package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.TASK_EDGES;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class TaskEdgeRepository {

    private final DSLContext dsl;

    public TaskEdgeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(UUID from, UUID to) {
        dsl.insertInto(TASK_EDGES)
                .set(TASK_EDGES.FROM_ID, from)
                .set(TASK_EDGES.TO_ID, to)
                .onConflictDoNothing()
                .execute();
    }

    /** Tasks that {@code id} depends on (its parents). */
    public List<UUID> parentsOf(UUID id) {
        return dsl.select(TASK_EDGES.FROM_ID)
                .from(TASK_EDGES)
                .where(TASK_EDGES.TO_ID.eq(id))
                .fetch(TASK_EDGES.FROM_ID);
    }

    /** Tasks that depend on {@code id} (its children). */
    public List<UUID> childrenOf(UUID id) {
        return dsl.select(TASK_EDGES.TO_ID)
                .from(TASK_EDGES)
                .where(TASK_EDGES.FROM_ID.eq(id))
                .fetch(TASK_EDGES.TO_ID);
    }
}
