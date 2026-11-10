# orange (Claude Code plugin)

One-install front-end for the [orange-ai](../) orchestrator. Ships:

- the `orange` MCP server (HTTP shim over the Spring Boot backend) — 12 tools spanning the planning/dispatch/task surface (plan, dispatch, create, list, task, tail, cancel, replay, reopen) and read-only monitoring (status_live, health, envs)
- the **session reporter** (`hooks/`) — tells orange when any of your Claude Code sessions starts, gets a prompt, stops, waits for you or ends, so orange can file what needs you and push the urgent part to your phone
- the `orange-steering` MCP server (served by the coordinator at `/mcp/steering`) — the project space, the attention inbox and your sessions: `orange_projects`, `orange_project`, `orange_project_update`, `orange_add`, `orange_set`, `orange_accept`, `orange_drop`, `orange_done`, `orange_attention`, `orange_resolve`, `orange_sessions`
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

The skill describes itself as fitting "build a feature", "implement / ship X", "plan + execute a project". When a user asks for non-trivial software development work in chat, the orchestrator skill kicks in: it asks only the follow-up questions the request leaves open, runs the planner, drops the resulting `plan.json` DAG in chat for review (nothing runs yet), and on approve dispatches it; the coordinator then runs the agents, verifies each task with its own evidence and integrates it.

For small one-off tasks (a single file edit, a typo fix), the skill explicitly defers to the regular chat — orange is overkill below the multi-task threshold.

## The session reporter

For orange to see every session, install the plugin at user scope (or start sessions with `--plugin-dir`). The hooks run `hooks/orange-report.py` (needs `python3`, nothing else) on SessionStart, UserPromptSubmit, Stop, Notification and SessionEnd, plus PostToolUse while a session was waiting for you.

The reporter never gets in a session's way: it prints nothing, always exits 0 and gives up on orange after 2 seconds. What it sends: the session id, the working directory, the repo's main checkout, branch and remote (without credentials), your prompt (first 4,000 characters) and the session's last message at a stop (last 8,000 characters). Full transcripts stay on your machine.

Set these in the shell your sessions start from:

| Variable | Default | Meaning |
|---|---|---|
| `ORANGE_URL` | `http://localhost:8080` | the coordinator |
| `ORANGE_API_TOKEN` | none | bearer token, when the coordinator has one set |
| `ORANGE_NODE` | the hostname | this machine's name in orange |
| `ORANGE_REPORT` | `1` | `0` switches the reporter off |

orange's own agent runs use a private `CLAUDE_CONFIG_DIR`, so the reporter never fires inside them.

The first session in a repo orange has not seen proposes a project for it: answer the attention item with `confirm` or `ignore` (`orange_resolve`), or attach the repo to an existing project with `orange_project_update`.
