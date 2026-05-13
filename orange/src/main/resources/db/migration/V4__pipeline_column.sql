-- Per-task pipeline selector. The orchestrator looks this up to pick which
-- Temporal workflow type to start (e.g. 'dev_only' = single dev stage,
-- 'dev_qa' = dev → tester loop, 'dev_review_qa' = dev → reviewer → tester).
ALTER TABLE tasks
    ADD COLUMN pipeline TEXT NOT NULL DEFAULT 'dev_only';

CREATE INDEX idx_tasks_pipeline_ready_queue
    ON tasks (pipeline, role, priority, created_at)
    WHERE status = 'ready';
