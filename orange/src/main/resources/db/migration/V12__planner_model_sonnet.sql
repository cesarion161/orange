-- Pin the planner agent's model to claude-sonnet-4-6.
--
-- V5 seeded the planner with model 'claude-opus-4-7'. As of 2026-05 that model
-- returns an Anthropic 400 ("role 'system' is not supported on this model")
-- through the agent-runner's bundled Claude Code CLI, so the planner produced
-- no plan.json and every planning request failed. claude-sonnet-4-6 (the model
-- the dev/tester/reviewer/triage agents already use) works. This was applied as
-- a manual DB UPDATE during incident triage; this migration makes it durable so
-- a fresh DB / re-seed doesn't reintroduce the broken model.
--
-- Idempotent: a plain UPDATE of the existing 'planner' row (seeded in V5).
UPDATE agents
   SET model = 'claude-sonnet-4-6',
       updated_at = NOW()
 WHERE name = 'planner'
   AND model <> 'claude-sonnet-4-6';
