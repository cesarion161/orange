# orange (Claude Code plugin)

One-install front-end for the [orange-ai](../) orchestrator: one Claude Code tab steers orange, which plans, runs, checks and merges the work on its own machine. Ships:

- the `orange-steering` MCP server (served by the coordinator at `/mcp/steering`):
  - projects and their space: `orange_projects`, `orange_project`, `orange_project_create`, `orange_project_update`, `orange_add`, `orange_set`, `orange_accept`, `orange_drop`, `orange_done`;
  - work: `orange_plan`, `orange_status`, `orange_task`, `orange_tail`, `orange_cancel`, `orange_pause`, `orange_resume`;
  - what needs you: `orange_attention`, `orange_resolve`, `orange_sessions`, `orange_focus`;
- the `orange` skill: the tab's flow, status → hand over work → follow → answer, and which tool does each;
- the **session reporter** (`hooks/`): tells orange when any of your Claude Code sessions starts, gets a prompt, stops, waits for you or ends, so orange can answer routine stops and file what needs you, and gives the steering tab orange's status when it starts.

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
- an orange coordinator to talk to (`ORANGE_URL`, and `ORANGE_API_TOKEN` when it has one); with it down, sessions work as if the plugin were not there

## The steering tab

The steering tab is the one Claude Code session you talk to orange in. A session is the steering tab when Claude Code starts with `ORANGE_STEERING=1` in its environment, or in `~/.orange/steering` (or below it; `ORANGE_STEERING_DIR` names another directory). An alias makes it one word:

```bash
alias orange='mkdir -p ~/.orange/steering && cd ~/.orange/steering && claude'
```

`ORANGE_STEERING=1 claude` works too, from any directory.

- **It starts from facts.** At its start (and after `/clear`) the reporter asks orange for a few lines of status (`GET /steering/context`) and adds them to the session's context: active projects, what is running, what waits for you. With orange out of reach it starts without them.
- **orange never judges it.** Its stops are you talking to orange: orange records the session, but queues no judge for it, files nothing about it and maps it to no project, whatever directory it runs in.
- **Nothing lives in it.** Projects, plans, tasks and questions are all in orange: clear the tab or open a new one whenever you like.

What you say there:

- "what's the status", "are we on track": `orange_status`; "what's new", "where should I focus": `orange_focus`;
- "start a project X on owner/repo" (or "with a new repo"): `orange_project_create`; give it the commands that prove work is done (`evidence`), or orange can implement there but never merges;
- "build X in Y": `orange_plan`. orange plans in the background and then dispatches the tasks itself, or asks you first with one attention item: always when the project has no evidence commands yet, otherwise when the plan is large or risky or the project's `plan-approval` convention says `always`;
- "what is task 1a2b3c4d doing": `orange_task`, `orange_tail`; "cancel it": `orange_cancel`; "stop everything": `orange_pause`, then `orange_resume`;
- "what needs me": `orange_attention`; answer with `orange_resolve`: `approve`, `drop` or a note for a plan; for a stuck task, the answer reopens it with the answer as its note. A stuck task's branch is on GitHub as `orange/task/<short id>` so you can look at it first;
- milestones: `orange_add` with `kind: milestone` and a `due` date, then plan work with `milestone`; the status and the brief then say whether it is on track or behind, from orange's own count.

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
| `ORANGE_STEERING` | none | `1` makes the session the steering tab |
| `ORANGE_STEERING_DIR` | `~/.orange/steering` | a session at or under it is the steering tab |

orange's own agent runs use a private `CLAUDE_CONFIG_DIR`, so the reporter never fires inside them.

The first session in a repo orange has not seen proposes a project for it: answer the attention item with `confirm` or `ignore` (`orange_resolve`), or attach the repo to an existing project with `orange_project_update`.

## The focus brief

At the brief times (09:00 and 14:00 by default, never in quiet hours) orange writes a brief across your active projects: where each one stands, what is waiting on you and what to focus on next. It pushes the brief to ntfy (to `ORANGE_NTFY_BRIEF_TOPIC`, or without one to the attention topic), unless nothing happened since the one before.

`orange_focus` returns the latest brief from any session; `fresh: true` writes a new one, which can take a minute (at most one every 10 minutes). Every brief has a page (`page` in the tool's answer; `/brief` on the coordinator for the latest) that shows the lines beside the facts they were written from. The counts and the dates are orange's own, read from its database; the model only adds the words, and when it cannot be asked the brief is written from the facts alone.
