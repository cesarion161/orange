-- Verifying, closing and integrating the user's sessions (project-space spec §7, §8, decisions
-- 10 and 11; Phase C). When a judge says a session is finished, orange checks the work itself,
-- and only passing evidence closes it.

-- Evidence belongs to a task or to a session.
ALTER TABLE evidence ALTER COLUMN task_id DROP NOT NULL;
ALTER TABLE evidence ADD COLUMN session_id UUID REFERENCES sessions(id) ON DELETE CASCADE;
ALTER TABLE evidence ADD CONSTRAINT evidence_owner CHECK ((task_id IS NULL) <> (session_id IS NULL));
CREATE INDEX idx_evidence_session_head ON evidence (session_id, head_sha) WHERE session_id IS NOT NULL;

-- The unit of attended work is an intent, not a session (spec decision 10): a session holds a
-- sequence of these. The first prompt opens one; a close ends it; the next prompt opens another.
CREATE TABLE session_work (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    session_id  UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    intent      TEXT,
    status      TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    -- How it closed: integrated (on the base, pushed or not), verified (evidence passed and the
    -- project's conventions ask for no integration, or leave it to the user).
    result      TEXT CHECK (result IN ('integrated', 'verified')),
    head_sha    TEXT,
    branch      TEXT,
    base_branch TEXT,
    note        TEXT,                               -- what orange did and what it left alone
    opened_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at   TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_session_work_one_open ON session_work (session_id) WHERE status = 'open';
CREATE INDEX idx_session_work_session ON session_work (session_id, opened_at DESC);

CREATE TRIGGER session_work_touch_updated_at
    BEFORE UPDATE ON session_work
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Sessions orange is verifying, for the sweeper.
CREATE INDEX idx_sessions_deciding ON sessions (waiting_since) WHERE status IN ('judging', 'verifying');
