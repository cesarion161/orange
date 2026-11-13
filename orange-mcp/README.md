# orange-mcp

MCP server that exposes the [orange-ai](../orange) coordinator's REST API as
tools Claude Code can call. Lets you stay in your Claude Code session and
say "build a TODO app with auth": Claude calls the planner to get a DAG,
you review it, then dispatch it. Orange then runs every task itself
(implement → verify with its own evidence commands → a diff review when the `review` convention asks → integrate),
and Claude narrates progress and relays the tasks that need a person.

Planning and dispatch are **two independent steps**: `orange_plan` produces a
plan DAG and creates nothing; `orange_dispatch` runs a plan (inline or by saved
`planId`). Review — or don't — in between.

## Tool surface

| Tool | What it does | REST shim |
|---|---|---|
| `orange_plan` | Run the planner on a feature description, return the plan DAG (creates nothing) | `POST /plans` |
| `orange_dispatch` | Run a plan (inline or by `plan_id`): insert its tasks; orange's run loop takes it from there | `POST /dispatch` |
| `orange_create_task` | Single-task create (no planner) | `POST /tasks` |
| `orange_list_tasks` | Recent tasks with optional status filter | `GET /tasks` |
| `orange_task` | One task's current state | `GET /tasks/{id}` |
| `orange_tail` | A task's events, then its latest run's events, for live narration | `GET /tasks/{id}/events`, `GET /tasks/{id}/runs`, `GET /runs/{id}/events` |
| `orange_cancel` | Cancel a task: its queued runs never start, its running agent is killed | `POST /tasks/{id}/cancel` |
| `orange_replay_task` | Run a task again as a new task | `POST /tasks/{id}/replay` |
| `orange_reopen_task` | Send a failed or needs-human task back to ready, with a note for the next attempt | `POST /tasks/{id}/reopen` |
| `orange_status_live` | Every running run with its task and latest events | `GET /events/live` |
| `orange_health` | Tasks by status, runs queued/running, the scheduler pause, envs, spend | `GET /health/aggregate` |
| `orange_envs` | Dev environments and who holds them | `GET /dev-envs` |

Runs, their event streams and a task's evidence are also readable directly:
`GET /tasks/{id}/runs`, `GET /runs/{id}`, `GET /runs/{id}/events?since=&limit=`,
`GET /tasks/{id}/evidence`. The scheduler's stop switch is `GET /scheduler`,
`POST /scheduler/pause` (`{reason?, minutes?}`) and `POST /scheduler/resume`.

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

This shim is the operator's surface. The agents orange runs talk to a different
endpoint, orange's own `/mcp/worker`, with a per-run token.

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
- `/orange-cancel <task-id>` — cancel a task

They're thin prompt templates that tell Claude to call the corresponding MCP tool.
