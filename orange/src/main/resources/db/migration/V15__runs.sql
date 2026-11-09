-- Runs are the unit of work (orange v2 spec §6, §7; project-space spec §7): one agent process, or
-- one verification or integration orange carries out itself, queued by the task reactor, claimed
-- by the scheduler, leased while it lives.

CREATE TABLE runs (
    id                     UUID PRIMARY KEY DEFAULT uuidv7(),
    task_id                UUID REFERENCES tasks(id) ON DELETE CASCADE,  -- null for plan runs
    -- agent kinds: plan, implement, ci_fix, diff_review, judge, brief; orange's own: verify, integrate.
    -- judge and brief belong to the project-space phases B and D, listed now so they need no migration.
    kind                   TEXT NOT NULL CHECK (kind IN ('plan', 'implement', 'ci_fix', 'verify',
                                                     'diff_review', 'integrate', 'judge', 'brief')),
    status                 TEXT NOT NULL DEFAULT 'queued'
                           CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    role                   TEXT NOT NULL,                  -- routing role: implementer, chore, reviewer, … ('orange' for verify/integrate)
    priority               INTEGER NOT NULL DEFAULT 100,   -- lower runs first, like tasks.priority
    attempt                INTEGER NOT NULL DEFAULT 1,     -- 1 + earlier runs of this kind on the task
    -- the route this run took (RouteDecision)
    tier                   TEXT,
    model                  TEXT,
    effort                 TEXT,
    harness                TEXT,
    auth                   TEXT CHECK (auth IN ('subscription', 'gateway')),
    budget_usd             NUMERIC(10, 4),
    input                  JSONB NOT NULL DEFAULT '{}'::jsonb,  -- what the run was asked to address
    outcome                JSONB,                                -- what it reported through MCP
    -- lease
    worker_id              TEXT,
    lease_expires_at       TIMESTAMPTZ,
    pid                    BIGINT,                         -- process mode: the harness process
    mcp_token_hash         TEXT UNIQUE,                    -- SHA-256 of the run's MCP bearer token
    -- gateway runs
    gateway_key_alias      TEXT,
    gateway_key_deleted_at TIMESTAMPTZ,
    spend_synced_at        TIMESTAMPTZ,
    -- accounting
    cost_usd               NUMERIC(10, 4) NOT NULL DEFAULT 0,
    tokens_in              BIGINT NOT NULL DEFAULT 0,
    tokens_out             BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens      BIGINT NOT NULL DEFAULT 0,
    session_id             TEXT,
    log_path               TEXT,
    error                  TEXT,                           -- failure reason (see FailureReasons)
    queued_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The task reactor's invariant: at most one queued-or-running run per task.
CREATE UNIQUE INDEX idx_runs_one_active_per_task ON runs (task_id)
    WHERE task_id IS NOT NULL AND status IN ('queued', 'running');
-- Scheduler hot path: next queued run of a kind.
CREATE INDEX idx_runs_queue  ON runs (kind, priority, queued_at) WHERE status = 'queued';
-- Reaper hot path: running runs by lease expiry.
CREATE INDEX idx_runs_leases ON runs (lease_expires_at) WHERE status = 'running';
CREATE INDEX idx_runs_task   ON runs (task_id, queued_at);

CREATE TRIGGER runs_touch_updated_at
    BEFORE UPDATE ON runs
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- A new run wakes the scheduler; a status change wakes the task reactor.
CREATE FUNCTION notify_run_queued() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('run_queued', NEW.kind);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER runs_notify_queued
    AFTER INSERT ON runs
    FOR EACH ROW EXECUTE FUNCTION notify_run_queued();

CREATE FUNCTION notify_run_changed() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status THEN
        PERFORM pg_notify('run_changed', coalesce(NEW.task_id::text, ''));
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER runs_notify_changed
    AFTER UPDATE ON runs
    FOR EACH ROW EXECUTE FUNCTION notify_run_changed();

-- Normalized harness events per run, used for tailing. The raw stream stays in the run's log file.
CREATE TABLE run_events (
    id         BIGSERIAL PRIMARY KEY,
    run_id     UUID NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    seq        INTEGER NOT NULL,
    type       TEXT NOT NULL,
    payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, seq)
);

-- Coordinator-wide switches, e.g. key 'paused' = {reason, until?, since}.
CREATE TABLE settings (
    key        TEXT PRIMARY KEY,
    value      JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER settings_touch_updated_at
    BEFORE UPDATE ON settings
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
