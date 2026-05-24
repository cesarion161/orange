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
-- Concurrency (the load-bearing detail): two parents of the same child can
-- reach `dev_ready` in *separate concurrent transactions* (e.g. two PR-merge
-- webhooks firing at once). Under READ COMMITTED each transaction's NOT EXISTS
-- would not see the other's not-yet-committed parent transition, so both could
-- skip promotion and strand the child permanently (a fan-in lost update). To
-- prevent that we take a transaction-scoped advisory lock keyed on the child
-- before checking it: the two transactions serialize on that child, and the
-- second one re-evaluates (fresh READ COMMITTED snapshot, post-lock) against
-- the first's now-committed state — so the last parent to commit always
-- promotes the child. We lock per-child (never the parent rows), so there is
-- no cross-resource lock cycle and no deadlock.
--
-- Trigger-based so it fires regardless of which path advances the parent —
-- GitHub merge webhook, workflow activity, or a manual transition.
CREATE OR REPLACE FUNCTION promote_ready_dependents() RETURNS TRIGGER AS $$
DECLARE
    child_id UUID;
BEGIN
    IF NEW.status IN ('dev_ready', 'in_test', 'test_done')
       AND OLD.status IS DISTINCT FROM NEW.status THEN
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
                      AND parent.status NOT IN ('dev_ready', 'in_test', 'test_done'));
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tasks_promote_dependents
    AFTER UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION promote_ready_dependents();
