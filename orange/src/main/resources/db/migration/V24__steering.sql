-- The steering tab drives tasks (remote-first spec §7.2, §7.5, §7.6; R4).

-- A plan: the user's request for a project, what the planner made of it and what orange decided.
-- planning → dispatched (orange's rule let it go) or asking (one attention item) → dispatched or dropped;
-- failed when the planner did not deliver. A note on an asking plan starts a new plan that revises it.
CREATE TABLE plans (
    id           UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id   UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    request      TEXT NOT NULL,                   -- the user's own words
    note         TEXT,                            -- the user's revision note, when this plan revises another
    revises_id   UUID REFERENCES plans(id) ON DELETE SET NULL,
    status       TEXT NOT NULL DEFAULT 'planning'
                 CHECK (status IN ('planning', 'asking', 'dispatched', 'dropped', 'failed')),
    ask_first    BOOLEAN,                         -- orange's decision: ask the user before dispatching
    reason       TEXT,                            -- why it asks, one line for the user
    plan         JSONB,                           -- the planner's plan.json
    architecture TEXT,
    failure      TEXT,                            -- why the planner did not deliver
    milestone_id UUID REFERENCES space_items(id) ON DELETE SET NULL,
    run_id       UUID REFERENCES runs(id) ON DELETE SET NULL,
    attention_id UUID,                            -- the plan_approval item, while it asks
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_plans_open ON plans (status) WHERE status IN ('planning', 'asking');
CREATE INDEX idx_plans_project ON plans (project_id, created_at DESC);
CREATE TRIGGER plans_touch_updated_at
    BEFORE UPDATE ON plans
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- A planned task remembers the plan it came from and the milestone it serves. The link points at the
-- milestone item it was made for; readers follow the supersede chain to the current one.
ALTER TABLE tasks ADD COLUMN plan_id      UUID REFERENCES plans(id) ON DELETE SET NULL,
                  ADD COLUMN milestone_id UUID REFERENCES space_items(id) ON DELETE SET NULL;
CREATE INDEX idx_tasks_milestone ON tasks (milestone_id) WHERE milestone_id IS NOT NULL;

-- The steering tab's own session: recorded, never judged, never mapped to a project.
ALTER TABLE sessions ADD COLUMN steering BOOLEAN NOT NULL DEFAULT false;
