# orange (Claude Code plugin)

One-install front-end for the [orange-ai](../) orchestrator. Ships:

- the `orange` MCP server (HTTP shim over the Spring Boot backend) — 12 tools spanning the planning/dispatch/task surface (plan, dispatch, create, list, task, tail, cancel, replay, reopen) and read-only monitoring (status_live, health, envs)
- the `orange-orchestrate` skill — teaches the chat when and how to drive orange end-to-end (idea → planner → review → dispatch → live narration while the coordinator runs the agents)

## Install (development, from this repo)

The plugin references the sibling `../orange-mcp` directory via `${CLAUDE_PLUGIN_ROOT}`, so it has to be loaded directly from this checkout rather than installed via a marketplace:

```bash
claude --plugin-dir /Users/<you>/stuff/2026/orange-ai/plugin
```

Or, for a persistent local install via a marketplace JSON, see `docs/marketplace.md` (TODO — Phase 8 packaging item).

## Prerequisites

- `uv` on `$PATH` (for the MCP server)
- the orange Spring Boot app running locally — the skill will offer to start it for you (`cd orange && ./gradlew bootRun`)
- Postgres up via `docker compose up -d postgres` in `orange/`

## What the skill triggers on

The skill describes itself as fitting "build a feature", "implement / ship X", "plan + execute a project". When a user asks for non-trivial software development work in chat, the orchestrator skill kicks in: it asks only the follow-up questions the request leaves open, runs the planner, drops the resulting `plan.json` DAG in chat for review (nothing runs yet), and on approve dispatches it; the coordinator then runs the agents, opens a PR per task and reviews it.

For small one-off tasks (a single file edit, a typo fix), the skill explicitly defers to the regular chat — orange is overkill below the multi-task threshold.
