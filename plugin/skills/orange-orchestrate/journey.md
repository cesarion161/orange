# orange-orchestrate journey playbook

Read this once per session when you first decide to use the orange-orchestrate skill. It expands every step of the skeleton in `SKILL.md` with concrete commands, decision rules, and what to say to the user.

## Step 1 — Detect the intent

User messages that fit:
- "Build me a TODO app with auth."
- "Implement a multi-step checkout flow."
- "Plan and ship a Stripe integration."
- "I want to refactor X across the codebase."

Borderline cases — ASK before assuming:
- "Add an endpoint to do X" → could be 5 minutes inline or a 4-task project. Ask: *"This sounds focused — would you like me to do it inline, or plan it through orange (review/test gates)?"*

If the user explicitly says "no orange, just do it" — defer entirely; don't keep offering.

## Step 2 — Server check + start

```bash
curl -sf http://localhost:8080/actuator/health
```

If 200, proceed. If connection refused:
```bash
cd /Users/<user>/path/to/orange-ai/orange && ./gradlew bootRun
```
(Run in background.) Wait for the log line `Started OrangeApplication in N seconds`. Don't proceed until you see it — the MCP calls will fail with `ECONNREFUSED` otherwise.

If Temporal isn't running yet, also:
```bash
cd /Users/<user>/path/to/orange-ai/orange && docker compose up -d
```

## Step 3 — Follow-up questions

Use `AskUserQuestion`. Recommended 4-question batch:

1. **Parallelism cap**: "How many agents should work in parallel?" — options: `1 (one task at a time)`, `3 (small team)`, `5 (default)`, `unlimited`.
   - Translate to `orange.concurrency.per-role` config. Cap is approximate (race-prone past the threshold by 1, fine for throttling). When `orange_claim_next_task` returns null and `orange_concurrency` shows a role at-cap, that's the reason — tell the user.
2. **Review mode**: "How should code be reviewed?" — `no review`, `agent reviewer`, `you review PRs only`, `both`.
3. **Test mode**: "How should tasks be tested?" — `no automated test stage`, `agent tester runs the suite`, `agent tester runs targeted smoke tests`.
4. **Gate order**: "If review and test are both on, which runs first?" — `review then test (default)`, `test then review`.

Optional 5th if relevant: **Auth handling** — "Does this feature touch authentication?" — `no`, `yes — auth-introducing tasks come first`, `yes — auth already exists`.

Translate the answers into a `default_pipeline`:
- review off & test off → `dev_only`
- review off & test on → `dev_qa`
- review on (agent) & test on → `dev_review_qa`
- review = user-only — still `dev_only` for the agent pipeline (user reviews the PR via GitHub)
- review on & test off — currently no pre-registered pipeline; either fall back to `dev_only` and tell the user, or add a new pipeline before proceeding.

## Step 4 — Plan (chat-as-planner)

Default: you are the planner. Don't shell out to the subprocess planner unless the user explicitly asked for a headless plan (saves API spend, fits the Max-subscription thesis).

```
1. orange_create_task(title="Plan: <feature short name>", role="planner",
                       pipeline="dev_only", description=<user's full request>)
2. orange_claim_next_task(role="planner", worker_id=<your-session-id>)
   → returns claim_token, prompt (= the description), cwd
3. Read the planner system prompt with `orange_task(planner-task-id)` if you need it inline.
4. In this chat, draft:
     - architecture.md (problem statement, approach, components, risks)
     - plan.json (tasks + edges + per-task pipeline + overrides)
   Use plan-template.md as a starting structure.
5. orange_submit_plan(
       claim_token=<token>,
       plan=<plan.json content>,
       architecture_md=<architecture.md content>)
6. Heartbeat in between #4 and #5 if drafting takes > 30s.
```

Submit is **plan-only**: it saves the plan and auto-completes the planner claim, but creates **no tasks** — nothing runs yet. The response carries a `planId`; you dispatch that later (step 5.5). Pipeline selection (`default_pipeline`, derived from the follow-ups) is supplied at dispatch, not here.

**Plan-sizing rules** (from memory: project_planner_scope.md):
- Cap at ~7 tasks for a vague goal. If the user wants more detail, ask which task to expand and rerun planning with that as the input.
- Each task should be sized for one focused agent session (rough estimate: 30-90 minutes of work).
- Don't pre-decompose if the architecture is still unclear — output a coarse plan and let the user signal where to drill in.

## Step 5 — User review of the plan

After submit, the response carries the `planId`, the plan DAG, and `outputDir` — the absolute path containing `plan.json` and (optionally) `architecture.md`. **Nothing is running yet** — this is the review gate. Tell the user:

> "Plan `<planId>` written to `<outputDir>`. Review `plan.json` and `architecture.md`. Reply:
> - `approve` to dispatch it (this starts the agents)
> - tell me what to change (e.g. 'split task 3 into two', 'make task 5 a tester role') and I'll re-plan
> - edit the file yourself and say `re-read it` — I'll re-ingest"

On change request: write a new planner prompt that embeds the prior plan PLUS the user's delta, then go back to step 4. The planner is stateless — never try to amend an existing plan iteratively. Because nothing was dispatched, re-planning is free — no tasks to cancel.

On file-edit: read the edited `plan.json` and dispatch it inline (step 5.5) rather than re-saving.

## Step 5.5 — Dispatch (on approval)

Dispatch is the independent step that actually starts work. Only do it once the user approves.

```
orange_dispatch(plan_id=<planId from step 4>,
                default_pipeline=<derived from the step-3 follow-ups>)
```

Or dispatch an edited/re-imported plan inline: `orange_dispatch(plan=<plan.json content>, default_pipeline=…)`. Dispatch validates the DAG (acyclic, pipelines resolve), inserts the tasks, and marks the dependency-free ones `ready` — agents pick them up from there. The response lists the created tasks; proceed to step 6 to execute/narrate. A 422 means the plan was rejected (read `reason` — usually an unknown pipeline); a 404 means the `plan_id` has no saved plan.

## Step 6 — Execute

### Dev envs are automatic for tester stages

When a stage is marked `requiresEnv` (currently: every `tester` stage in `dev_qa` and `dev_review_qa`), the workflow leases one of the `dev_envs` rows from the pool BEFORE invoking the agent. The lease info is threaded into the tester prompt as a block that includes:

- per-port URLs (`http://localhost:<port>`)
- `data_dir` — the env's storage root
- `auth_bypass_token` — *opt-in convention*; surfaced only when the env's metadata sets it. The token is meaningless unless the user's project explicitly honors it (e.g. an `X-Orange-Auth-Bypass` header check in dev mode). Orange itself enforces nothing — auth is the project's concern.
- `fixture_set` — name of the seed dataset loaded

The tester is told to prefer (in order): the bypass token if the project honors it, then `./login.sh` from the worktree root if present, then any documented test credentials. The env is released after the stage exits regardless of outcome.

This is the bottleneck enforcement story: tester parallelism is capped at `dev_envs.count` (currently 5 seeded). Dev stages don't need an env. If acquire times out (default 30 min), the stage fails — tell the user via `orange_envs` and either bump the pool or wait.

### Execution modes

You have two execution modes:

**Mode A — chat-as-executor via sub-agents (default, no API spend, parallel).** This is the way. You — the orchestrator chat — never do the work yourself, and you never hold a claim. You spawn one sub-agent per task via the `Agent` tool; each sub-agent **claims its own task**, does the work, and completes. Sub-agents inherit your MCP toolset and your Max subscription, get isolated context windows (so they don't pollute the user's monitoring view), and return a one-line summary back to you.

Per polling cycle:

1. **Decide the batch size.** Look at the ready queue (`orange_list_tasks(status="ready")`) and per-role concurrency (`orange_concurrency`). Spawn `N = min(ready_count_for_role, role_cap - in_flight)` sub-agents for that role. If `N=0`, skip; if some tasks are ready but you're at cap, wait and re-poll.
2. **Spawn N sub-agents in a SINGLE message** (Anthropic batches Agent calls in one message → they run concurrently). Each sub-agent prompt should be self-contained and instruct them to:
   - Pick a unique `worker_id` like `subagent-<role>-<short-uuid>`.
   - Call `orange_claim_next_task(role=…, worker_id=…)`. The response gives them `task_id`, `claim_token`, `prompt`, `cwd` (worktree path), `deps`. If the response is null/empty, the queue raced empty — return "no task" and exit cleanly.
   - If `deps` is non-empty, call `orange_dep_artifacts(task_id)` to read what the upstream tasks produced.
   - Do the work in `cwd`. Commit on the branch already checked out in the worktree (`orange/<task-id>`). Use `-c user.email=worker@orange -c user.name="…"` so commits don't need global git config.
   - If the work might exceed the lease TTL (default 2min), call `orange_heartbeat(claim_token)` every ~60s. For short tasks (<2min) skip.
   - When done: call `orange_complete_task(claim_token, summary, artifacts)`. **Crucially**: `artifacts` SHOULD include `pr_url`/`pr_repo`/`pr_number` if (and only if) they actually pushed and opened a real PR somewhere. Omit them otherwise — the orchestrator transitions the task to `dev_ready` ("work done, no PR pending") when `pr_url` is absent, rather than lying with a fake `pr_open`.
   - On unrecoverable failure, call `orange_fail_task(claim_token, reason, retryable=…)`.
   - Return ONE short line to the orchestrator: the task title (or id), the final status (`dev_ready` / `pr_open` / `failed`), and the commit SHA if applicable. Nothing else — keep the orchestrator's context clean.
3. **While sub-agents run, you monitor.** Poll `orange_status_live(role=…)` every ~10s and surface a one-line-per-active-task summary to the user (step 7). Don't hold the chat hostage — go quiet between polls.
4. **When sub-agents return**, aggregate their one-line summaries into your status update. Sub-agents have already called complete; the dashboard will reflect the new states.

If the plan has `tester` or `reviewer` tasks, same pattern: once a dev task lands in `dev_ready`, the tester role's queue picks up a downstream tester task (deps were transitive in `task_edges`). Spawn tester sub-agents the same way.

**curl-as-fallback** (development-time only): during development of the MCP server itself the orchestrator's tool snapshot may be stale (Claude Code reads MCP tools at session start). If `orange_claim_next_task` and friends aren't available as tools, sub-agents can `curl` the equivalent REST endpoints directly — `POST /claims/next`, `POST /claims/{token}/complete`, etc. Same wire format the MCP tools shim onto. This is not the prescribed pattern; the prescribed pattern is MCP tools in a fresh session.

**Mode B — headless workers (API tokens).** If the user has the subprocess workers running (workflow `auto-start=true` with `ANTHROPIC_API_KEY` configured), they'll pick up tasks automatically via the Temporal `PipelineWorkflow`. You just narrate progress (step 7). This costs API tokens; prefer Mode A unless the user explicitly wants headless.

**Anti-pattern (do NOT do this):** taking the prompt and editing files in this chat session yourself. That serializes every task and burns the user-facing context. The whole point of orange is parallelism + isolation; collapsing it into one chat defeats the architecture.

## Step 7 — Narrate progress

Every 30-60s while there's work in flight, print a one-line per-task summary to the user via `orange_status_live`:

```
orange_status_live(role=null, per_task_limit=3)
  → [{ taskId, title, role, status, claimedBy, recentEvents: [...] }, ...]
```

Each row carries the last 3 events. Render one line per task: emoji-by-role + truncated title + last event type & payload preview. Track per-task max-event-id locally; on the next poll, only narrate tasks whose latest event id moved.

For env occupancy / port info (useful before kicking off a test stage):

```
orange_envs() → [{ name, status, ports, leasedBy }, ...]
```

If `orange_status_live` returns no rows AND the ready queue is empty, you're done — tell the user.

## Step 8 — Post-merge (handled by orange, narrate only)

After `markPrOpen` the workflow stays **alive** inside a `Workflow.await` loop waiting for PR-lifecycle signals from the GitHub webhook. Three branches:

- **`prMerged` signal** → workflow calls `markDevReady` and exits. Then async-fires `PostMergeVerifier`: if `orange.post-merge.build-command` is configured, fetches+checks-out `origin/main` in the base repo and runs the command. On pass → stamps `metadata.main_verified_at` + appends `main_build_ok`. On fail → appends `build_broken` + flags `metadata.regression_suspect=true` AND auto-enqueues a triage task (role=`triage`, priority 10) whose description embeds the failure context. A `triage` agent picks it up via the normal claim flow and may reopen the suspect task via `orange_reopen_task`.
- **`prReviewed` signal with `state=changes_requested`** → workflow re-runs the dev stage with the review body as augmentation, then pushes the new commits to the same branch (no new PR — comments show up on the existing PR). Capped at 5 review retries.
- **`prReviewed` signal with `state=approved` (or other)** → workflow records the event and keeps awaiting merge.
- **`prClosedUnmerged` signal** → workflow marks the task failed and exits.

Periodically (every 5min) the `WorktreeDisposer` removes worktrees of terminal-status tasks (test_done / failed / cancelled). Stamps `metadata.worktree_removed_at` so reruns are no-ops.

You don't drive any of this; just notice it on your next narration pass — e.g. "task X merged, build_broken event landed — likely regression". For `regression_suspect` tasks, surface them prominently so the user can decide whether to reopen.

## Failure modes & recovery

| What happened | What you do |
|---|---|
| Heartbeat returns `alive=false` | Stop work on that task. Don't try to complete — the token is dead. Tell the user the task was reaped and decide whether to re-claim. |
| Heartbeat returns `cancel_requested=true` | Stop work. Call `orange_release_claim` so the task goes back to ready without burning an attempt. |
| `orange_complete_task` returns 410 | Same as above — the claim is gone. Don't try to redo the work; check `orange_task(id)` for the current status. |
| Plan submit returns 422 | Structural problem (cycle, duplicate task key). Read `reason`, fix the plan, resubmit. |
| Dispatch returns 422 | Read `reason` — usually an unknown pipeline name. Fix the plan and re-dispatch. |
| Dispatch returns 404 | The `plan_id` has no saved plan.json. Re-plan, or dispatch the plan inline. |
| Server unreachable mid-execution | Stop heartbeating, tell the user, offer to restart `./gradlew bootRun`. Active claims auto-expire on the reaper's next pass. |
| User asked to cancel | `orange_cancel(task_id)` for each in-flight one. If you're holding a claim, your next heartbeat will see `cancel_requested=true`; release. |
| Chat session restart | Call `orange_my_active_claims(worker_id)` — any claims still alive can be resumed without re-claiming. |

## What goes in the planner prompt (chat-as-planner)

You're acting as the planner. Use this template (`plan-template.md`) as the structure of your draft.
