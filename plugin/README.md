# orange (Claude Code plugin)

One-install front-end for the [orange-ai](../) orchestrator. Ships:

- the **session reporter** (`hooks/`) — tells orange when any of your Claude Code sessions starts, gets a prompt, stops, waits for you or ends, so orange can file what needs you and push the urgent part to your phone
- the `orange-steering` MCP server (served by the coordinator at `/mcp/steering`) — the project space, the attention inbox, your sessions and the focus brief: `orange_projects`, `orange_project`, `orange_project_update`, `orange_add`, `orange_set`, `orange_accept`, `orange_drop`, `orange_done`, `orange_attention`, `orange_resolve`, `orange_sessions`, `orange_focus`
- the `orange-orchestrate` skill — teaches the chat when and how to drive orange end-to-end (idea → planner → review → dispatch → live narration while the coordinator runs the agents)

## Install

The repository is its own marketplace (`.claude-plugin/marketplace.json`). From a checkout:

```bash
claude plugin marketplace add /path/to/orange-ai
claude plugin install orange@orange-local
```

That installs at user scope, so every Claude Code session on this machine reports to orange. Hooks load when a session starts: restart sessions that were already open. The install is a copy; after pulling changes to `plugin/`, run `claude plugin marketplace update orange-local` and `claude plugin update orange@orange-local`.

For a single session without installing: `claude --plugin-dir /path/to/orange-ai/plugin`.

## Prerequisites

- `python3` on `$PATH` (the reporter; standard library only)
- the orange coordinator running (`cd orange && ./gradlew bootRun`); with it down, sessions work as if the plugin were not there

The task tools (`orange_plan`, `orange_dispatch`, …) are not part of the plugin: they come from the `orange` MCP server in this repository's own `.mcp.json` (the Python shim in `orange-mcp/`, which needs `uv`), so they are available in sessions inside this repo until v2 PR 5 moves them into the coordinator.

## What the skill triggers on

The skill describes itself as fitting "build a feature", "implement / ship X", "plan + execute a project". When a user asks for non-trivial software development work in chat, the orchestrator skill kicks in: it asks only the follow-up questions the request leaves open, runs the planner, drops the resulting `plan.json` DAG in chat for review (nothing runs yet), and on approve dispatches it; the coordinator then runs the agents, verifies each task with its own evidence and integrates it.

For small one-off tasks (a single file edit, a typo fix), the skill explicitly defers to the regular chat — orange is overkill below the multi-task threshold.

## The session reporter

For orange to see every session, install the plugin at user scope (or start sessions with `--plugin-dir`). The hooks run `hooks/orange-report.py` (needs `python3`, nothing else) on SessionStart, UserPromptSubmit, Stop, Notification and SessionEnd, plus PostToolUse while a session was waiting for you.

The reporter never gets in a session's way: it always exits 0 and gives up on an unreachable orange after 2 seconds.

At a stop, orange's judge decides what happens next. While it decides (usually seconds; at most `ORANGE_HOOK_WAIT`), the Stop hook waits. If orange answers the session itself, the hook blocks the stop with that answer and the session continues; you find the answer and its reason as a decision in the project's space (`orange_project`). Otherwise the session stops as usual, and what needs you is in `orange_attention`. Typing a prompt yourself always wins: it voids whatever orange was about to say. Set the project convention `auto-answer` to `none` (`orange_set`) to have orange never answer for you there. What it sends: the session id, the working directory, the repo's main checkout, branch and remote (without credentials), your prompt (first 4,000 characters) and the session's last message at a stop (last 8,000 characters). Full transcripts stay on your machine.

Set these in the shell your sessions start from:

| Variable | Default | Meaning |
|---|---|---|
| `ORANGE_URL` | `http://localhost:8080` | the coordinator |
| `ORANGE_API_TOKEN` | none | bearer token, when the coordinator has one set |
| `ORANGE_NODE` | the hostname | this machine's name in orange |
| `ORANGE_REPORT` | `1` | `0` switches the reporter off |
| `ORANGE_HOOK_WAIT` | `600` | seconds a Stop hook waits for orange's verdict; `0` = never wait |

orange's own agent runs use a private `CLAUDE_CONFIG_DIR`, so the reporter never fires inside them.

The first session in a repo orange has not seen proposes a project for it: answer the attention item with `confirm` or `ignore` (`orange_resolve`), or attach the repo to an existing project with `orange_project_update`.

## The focus brief

At the brief times (09:00 and 14:00 by default, never in quiet hours) orange writes a brief across your active projects: where each one stands, what is waiting on you and what to focus on next. It pushes the brief to ntfy (to `ORANGE_NTFY_BRIEF_TOPIC`, or without one to the attention topic), unless nothing happened since the one before.

`orange_focus` returns the latest brief from any session; `fresh: true` writes a new one, which can take a minute (at most one every 10 minutes). Every brief has a page (`page` in the tool's answer; `/brief` on the coordinator for the latest) that shows the lines beside the facts they were written from. The counts and the dates are orange's own, read from its database; the model only adds the words, and when it cannot be asked the brief is written from the facts alone.
