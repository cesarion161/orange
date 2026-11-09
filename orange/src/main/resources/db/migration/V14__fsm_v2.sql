-- FSM v2 (orange v2 spec §6). A task now moves
--   pending → ready → running → in_review → merged
-- with needs_human for anything that waits on a person. The coordinator runs every agent
-- itself, so the v1 pipeline and chat-claim columns go, and PR state moves out of
-- metadata into real columns.

-- 1. Triggers that know the v1 states. The promotion trigger comes back below with the
--    new satisfied state; dropping it first keeps the data migration from firing it.
DROP TRIGGER tasks_notify_ready ON tasks;
DROP FUNCTION notify_task_ready();
DROP TRIGGER tasks_promote_dependents ON tasks;

-- 2. Statuses. A task that was mid-run under v1 has no run any more, so it starts over
--    from ready; everything already merged counts as merged.
ALTER TABLE tasks DROP CONSTRAINT tasks_status_check;
UPDATE tasks SET status = CASE status
        WHEN 'in_progress' THEN 'ready'
        WHEN 'pr_open'     THEN 'in_review'
        WHEN 'dev_ready'   THEN 'merged'
        WHEN 'in_test'     THEN 'merged'
        WHEN 'test_done'   THEN 'merged'
        ELSE status
    END;
ALTER TABLE tasks ADD CONSTRAINT tasks_status_check CHECK (status IN (
    'pending',      -- some dependency is not merged yet
    'ready',        -- dependencies merged; waiting for its first implement run
    'running',      -- a code-changing run (implement or CI fix) is queued or active
    'in_review',    -- PR open and no code-changing run pending; a review run may be active
    'merged',       -- PR merged; satisfies dependents
    'needs_human',  -- waiting on a person: a question, repeated failures, a PR closed unmerged
    'failed',       -- given up
    'cancelled'));

-- 3. Roles: implementer or chore (the reviewer and planner are run kinds, not task roles).
UPDATE tasks SET role = 'implementer' WHERE role <> 'chore';
ALTER TABLE tasks ALTER COLUMN role SET DEFAULT 'implementer';
ALTER TABLE tasks ADD CONSTRAINT tasks_role_check CHECK (role IN ('implementer', 'chore'));

-- 4. v1 pipeline and claim columns. Dropping a column also drops the indexes and CHECKs
--    that use it (the pipeline queue, the heartbeat and claimed_by indexes, the claim pair CHECK).
DROP INDEX idx_tasks_ready_queue;
DROP INDEX idx_tasks_dev_ready_queue;
ALTER TABLE tasks
    DROP COLUMN pipeline,
    DROP COLUMN claimed_by,
    DROP COLUMN claimed_at,
    DROP COLUMN heartbeat_at;
DROP TABLE task_claims;

-- 5. Run and PR state.
ALTER TABLE tasks
    ADD COLUMN repo           TEXT,                       -- owner/name the PR lives in
    ADD COLUMN failures       INTEGER NOT NULL DEFAULT 0, -- model failures since the last human answer
    ADD COLUMN branch         TEXT,
    ADD COLUMN pr_number      INTEGER,
    ADD COLUMN pr_url         TEXT,
    ADD COLUMN head_sha       TEXT,                       -- PR head the coordinator last saw
    ADD COLUMN reviewed_sha   TEXT,                       -- head the latest review run judged
    ADD COLUMN review_verdict TEXT CHECK (review_verdict IN ('approve', 'request_changes'));

UPDATE tasks
   SET repo      = metadata->>'pr_repo',
       pr_number = (metadata->>'pr_number')::int,
       pr_url    = metadata->>'pr_url',
       branch    = 'orange/' || id
 WHERE metadata ? 'pr_number';

CREATE INDEX idx_tasks_ready  ON tasks (priority, created_at) WHERE status = 'ready';
CREATE INDEX idx_tasks_pr     ON tasks (pr_number) WHERE pr_number IS NOT NULL;
CREATE INDEX idx_tasks_active ON tasks (updated_at)
    WHERE status IN ('ready', 'running', 'in_review', 'needs_human');

-- 6. Dependents are promoted once every parent is merged. The per-child advisory lock and
--    its reasoning are unchanged from V11: two parents merging in concurrent transactions
--    serialize on the child, so the last one to commit always promotes it.
CREATE OR REPLACE FUNCTION promote_ready_dependents() RETURNS TRIGGER AS $$
DECLARE
    child_id UUID;
BEGIN
    IF NEW.status = 'merged' AND OLD.status IS DISTINCT FROM NEW.status THEN
        FOR child_id IN
            SELECT e.to_id FROM task_edges e WHERE e.from_id = NEW.id
        LOOP
            PERFORM pg_advisory_xact_lock(hashtextextended(child_id::text, 0));
            UPDATE tasks child
               SET status = 'ready'
             WHERE child.id = child_id
               AND child.status = 'pending'
               AND NOT EXISTS (
                   SELECT 1 FROM task_edges e2
                     JOIN tasks parent ON parent.id = e2.from_id
                    WHERE e2.to_id = child_id
                      AND parent.status <> 'merged');
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tasks_promote_dependents
    AFTER UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION promote_ready_dependents();

-- 7. Every status change is announced, so the task reactor can decide the next run
--    without polling (it also sweeps periodically in case a notification is missed).
CREATE FUNCTION notify_task_changed() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status THEN
        PERFORM pg_notify('task_changed', NEW.id::text);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tasks_notify_changed
    AFTER UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION notify_task_changed();
