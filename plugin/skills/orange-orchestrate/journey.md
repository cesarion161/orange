# orange-orchestrate journey playbook

Read this once per session when you first decide to use the orange-orchestrate skill. It expands every step of the skeleton in `SKILL.md` with concrete commands, decision rules, and what to say to the user.

## Step 1 — Detect the intent

User messages that fit:
- "Build me a TODO app with auth."
- "Implement a multi-step checkout flow."
- "Plan and ship a Stripe integration."
- "I want to refactor X across the codebase."

Borderline cases — ASK before assuming:
- "Add an endpoint to do X" → could be 5 minutes inline or a 4-task project. Ask: *"This sounds focused — would you like me to do it inline, or plan it through orange?"*

If the user explicitly says "no orange, just do it" — defer entirely; don't keep offering.

## Step 2 — Server check + start

```bash
curl -sf http://localhost:8080/actuator/health
```

If 200, proceed. If connection refused:
```bash
cd /Users/<user>/path/to/orange-ai/orange && docker compose up -d postgres && ./gradlew bootRun
```
(Run in background.) Wait for the log line `Started OrangeApplication in N seconds`. Don't proceed until you see it — the MCP calls will fail with `ECONNREFUSED` otherwise.

## Step 3 — Follow-up questions

Use `AskUserQuestion` only for what the request leaves open: scope boundaries, constraints (libraries, platforms), whether auth already exists. Don't ask about review or test gates — orange verifies every task with the configured evidence commands and a diff review before it integrates it.

## Step 4 — Plan

```
orange_plan(description=<the user's full request plus the follow-up answers>)
```

The coordinator runs the planner agent on the Max plan. It returns a `planId`, the plan DAG and `outputDir`, the absolute path containing `plan.json` and `architecture.md`. **Nothing is running yet.**

**Plan-sizing rules** (from memory: project_planner_scope.md):
- Cap at ~7 tasks for a vague goal. If the user wants more detail, ask which task to expand and rerun planning with that as the input.
- Each task should be sized for one focused agent session (30-90 minutes of work) and one reviewable change.
- Don't pre-decompose if the architecture is still unclear — output a coarse plan and let the user signal where to drill in.

## Step 5 — User review of the plan

This is the review gate. Tell the user:

> "Plan `<planId>` written to `<outputDir>`. Review `plan.json` and `architecture.md`. Reply:
> - `approve` to dispatch it (this starts the agents)
> - tell me what to change (e.g. 'split task 3 into two') and I'll re-plan
> - edit the file yourself and say `re-read it` — I'll dispatch your edited version"

On a change request: call `orange_plan` again with a description that embeds the prior plan PLUS the user's delta. The planner is stateless. Because nothing was dispatched, re-planning is free.

On a file edit: read the edited `plan.json` and dispatch it inline (step 5.5).

## Step 5.5 — Dispatch (on approval)

```
orange_dispatch(plan_id=<planId from step 4>)
```

Or dispatch an edited plan inline: `orange_dispatch(plan=<plan.json content>)`. Dispatch validates the DAG (acyclic), inserts the tasks, and marks the dependency-free ones `ready`. A 422 means the plan was rejected (read `reason`); a 404 means the `plan_id` has no saved plan.

## Step 6 — Execution (the coordinator does it)

For each `ready` task the coordinator:
1. starts an **implement run** (Claude Code in the task's own worktree, on the Max plan);
2. when the agent reports done and has committed, moves the task to **`verifying`**;
3. **verifies** it: runs the configured evidence commands itself (tests, build), checks the worktree is clean, and, for risky tasks or when the project's `review` convention asks, has an LLM **diff review** compare the change with the task's intent. Any gap sends the task back to `running` with the gaps as feedback for a fix run;
4. **integrates** a `verified` task: rebases it onto the base branch (re-verifying if that changed anything), fast-forwards the base and pushes it. The task is `integrated`, and its dependents become `ready`.

A task whose runs keep failing, or whose agent asks a question, moves to `needs_human`. Tell the user what it is waiting on; `orange_reopen_task(task_id, reason)` retries it.

**Anti-pattern (do NOT do this):** taking a task's prompt and editing files in this chat yourself. The coordinator already runs the agents in isolated worktrees; doing it here serializes the work and bypasses verification.

## Step 7 — Narrate progress

Every 30-60s while there's work in flight, print a one-line per-run summary via `orange_status_live`:

```
orange_status_live(role=null, per_task_limit=3)
  → [{ run_id, task_id, task_title, kind, role, model, started_at, recent: [{seq, type, payload}, ...] }, ...]
```

Render one line per running run: truncated task title + run kind (implement, ci_fix, verify, diff_review, integrate) + its last event (`recent` is newest first). Track the latest `seq` per run; on the next poll, only narrate runs that moved. Use `orange_health()` for a one-line system summary; it also says when the scheduler is paused and why.

If nothing is in flight and nothing is ready, report what has been integrated, which tasks wait for the user (`needs_human`), and whether the scheduler is paused (`orange_health()`).

## Failure modes & recovery

| What happened | What you do |
|---|---|
| Dispatch returns 422 | Read `reason` — usually a cycle or a duplicate task key. Fix the plan and re-dispatch. |
| Dispatch returns 404 | The `plan_id` has no saved plan.json. Re-plan, or dispatch the plan inline. |
| A task is `needs_human` | `orange_tail(task_id)` shows why. Relay it to the user; retry with `orange_reopen_task` once they decide. |
| A task is `failed` | Same: inspect, then `orange_reopen_task` if the user wants another attempt. |
| Server unreachable | Tell the user and offer to restart `./gradlew bootRun`. Runs left behind are failed and retried on restart. |
| Nothing moves; `orange_health()` says `PAUSED` | Orange paused itself on a failure that would recur at once. A plan limit resumes on its own at the reset time; a rejected or missing token, a missing `claude` binary or a broken base repo needs the user to fix it (`orange/.env` changes need a restart), then `curl -X POST localhost:8080/scheduler/resume`. |
| User asked to cancel | `orange_cancel(task_id)` for each in-flight task; it kills the running agent too. |

## What goes in the plan

`plan-template.md` shows the plan shape the planner produces and you may edit.
