# orange-mcp

MCP server that exposes the [orange-ai](../orange) orchestrator's REST API as
tools Claude Code can call. Lets you stay in your Claude Code session and
say "build a TODO app with auth" — Claude calls the planner to get a DAG,
you review it, then dispatch it; Claude watches progress, narrates events as
they happen, and asks for input when reviewers gate on you.

Planning and dispatch are **two independent steps**: `orange_plan` produces a
plan DAG and creates nothing; `orange_dispatch` runs a plan (inline or by saved
`planId`). Review — or don't — in between.

## Tool surface

| Tool | What it does | REST shim |
|---|---|---|
| `orange_plan` | Run the planner on a feature description, return the plan DAG (creates nothing) | `POST /plans` |
| `orange_dispatch` | Run a plan (inline or by `plan_id`): insert tasks + start work | `POST /dispatch` |
| `orange_create_task` | Single-task create (no planner) | `POST /tasks` |
| `orange_list_tasks` | Recent tasks with optional status filter | `GET /tasks` |
| `orange_task` | One task's current state | `GET /tasks/{id}` |
| `orange_tail` | Recent `task_events` for live narration | `GET /tasks/{id}/events` |
| `orange_cancel` | Terminate workflow + mark cancelled | `POST /tasks/{id}/cancel` |

## Quickstart

```sh
# 1. Install (uses uv)
cd orange-mcp
uv sync

# 2. Verify it boots and quits cleanly when stdin closes
echo '' | uv run python -m orange_mcp
```

Then register it with Claude Code. Add to your `~/.claude/mcp.json` (or your
project's `.claude/mcp.json`):

```json
{
  "mcpServers": {
    "orange": {
      "command": "uv",
      "args": ["--directory", "/absolute/path/to/orange-mcp", "run", "orange-mcp"],
      "env": {
        "ORANGE_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

Restart Claude Code. Tools will appear as `orange_plan`, `orange_list_tasks`, etc.

## Env vars

| Var | Default | Meaning |
|---|---|---|
| `ORANGE_BASE_URL` | `http://localhost:8080` | Orange-ai REST base URL |

## Slash commands

For keystroke-friendly access to common ops, project-level slash commands live
in `.claude/commands/` at the repo root:

- `/orange-plan <description>` — fast planner invocation (plan-only)
- `/orange-dispatch <plan-id|plan-file>` — run a reviewed plan
- `/orange-status` — one-line summary of in-flight tasks
- `/orange-cancel <task-id>` — terminate

They're thin prompt templates that tell Claude to call the corresponding MCP tool.
