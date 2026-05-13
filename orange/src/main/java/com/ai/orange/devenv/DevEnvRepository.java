package com.ai.orange.devenv;

import static com.ai.orange.db.jooq.Tables.DEV_ENVS;

import com.ai.orange.db.jooq.tables.records.DevEnvsRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DevEnvRepository {

    private final DSLContext dsl;

    public DevEnvRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Atomically claim the next {@code FREE} dev_env using
     * {@code SELECT … FOR UPDATE SKIP LOCKED}. Two concurrent callers will
     * never see the same row. Returns empty when the pool is exhausted —
     * callers either retry or wait on a {@code dev_env_freed} notification.
     */
    @Transactional
    public Optional<DevEnv> tryLease(String holder) {
        DevEnvsRecord candidate = dsl.selectFrom(DEV_ENVS)
                .where(DEV_ENVS.STATUS.eq(DevEnvStatus.FREE.dbValue()))
                .orderBy(DEV_ENVS.NAME.asc())
                .limit(1)
                .forUpdate()
                .skipLocked()
                .fetchOne();
        if (candidate == null) return Optional.empty();

        OffsetDateTime now = OffsetDateTime.now();
        DevEnvsRecord leased = dsl.update(DEV_ENVS)
                .set(DEV_ENVS.STATUS, DevEnvStatus.LEASED.dbValue())
                .set(DEV_ENVS.LEASED_BY, holder)
                .set(DEV_ENVS.LEASED_AT, now)
                .setNull(DEV_ENVS.RELEASED_AT)
                .where(DEV_ENVS.ID.eq(candidate.getId()))
                .returning()
                .fetchOne();
        return Optional.ofNullable(leased).map(DevEnvRepository::toDomain);
    }

    /**
     * Returns the env to the {@code FREE} pool. The {@code dev_envs_notify_freed}
     * trigger fires {@code pg_notify('dev_env_freed', id)} as a side effect,
     * waking any callers blocked in {@link DevEnvService#acquire}.
     */
    @Transactional
    public boolean release(UUID id) {
        return dsl.update(DEV_ENVS)
                .set(DEV_ENVS.STATUS, DevEnvStatus.FREE.dbValue())
                .setNull(DEV_ENVS.LEASED_BY)
                .setNull(DEV_ENVS.LEASED_AT)
                .set(DEV_ENVS.RELEASED_AT, OffsetDateTime.now())
                .where(DEV_ENVS.ID.eq(id)
                        .and(DEV_ENVS.STATUS.eq(DevEnvStatus.LEASED.dbValue())))
                .execute() > 0;
    }

    public Optional<DevEnv> findById(UUID id) {
        return dsl.selectFrom(DEV_ENVS).where(DEV_ENVS.ID.eq(id)).fetchOptional().map(DevEnvRepository::toDomain);
    }

    public List<DevEnv> findByLeasedBy(String holder) {
        return dsl.selectFrom(DEV_ENVS)
                .where(DEV_ENVS.LEASED_BY.eq(holder))
                .fetch()
                .map(DevEnvRepository::toDomain);
    }

    public int countByStatus(DevEnvStatus status) {
        return dsl.fetchCount(DEV_ENVS, DEV_ENVS.STATUS.eq(status.dbValue()));
    }

    /** All envs, ordered by name. Used by {@code GET /dev-envs} for status views. */
    public List<DevEnv> findAll() {
        return dsl.selectFrom(DEV_ENVS)
                .orderBy(DEV_ENVS.NAME.asc())
                .fetch()
                .map(DevEnvRepository::toDomain);
    }

    static DevEnv toDomain(DevEnvsRecord r) {
        return new DevEnv(
                r.getId(),
                r.getName(),
                r.getPorts(),
                DevEnvStatus.fromDb(r.getStatus()),
                r.getLeasedBy(),
                r.getLeasedAt(),
                r.getReleasedAt(),
                r.getMetadata(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
