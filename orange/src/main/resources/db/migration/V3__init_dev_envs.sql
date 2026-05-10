CREATE TABLE dev_envs (
    id           UUID PRIMARY KEY DEFAULT uuidv7(),
    name         TEXT NOT NULL UNIQUE,                     -- 'env-1' .. 'env-5'
    ports        JSONB NOT NULL,                           -- {"web":3000,"api":8080,"db":5432}
    status       TEXT NOT NULL DEFAULT 'free'
                 CHECK (status IN ('free','leased','disabled')),
    leased_by    TEXT,                                     -- task id, agent run id, or worker id
    leased_at    TIMESTAMPTZ,
    released_at  TIMESTAMPTZ,
    metadata     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK ((status = 'leased') = (leased_by IS NOT NULL AND leased_at IS NOT NULL))
);

-- Hot path: pick the first free env with FOR UPDATE SKIP LOCKED.
CREATE INDEX idx_dev_envs_free ON dev_envs (id) WHERE status = 'free';

CREATE TRIGGER dev_envs_touch_updated_at
    BEFORE UPDATE ON dev_envs
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- Wake any LISTEN'er on dev_env_freed when an env returns to 'free'.
-- This is what lets agents wait for an env without polling.
CREATE OR REPLACE FUNCTION notify_dev_env_freed() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'free' AND (OLD.status IS DISTINCT FROM 'free') THEN
        PERFORM pg_notify('dev_env_freed', NEW.id::text);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER dev_envs_notify_freed
    AFTER UPDATE ON dev_envs
    FOR EACH ROW EXECUTE FUNCTION notify_dev_env_freed();

-- Seed a default pool matching the user's example (5 envs, 3 components each).
-- Override via INSERT/UPDATE in a project-specific R__seed.sql or by editing rows.
INSERT INTO dev_envs (name, ports) VALUES
    ('env-1', '{"web": 3000, "api": 8080, "db": 5432}'::jsonb),
    ('env-2', '{"web": 3001, "api": 8081, "db": 5433}'::jsonb),
    ('env-3', '{"web": 3002, "api": 8082, "db": 5434}'::jsonb),
    ('env-4', '{"web": 3003, "api": 8083, "db": 5435}'::jsonb),
    ('env-5', '{"web": 3004, "api": 8084, "db": 5436}'::jsonb);
