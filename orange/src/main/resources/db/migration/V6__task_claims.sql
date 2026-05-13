-- Lease records issued to chat-driven (and headless) executors when they claim a
-- task via the chat-as-executor MCP surface. The orchestrator-owned reaper bounces
-- expired claims back into the queue, mirroring the dead-worker recovery that the
-- subprocess pipeline gets from the `tasks.heartbeat_at` reaper.
--
-- One active row per `task_id` at a time. When a task is released or completed we
-- delete the row; this keeps the partial-index hot path small and lets retries
-- re-acquire cleanly. Historical claim activity is captured in `task_events` —
-- this table is only the live-lease pointer.
CREATE TABLE task_claims (
    task_id      UUID PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    claim_token  UUID NOT NULL UNIQUE,
    worker_id    TEXT NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ NOT NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    attempt      INTEGER NOT NULL DEFAULT 1,
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- Reaper hot path: find lapsed leases ordered by expiry.
CREATE INDEX idx_task_claims_expires ON task_claims (expires_at);

-- "Show me my active claims" — used by chat-session resume after restart.
CREATE INDEX idx_task_claims_worker ON task_claims (worker_id);
