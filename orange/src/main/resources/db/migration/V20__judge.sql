-- The session judge (project-space spec §6, §7; Phase B): at every stop of a session orange
-- follows, a short judge run decides what happens next, and its verdict reaches the session
-- through the stop hook, which long-polls for it.

-- A judge run belongs to a session, not to a task. (runs.session_id is the harness's own id of
-- the run's agent session, so this link has its own name.)
ALTER TABLE runs ADD COLUMN attended_session_id UUID REFERENCES sessions(id) ON DELETE CASCADE;
CREATE INDEX idx_runs_attended_session ON runs (attended_session_id, queued_at)
    WHERE attended_session_id IS NOT NULL;

-- The stop a session is at: the reporter's id of its latest 'stopped' event. A verdict is only
-- ever delivered to the stop it was made for.
ALTER TABLE sessions ADD COLUMN stop_event_id TEXT;

CREATE TABLE verdicts (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    session_id    UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    run_id        UUID REFERENCES runs(id) ON DELETE SET NULL,
    stop_event_id TEXT NOT NULL,
    -- continue: orange answers the session. verify: it says it is finished. escalate: the user
    -- must decide. idle: nothing to do.
    kind          TEXT NOT NULL CHECK (kind IN ('continue', 'verify', 'escalate', 'idle')),
    message       TEXT,                              -- continue: what the session is told
    rationale     TEXT,
    confidence    NUMERIC(3, 2),
    -- pending: waiting for the stop hook. delivered: the hook took it. cancelled: the user spoke
    -- first. expired: the session had moved on, or no hook came for it.
    status        TEXT NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'delivered', 'cancelled', 'expired')),
    delivered_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (session_id, stop_event_id)
);

CREATE INDEX idx_verdicts_pending ON verdicts (created_at) WHERE status = 'pending';

CREATE TRIGGER verdicts_touch_updated_at
    BEFORE UPDATE ON verdicts
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
