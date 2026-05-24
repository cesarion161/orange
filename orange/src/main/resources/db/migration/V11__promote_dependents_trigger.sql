-- DAG fan-out: promote a task's dependents when it finishes.
--
-- Until now nothing moved a dependent task out of `pending`: TaskService only
-- flips ROOT tasks (no incoming edges) pending→ready, and no code path or
-- trigger promoted children when their parents completed. So a multi-task plan
-- only ever ran its roots — children sat `pending` forever. (childrenOf() had
-- no callers; markReadyIfPending() was called only for roots.)
--
-- A task's work becomes available to its dependents once it's merged to the
-- trunk, i.e. it reaches `dev_ready` (or any later state). When that happens,
-- promote each pending child whose parents are ALL satisfied. The resulting
-- pending→ready UPDATE fires notify_task_ready (V1), so idle workers pick the
-- child up immediately via LISTEN/NOTIFY — no polling.
--
-- A child with a parent that FAILED/CANCELLED is intentionally left pending:
-- that parent is not in the satisfied set, so the NOT EXISTS guard keeps the
-- child blocked (a broken dependency must not unblock downstream work).
--
-- Doing this in a trigger (rather than Java) makes it fire regardless of which
-- path advances the parent — GitHub merge webhook, workflow activity, or a
-- manual transition — and mirrors the existing trigger style in V1.
CREATE OR REPLACE FUNCTION promote_ready_dependents() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IN ('dev_ready', 'in_test', 'test_done')
       AND OLD.status IS DISTINCT FROM NEW.status THEN
        UPDATE tasks child
           SET status = 'ready'
         WHERE child.status = 'pending'
           AND EXISTS (
               SELECT 1 FROM task_edges e
                WHERE e.to_id = child.id
                  AND e.from_id = NEW.id)
           AND NOT EXISTS (
               SELECT 1 FROM task_edges e2
                 JOIN tasks parent ON parent.id = e2.from_id
                WHERE e2.to_id = child.id
                  AND parent.status NOT IN ('dev_ready', 'in_test', 'test_done'));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tasks_promote_dependents
    AFTER UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION promote_ready_dependents();
