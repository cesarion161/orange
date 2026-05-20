---
description: Drive the orange-ai multi-agent feature factory end-to-end from chat — plan a feature into a task DAG, kick off parallel agents, narrate progress live. Triggers on intents like "build X", "implement feature Y", "ship Z", "let's plan and execute this project". Skips for single-file edits, typo fixes, one-off scripts.
---

# orange-orchestrate

You are the **chat orchestrator** in front of the orange-ai backend. Your job is to turn a user's product idea into a parallel multi-agent execution, supervised by the user, without dropping them into raw MCP calls.

## When to invoke this skill

**Use it** when the user asks for:
- "build / implement / ship a feature" that touches > 1 file or > 1 area
- multi-day work with deliberate planning (architecture, dependencies)
- anything where you'd want review or testing gates

**Do NOT use it** for:
- single-file edits, typo fixes, one-off scripts
- pure exploration / "what does this code do"
- direct manual orange-mcp calls (the user invoked a tool by hand)

When in doubt: ask the user "this looks like a multi-task project — should I plan it via orange, or just do it inline?" — give them the choice.

## The journey (skeleton)

The full step-by-step playbook lives in `journey.md` (read it the first time you run this skill in a session). One-line skeleton:

```
intent → server-check → follow-ups → plan → user review → execute → narrate → post-merge
```

1. **server-check** — confirm `http://localhost:8080/actuator/health` returns OK. If not, run `cd orange && ./gradlew bootRun` in the background and wait for "Started OrangeApplication".
2. **follow-ups** — `AskUserQuestion` for: parallel-agents cap, review mode (none / agent / user-only / both), test mode (none / agent), gate order (review-before-test vs after), auth-required.
3. **plan** — by default, you ARE the planner. Create a `planner` task with `orange_create_task`, claim it with `orange_claim_next_task(role=planner)`, draft `plan.json` + `architecture.md` in this chat, submit via `orange_submit_plan(default_pipeline=…)`. The default pipeline is derived from the follow-up answers (see `journey.md` for the mapping).
4. **user review** — print the plan path; ask the user to open it and either approve or describe changes. On change request, write a revised planner prompt and start fresh (planner is stateless — never carry plan state between iterations).
5. **execute** — once approved, the tasks are already in the DB. If you're acting as the worker pool, claim tasks via `orange_claim_next_task(role=dev|tester|…)` and execute each one in this chat. If headless workers are running, just monitor.
6. **narrate** — every ~30s while work is in flight, call `orange_status_live` for one-shot aggregate progress; emit one-line status updates per active task. Call `orange_envs` to surface env occupancy when a test stage is queued.

## Critical rules

- **Don't bypass the chat-as-executor contract.** When you claim a task, you MUST heartbeat every ~30s (`orange_heartbeat`) or the lease expires and the reaper bounces the task. On completion always call `orange_complete_task` / `orange_fail_task` / `orange_release_claim` — never just drop the claim.
- **Honor `cwd` from the claim.** Each task hands you a worktree path; all edits go there. If you need to read repo state outside the worktree, do it before claiming, not during.
- **Per-task pipeline lives in the plan.** When the user says "test everything" / "review only the auth tasks", you express that as `pipeline` per task in `plan.json`, not as a global flag. The submit endpoint will reject typos (422) — surface the error verbatim.
- **Stateless planner.** If the user wants plan changes, build a new prompt that *includes the prior plan plus the requested delta* and submit again. Don't try to amend in place.
- **Cancel hygiene.** If the user cancels mid-execution, call `orange_cancel(task_id)` for the relevant tasks. That flips `cancel_requested=true` on any active claim; your next heartbeat will see it and you should call `orange_release_claim`.

## Tools you'll use (all via the orange MCP)

| When | Tool |
|------|------|
| Check or start orange | Bash `curl localhost:8080/actuator/health` or `./gradlew bootRun` |
| Create planner task | `orange_create_task(title, role="planner")` |
| Become a worker | `orange_claim_next_task(role, worker_id)` |
| Heartbeat | `orange_heartbeat(claim_token)` — every ~30s while holding a claim |
| Done | `orange_complete_task(claim_token, summary, artifacts)` |
| Submit plan | `orange_submit_plan(claim_token, plan, architecture_md, default_pipeline)` |
| Read upstream outputs | `orange_dep_artifacts(task_id)` |
| Status during exec | `orange_status_live(role?, per_task_limit?)`, `orange_envs()` |
| Why is a claim refused | `orange_concurrency()` — at-cap roles surface here |
| Drill into one task | `orange_tail(task_id, since)` |
| Triage (reopen a failed task) | `orange_reopen_task(task_id, reason)` — FAILED/TEST_DONE → READY only |
| User cancel | `orange_cancel(task_id, reason)` |
| Recover after restart | `orange_my_active_claims(worker_id)` |

Reference: `journey.md` for the full playbook, plan-template for the planner prompt shape, and `~/.claude/.../memory/orchestrator_design.md` for why each decision was made.
