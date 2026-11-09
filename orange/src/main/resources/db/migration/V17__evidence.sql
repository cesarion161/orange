-- Evidence decides "done" (project-space spec §2, §7): every check orange ran on a task's head,
-- and every diff review of it. A task is verified only when its current head has passing
-- evidence for every configured command and a passing diff review. The session_id column comes
-- with the sessions table in project-space Phase A.
CREATE TABLE evidence (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    task_id     UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    run_id      UUID REFERENCES runs(id) ON DELETE SET NULL,
    head_sha    TEXT NOT NULL,
    kind        TEXT NOT NULL CHECK (kind IN ('tests', 'build', 'lint', 'custom', 'diff_review', 'screenshot', 'manual')),
    command     TEXT,
    exit_code   INT,
    output_tail TEXT,
    passed      BOOLEAN NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_evidence_task_head ON evidence (task_id, head_sha);
