-- v2 PR 2: every agent run is a `runs` row (V15), and role prompts and tools live in code
-- (src/main/resources/roles/*.md, roles/ToolPolicy). The v1 per-attempt history and the seeded
-- agent rows have no readers left. agent_runs references agents, so it goes first.
DROP TABLE agent_runs;
DROP TABLE agents;
