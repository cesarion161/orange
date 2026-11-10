-- Project space, sessions and attention (project-space spec §5, §9; Phase A).
-- orange watches the user's own interactive sessions, keeps each project's goals, conventions,
-- decisions and notes, and collects everything that wants the user in one inbox.

-- A named area of work. 'proposed' = orange saw a repo it did not know and awaits the user.
CREATE TABLE projects (
    id         UUID PRIMARY KEY DEFAULT uuidv7(),
    name       TEXT NOT NULL UNIQUE,
    status     TEXT NOT NULL DEFAULT 'active'
               CHECK (status IN ('proposed', 'active', 'paused', 'archived')),
    summary    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER projects_touch_updated_at
    BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- A repo belongs to one project. repo_root is the main checkout's absolute path on its node.
CREATE TABLE project_repos (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id     UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    node_id        TEXT NOT NULL DEFAULT 'local',
    repo_root      TEXT NOT NULL,
    remote_url     TEXT,
    default_branch TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (node_id, repo_root)
);

CREATE INDEX idx_project_repos_project ON project_repos (project_id);

-- The user's interactive sessions, one row per harness session.
CREATE TABLE sessions (
    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id          UUID REFERENCES projects(id) ON DELETE SET NULL,
    harness             TEXT NOT NULL,                 -- claude-code, opencode
    external_id         TEXT NOT NULL,                 -- the harness's own session id
    node_id             TEXT NOT NULL DEFAULT 'local', -- the machine it reported from (spec §6.1)
    cwd                 TEXT,
    repo_root           TEXT,
    branch              TEXT,
    worktree            TEXT,
    intent              TEXT,                          -- the first prompt, until someone sets one
    status              TEXT NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'stopped', 'judging', 'verifying',
                                          'waiting_on_user', 'closed', 'ended')),
    -- Why the session is not working, and since when: question (it stopped asking something),
    -- permission (a prompt only the user can answer), finished (it stopped with nothing to ask).
    waiting_reason      TEXT CHECK (waiting_reason IN ('question', 'permission', 'finished')),
    waiting_since       TIMESTAMPTZ,
    last_message        TEXT,                          -- the excerpt sent with the last stop
    auto_continues      INT NOT NULL DEFAULT 0,
    last_event_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_user_prompt_at TIMESTAMPTZ,
    closed_reason       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (harness, external_id)
);

-- Sweeper hot path: sessions that are waiting for someone.
CREATE INDEX idx_sessions_waiting ON sessions (waiting_since) WHERE waiting_since IS NOT NULL;
CREATE INDEX idx_sessions_project ON sessions (project_id, last_event_at DESC);

CREATE TRIGGER sessions_touch_updated_at
    BEFORE UPDATE ON sessions
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE FUNCTION notify_session_changed() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status THEN
        PERFORM pg_notify('session_changed', NEW.id::text);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sessions_notify_changed
    AFTER INSERT OR UPDATE ON sessions
    FOR EACH ROW EXECUTE FUNCTION notify_session_changed();

-- The neutral events a session's reporter sent, in the order orange applied them.
CREATE TABLE session_events (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    type        TEXT NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ,                           -- the reporter's clock
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_session_events_session ON session_events (session_id, id);

-- One entry in a project's space. Content is never edited in place: a change inserts a new item
-- that supersedes the old one. A proposal is any kind with status 'proposed'.
CREATE TABLE space_items (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id    UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- A session's override or note. It goes with its session: left behind with a null session it
    -- would read as the project's own value.
    session_id    UUID REFERENCES sessions(id) ON DELETE CASCADE,
    task_id       UUID REFERENCES tasks(id) ON DELETE SET NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('goal', 'milestone', 'convention', 'decision', 'note', 'question')),
    key           TEXT,                                -- conventions only
    body          JSONB NOT NULL,                      -- {"text": …}; conventions {"value": …}
    status        TEXT NOT NULL DEFAULT 'active'
                  CHECK (status IN ('proposed', 'active', 'done', 'dropped', 'superseded')),
    author_kind   TEXT NOT NULL CHECK (author_kind IN ('user', 'orange', 'agent')),
    author_ref    TEXT,                                -- a run or session id for agents
    due_at        TIMESTAMPTZ,                         -- milestones, optional
    supersedes_id UUID REFERENCES space_items(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK ((kind = 'convention') = (key IS NOT NULL))
);

CREATE INDEX idx_space_items_active ON space_items (project_id, kind) WHERE status = 'active';
CREATE INDEX idx_space_items_project ON space_items (project_id, created_at DESC);
-- One active value per convention key, per project and per session override.
CREATE UNIQUE INDEX idx_space_items_one_convention
    ON space_items (project_id, key, coalesce(session_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE kind = 'convention' AND status = 'active';

CREATE TRIGGER space_items_touch_updated_at
    BEFORE UPDATE ON space_items
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Everything that wants the user. Urgent and critical items are pushed; the rest wait for the brief.
CREATE TABLE attention_items (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id      UUID REFERENCES projects(id) ON DELETE CASCADE,
    session_id      UUID REFERENCES sessions(id) ON DELETE CASCADE,
    task_id         UUID REFERENCES tasks(id) ON DELETE CASCADE,
    kind            TEXT NOT NULL,                     -- session_question, session_waiting, session_idle, project_proposed, …
    -- At most one item per key, ever: the same condition (this wait of this session, this
    -- unknown repo) is filed once, and resolving it does not file it again.
    dedupe_key      TEXT,
    urgency         TEXT NOT NULL DEFAULT 'normal' CHECK (urgency IN ('critical', 'urgent', 'normal')),
    title           TEXT NOT NULL,
    body            TEXT NOT NULL DEFAULT '',
    proposed_answer TEXT,
    status          TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'acked', 'resolved')),
    pushed_at       TIMESTAMPTZ,
    resolved_by     TEXT,                              -- user, orange, or the event that made it moot
    resolution      TEXT,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_attention_open ON attention_items (urgency, created_at) WHERE status = 'open';
CREATE UNIQUE INDEX idx_attention_dedupe ON attention_items (dedupe_key)
    WHERE dedupe_key IS NOT NULL;
CREATE INDEX idx_attention_session ON attention_items (session_id) WHERE session_id IS NOT NULL;

CREATE TRIGGER attention_items_touch_updated_at
    BEFORE UPDATE ON attention_items
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- A new item, or one whose urgency rose, wakes the notifier.
CREATE FUNCTION notify_attention() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR OLD.urgency IS DISTINCT FROM NEW.urgency THEN
        PERFORM pg_notify('attention', NEW.id::text);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER attention_items_notify
    AFTER INSERT OR UPDATE ON attention_items
    FOR EACH ROW EXECUTE FUNCTION notify_attention();
