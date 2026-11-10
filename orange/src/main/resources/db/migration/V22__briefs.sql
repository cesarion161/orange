-- The focus brief (project-space spec §7, §9; Phase D): a short report across the active projects.
-- orange reads the facts in SQL (the snapshot); a brief run adds one line per project and what to
-- focus on. A row is written once, complete, when the brief is.
CREATE TABLE briefs (
    id           UUID PRIMARY KEY DEFAULT uuidv7(),
    slot_at      TIMESTAMPTZ,                     -- the brief time it answers; null = asked for (orange_focus)
    snapshot_at  TIMESTAMPTZ NOT NULL,            -- when the facts were read; the next scheduled brief starts here
    snapshot     JSONB NOT NULL,                  -- the facts as orange read them
    content      JSONB,                           -- what submit_brief said; null = facts only
    note         TEXT,                            -- why there are no model lines: paused, the run failed, nothing new
    run_id       UUID REFERENCES runs(id) ON DELETE SET NULL,
    pushable     BOOLEAN NOT NULL DEFAULT false,  -- a scheduled brief with something new: it goes to the phone
    page_key     TEXT NOT NULL,                   -- opens this brief's page and nothing else
    delivered_at TIMESTAMPTZ,                     -- when ntfy took the push
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- One brief per brief time: a second tick, or a restart, finds the slot taken.
CREATE UNIQUE INDEX idx_briefs_slot ON briefs (slot_at) WHERE slot_at IS NOT NULL;
CREATE INDEX idx_briefs_latest ON briefs (snapshot_at DESC);
CREATE TRIGGER briefs_touch_updated_at
    BEFORE UPDATE ON briefs
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
-- The brief reads prompts and stops by time (focus, rhythm); nothing ever deletes session events.
CREATE INDEX idx_session_events_type_time ON session_events (type, received_at);
