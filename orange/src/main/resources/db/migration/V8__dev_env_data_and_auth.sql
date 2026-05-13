-- Flesh out the seeded dev_envs with per-env data + tester-onboarding metadata.
-- We use the existing JSONB `metadata` column rather than adding typed columns
-- so the wire shape stays flexible (different projects need different keys —
-- web framework, db engine, fixture set name, etc.).
--
-- Keys added per env:
--   data_dir         — host directory the env's storage lives in. The
--                      docker-compose orchestrator (Phase 8 follow-up) mounts
--                      this as the env's volume so parallel testers don't
--                      poison each other's data.
--   auth_bypass_token — short stable token the tester prompt surfaces. The
--                      running app honors it ONLY when bound to a leased
--                      dev_env (never prod) — see `orange.envs.test-bypass`
--                      config. Saves testers from automating login flows.
--   fixture_set       — name of the seed dataset loaded into the env's storage.
--                      'default' for now; can be specialized per-env later.

UPDATE dev_envs SET metadata = jsonb_build_object(
    'data_dir', '/var/orange/envs/' || name || '/data',
    'auth_bypass_token', 'env-' || substr(md5(id::text), 1, 12),
    'fixture_set', 'default'
) || metadata
WHERE metadata->>'data_dir' IS NULL;

-- The `|| metadata` order means user-provided overrides in metadata win over
-- our defaults — operators can edit a row's metadata to customize without
-- losing it on the next reseed.
