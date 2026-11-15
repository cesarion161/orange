-- GitHub is the meeting point (remote-first spec §7.1, §7.4).

-- A project's GitHub repositories, one row per repository: its identity is owner/name, whatever
-- checkout reports it. orange keeps one mirror of each.
CREATE TABLE repos (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id     UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    key            TEXT NOT NULL UNIQUE,          -- owner/name on GitHub, lowercased
    default_branch TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_repos_project ON repos (project_id);

-- A checkout (project_repos: a path on a node) of a GitHub repo; null when its remote is not on GitHub.
ALTER TABLE project_repos ADD COLUMN repo_id UUID REFERENCES repos(id) ON DELETE SET NULL;

-- Tasks belong to a project and are done in one of its repos. Both null: the legacy single base repo.
ALTER TABLE tasks ADD COLUMN project_id UUID REFERENCES projects(id) ON DELETE SET NULL,
                  ADD COLUMN repo_id    UUID REFERENCES repos(id) ON DELETE RESTRICT;

CREATE INDEX idx_tasks_project ON tasks (project_id) WHERE project_id IS NOT NULL;

-- What the session's last stop reported of its checkout: orange closes work from GitHub and never
-- reads the checkout itself.
ALTER TABLE sessions ADD COLUMN head_sha    TEXT,   -- its HEAD
                     ADD COLUMN dirty_files JSONB;  -- its uncommitted and untracked paths; null = not reported
