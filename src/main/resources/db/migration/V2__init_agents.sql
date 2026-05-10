CREATE TABLE agents (
    id                UUID PRIMARY KEY DEFAULT uuidv7(),
    name              TEXT NOT NULL UNIQUE,                 -- e.g. 'dev-1', 'tester-fast'
    role              TEXT NOT NULL,                        -- 'dev', 'tester', 'reviewer', ...
    system_prompt     TEXT NOT NULL,
    model             TEXT NOT NULL DEFAULT 'claude-sonnet-4-6',
    fallback_model    TEXT,
    allowed_tools     JSONB NOT NULL DEFAULT '[]'::jsonb,   -- ['Read','Edit','Bash',...]
    disallowed_tools  JSONB NOT NULL DEFAULT '[]'::jsonb,
    permission_mode   TEXT NOT NULL DEFAULT 'acceptEdits',
    max_turns         INTEGER,
    max_budget_usd    NUMERIC(10, 2),
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agents_role_enabled ON agents (role) WHERE enabled = TRUE;

CREATE TRIGGER agents_touch_updated_at
    BEFORE UPDATE ON agents
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- agent_runs: one row per (task × agent attempt). Lets us assign the same task
-- to multiple agents one-by-one (with different prompts) and keep the history.
CREATE TABLE agent_runs (
    id           UUID PRIMARY KEY DEFAULT uuidv7(),
    task_id      UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    agent_id     UUID NOT NULL REFERENCES agents(id) ON DELETE RESTRICT,
    attempt      INTEGER NOT NULL,                          -- 1, 2, 3 ...
    prompt       TEXT NOT NULL,                             -- the actual prompt sent (for replay)
    status       TEXT NOT NULL DEFAULT 'running'
                 CHECK (status IN ('running','succeeded','failed','cancelled')),
    cost_usd     NUMERIC(10, 4) DEFAULT 0,
    tokens_in    BIGINT DEFAULT 0,
    tokens_out   BIGINT DEFAULT 0,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at  TIMESTAMPTZ,
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,        -- worktree path, session_id, ...
    UNIQUE (task_id, attempt)
);

CREATE INDEX idx_agent_runs_task   ON agent_runs (task_id, attempt);
CREATE INDEX idx_agent_runs_agent  ON agent_runs (agent_id, started_at DESC);
CREATE INDEX idx_agent_runs_active ON agent_runs (started_at) WHERE status = 'running';
