-- Shared bits used by later migrations too.
CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ──────────────────────────── tasks ─────────────────────────────────────
CREATE TABLE tasks (
    id           UUID PRIMARY KEY DEFAULT uuidv7(),
    title        TEXT NOT NULL,
    description  TEXT,
    role         TEXT NOT NULL DEFAULT 'dev',
    status       TEXT NOT NULL DEFAULT 'pending'
                 CHECK (status IN (
                     'pending',      -- created, dependencies not yet satisfied
                     'ready',        -- claimable by an agent
                     'in_progress',  -- claimed, agent working
                     'pr_open',      -- agent finished, PR awaiting human review/merge
                     'dev_ready',    -- merged to main, awaiting test pickup
                     'in_test',      -- a tester agent is running smoke tests
                     'test_done',    -- testing complete (see latest task_event for verdict)
                     'failed',
                     'cancelled'
                 )),
    priority     INTEGER NOT NULL DEFAULT 100,
    claimed_by   TEXT,                                   -- agent name or worker id
    claimed_at   TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,     -- worktree path, PR url, etc.
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK ((claimed_by IS NULL) = (claimed_at IS NULL))
);

-- Hot path: claim the next ready task with FOR UPDATE SKIP LOCKED.
CREATE INDEX idx_tasks_ready_queue
    ON tasks (role, priority, created_at)
    WHERE status = 'ready';

CREATE INDEX idx_tasks_dev_ready_queue
    ON tasks (priority, created_at)
    WHERE status = 'dev_ready';

-- Reaper / heartbeat-stale lookup.
CREATE INDEX idx_tasks_in_progress_heartbeat
    ON tasks (heartbeat_at)
    WHERE status = 'in_progress';

CREATE INDEX idx_tasks_claimed_by
    ON tasks (claimed_by)
    WHERE claimed_by IS NOT NULL;

CREATE TRIGGER tasks_touch_updated_at
    BEFORE UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Notify any LISTEN'er when a task transitions into a claimable state.
CREATE OR REPLACE FUNCTION notify_task_ready() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status IN ('ready', 'dev_ready')
       AND (OLD.status IS DISTINCT FROM NEW.status) THEN
        PERFORM pg_notify('task_ready', NEW.id::text || ':' || NEW.status);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tasks_notify_ready
    AFTER UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION notify_task_ready();

-- ──────────────────────────── task_edges (DAG) ──────────────────────────
CREATE TABLE task_edges (
    from_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    to_id   UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    PRIMARY KEY (from_id, to_id),
    CHECK (from_id <> to_id)
);

-- Dependents lookup: "which tasks depend on me?"
CREATE INDEX idx_task_edges_to ON task_edges (to_id);

-- ──────────────────────────── task_events (audit) ───────────────────────
CREATE TABLE task_events (
    id         BIGSERIAL PRIMARY KEY,
    task_id    UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    actor      TEXT NOT NULL,                            -- 'system', agent name, user
    event_type TEXT NOT NULL,                            -- 'status_changed', 'tool_use', 'assistant_message', 'cost', 'hook_blocked', etc.
    payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_task_events_task    ON task_events (task_id, id);
CREATE INDEX idx_task_events_type    ON task_events (event_type, created_at DESC);
