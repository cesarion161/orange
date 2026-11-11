# orange v2: the Agent Factory core

2026-11-02 · Status: draft for approval · Source design: "Agent Factory — Stack & Architecture" (Claude Doc by the user, 2026-11-02)

> **Amended** by `2026-11-09-orange-project-space-design.md` (PR core removed, evidence-gated integration) and `2026-11-12-orange-remote-first-design.md` (review optional, everything on the VPS, the remaining PRs re-planned as R1–R5). Read those first where they disagree.

## 1. Intent

orange is meant to turn a spec into merged pull requests. Agents do the planning, coding, review and CI fixes; the human steers and merges.

v1 stopped on 2026-06-04 at two problems:
- Tasks running in parallel edited the same files and conflicted. In the snake run, PRs #26–#28 were closed and the last three tasks never ran.
- Nothing happens after a PR opens. The workflow ends at `pr_open`, review webhooks are only logged and CI is ignored.

v1 also carried weight it didn't use: Temporal (for a post-PR wait loop that was never built), a second execution path (chat-as-executor), and a Python sidecar between Java and Claude Code.

v2 evolves the existing orange codebase into the PRD's coordinator. It keeps what works:
- the Postgres task DAG, with its claims, leases, reaper and fan-in promotion trigger;
- the planner/dispatcher split;
- worktrees and PR opening;
- webhook HMAC checking.

It rebuilds the execution layer:

- **One execution path.** The coordinator runs every agent itself. Temporal and chat-as-executor are removed.
- **Claude Code is called directly** (`claude -p`) through a harness adapter. The Python sidecar is removed.
- **Claude Code runs bill the user's Max subscription by default** (user decision, 2026-11-03).
  - The run gets a long-lived `claude setup-token` token and talks to Anthropic directly. That is the only arrangement Anthropic's terms allow for subscription tokens.
  - Orange sets its own limits on top of the plan's caps: parallel runs, run time, an estimated per-run and daily cost, and a pause until the plan's 5-hour or weekly limit resets.
- **LiteLLM is in the stack from day 1** (user decision) for every API-key run: overflow (off by default, because the user chose no API spend), other providers, and OpenCode later.
  - Each gateway run gets a short-lived virtual key with its own budget, and its spend comes from the gateway.
  - The routing config chooses `auth: subscription | gateway` per tier.
- **The unit of work is the run, not a pipeline.** Events decide which kind of run comes next:
  - PR opened → review run
  - CI red → CI-fix run
  - review comments → rework run
  - merge → done
- **File-scope claims plus plan validation** keep parallel tasks from touching the same files.
- **A routing table picks model and effort for each run**, escalating after failures and stopping at `needs_human`.

### Success

These are the PRD's phase gates, measured on a real repo:
1. A task goes from `ready` to a PR with green CI, unattended.
2. An epic of 8 or more tasks completes in parallel with zero merge conflicts.
3. A week in which the user only answers push notifications and merges.

## 2. Goals, non-goals, anti-goals

**Goals for this change set**

1. One coordinator-owned run loop with leases, heartbeats from the runner, a reaper and per-kind slot pools.
2. A `HarnessAdapter` interface with a Claude Code implementation that streams normalized events.
3. Two ways for a run to authenticate, chosen per routing tier:
   - **`subscription`** (the default for Claude Code). `CLAUDE_CODE_OAUTH_TOKEN` comes from `claude setup-token`, and Claude Code talks to Anthropic directly. Cost is the estimate Claude Code reports itself (`total_cost_usd` and usage).
     - Orange enforces limits: slots, per-kind timeouts, a per-run estimated-cost watch that kills the run once it passes the tier budget, and a daily estimated-cost cap.
     - When the plan reports "usage limit reached … resets at …", orange pauses the scheduler until the reset and sends a push.
   - **`gateway`**. A LiteLLM gateway runs in `compose.yaml`.
     - Each run gets a virtual key limited to its models, with a budget and an expiry; the key is blocked when the run ends.
     - Spend and tokens from LiteLLM are stored on the run.
     - Overflow from subscription to gateway is off by default (`orange.billing.overflow-to-gateway=false`, the user's "no API spend").
4. A routing table (role × difficulty × risk → tier + effort) with escalation.
5. An event inbox plus a "reactor" that decides each task's next run. It covers review runs, CI-fix runs, rework from human and Copilot comments, conflicts after the base branch moves, ready-to-merge, and closed-unmerged.
6. Planner v2:
   - an epic per request;
   - tasks carry scopes, acceptance criteria, difficulty and a risk flag;
   - `submit_plan` is validated by the coordinator, with these rules: acyclic, a task cap, scopes required, no overlapping scopes between tasks that can run concurrently, and risk set by path rules;
   - the user approves, then dispatch.
7. Scope claims taken when a task starts and held until merge.
8. A notes blackboard (`post_note` / `search_notes`). Decision and interface notes are injected into prompts.
9. `needs_human`, `request_human`, and ntfy pushes for needs-human, ready-to-merge, budget and digest.
10. Budgets: per run (gateway key), a daily cap (pauses new runs), and cost per merged PR.
11. MCP served by the coordinator:
    - a worker endpoint with per-run tokens;
    - a steering endpoint for the Claude Code chat;
    - the plugin rewritten as the steering role.
12. Container isolation per run (non-root; only the task workspace mounted; no Docker socket; no tokens).
13. Later phases in this same plan: a thin dashboard, UI QA runs (Playwright MCP plus dev-env leasing), and an OpenCode adapter plus a nightly conformance suite.

**Non-goals.** Multiple users, multiple servers, auto-merge to the base branch, a learned router, research-only tasks (tasks that end in a note instead of a PR), VPS provisioning (Tailscale and Funnel setup are documentation, not code), and Kotlin.

**Anti-goals.** These count as failure even if everything works:
- The platform becomes the project again. Every phase ends with a test on a real repo.
- An agent can reach a provider API key, the LiteLLM master key or the GitHub token.
  - One exception was accepted by the user when choosing subscription billing: a subscription run's own environment holds the plan token.
  - Mitigations: runs live in containers, the network is allowlisted, only allowlisted authors' comments reach agents, and the token never touches the workspace or logs.
  - Rotating the token is documented: run `claude setup-token` again and revoke the old one in claude.ai.
- There are two ways to run a task.
- Completion or verdicts are parsed out of transcripts. They go through MCP tools.
- Code depends on a single harness's features.

## 3. Constraints

- Java 26, Spring Boot 4, Postgres 18, Flyway, jOOQ. Keep the existing patterns: `uuidv7()` primary keys, the `touch_updated_at` trigger, partial indexes on hot queues, and `pg_notify` on state changes.
- Flyway migrations are immutable, so all schema change goes into new `V14+` files.
- PRs target `orange-e2e-base`. They follow the user's standard flow: worktree, PR, review, up to 3 fix rounds, merge with `--merge`.
- On macOS, jOOQ codegen needs an embedded Postgres, and the shared-memory limit is tiny (`kern.sysv.shmall=1024`). Schema changes need `generateJooq` to run, so the build has to cope with that. Options: the existing setup script, or switching codegen to Testcontainers.
- **Subscription tokens.** Anthropic restricts them to Claude Code and claude.ai. They may never be used by orange's own code, by the Agent SDK, or by LiteLLM. Only the `claude` binary receives one.
  - Behind any gateway, Claude Code is billed at API prices.
  - Provider API keys live only in the LiteLLM container's environment.
- **Live checks use the subscription only.** The user chose no API spend. The gateway path is verified against a real LiteLLM container for key mint, block, info and delete, which makes no provider calls, and with fakes. It does not make live model calls.
- **Java: the latest GA release.** That's JDK 27 (released 2026-09-15, not LTS). Fall back to 26 if Lombok, Byte Buddy or Gradle can't handle 27 yet.
- Claude Code 2.1.316 flags this design relies on (all checked locally): `-p`, `--output-format stream-json --verbose`, `--model`, `--effort low|medium|high|xhigh|max`, `--fallback-model`, `--max-budget-usd`, `--append-system-prompt`, `--mcp-config`, `--strict-mcp-config`, `--setting-sources`, `--allowedTools`, `--permission-mode` (acceptEdits, dontAsk, bypassPermissions, …), `--session-id`, `--no-session-persistence`.

## 4. Decisions

**Made by the user in conversation**
- Evolve orange rather than start a new repo.
- Drop Temporal.
- Use LiteLLM from day 1, not later.
- Bill Claude Code runs to the Max subscription directly, with orange-side limits. No API spend, so overflow to the gateway is off. (2026-11-03)
- Live tests run on a new private sandbox repo created by the builder, with GitHub Actions CI.
- Java, on the latest version.
- Adopt these, as proposed:
  - the splitting rules (one PR per task, skeleton first, interfaces first, a cap, coordinator-side validation, risk from path rules);
  - runs as the unit, driven by events;
  - the starting routing table and escalation;
  - headless Playwright for later UI QA.

**Builder's calls (flagged so the user can check them)**
1. **Java, not Kotlin.** All existing code is Java, and mixing languages buys nothing.
2. **Claude Code adapter first.** OpenCode comes in the last phase, behind the same interface.
3. **Removed:**
   - chat-as-executor (Mode A);
   - the Python `agent-runner/` and the `protocol/` schemas;
   - the Python `orange-mcp/` shim (MCP moves into the coordinator);
   - `PostMergeVerifier` and the periodic regression (CI replaces them);
   - the triage agent (conflicts become fix runs);
   - pipelines (`dev_only` / `dev_qa` / `dev_review_qa`) and the `pipeline` column.
4. **Role prompts move from rows in the `agents` table to versioned files** under `orange/src/main/resources/roles/`. The `agents` and `agent_runs` tables are replaced by `runs`.
5. **LiteLLM model names are the real Anthropic model IDs** (`claude-opus-5-5` and so on), so Claude Code enables model-specific features such as effort. Tiers live in orange's routing config. LiteLLM config carries explicit per-token prices, so spend doesn't depend on LiteLLM's built-in price map.
6. **The REST API and the steering MCP get bearer-token auth** (`ORANGE_API_TOKEN`). If the token is unset, a warning is logged and requests from localhost are allowed. Worker MCP calls use a per-run token.
7. **Process isolation comes first** (PRs 1–5), container isolation in PR 6. Process mode is for local development and is documented as unconfined.
8. **Webhooks are made optional for correctness.** A reconciler polls GitHub for open PRs every 2 minutes, so lost or unforwarded webhooks only add latency.
9. **Rebasing an open PR happens only when GitHub reports a conflict** (`mergeable_state = dirty`). The coordinator first tries a plain `git rebase`; only if that conflicts does it queue a fix run. PRs that are merely behind the base branch are left alone unless a config flag says otherwise.
10. **Agent reviews are posted to GitHub as `COMMENT` reviews.** The PR author is the coordinator's token, and GitHub forbids `APPROVE` or `REQUEST_CHANGES` on your own PR. The verdict that counts is stored in orange.
11. **orange keeps acting as the user's own GitHub identity for now** (`ORANGE_GITHUB_TOKEN`, host git credentials). Two consequences:
    - Every comment and review orange posts carries a hidden marker, `<!-- orange:run=<id> -->`. Orange's own posts are recognised by that marker, not by their author.
    - Only comments from authors in `orange.github.feedback-authors` become agent input. The default is the token's own login plus `copilot-pull-request-reviewer[bot]`. Everyone else is logged and ignored, which closes the prompt-injection path on public repos.

    Because orange's PRs are authored as the user, branch protection can't require the user's approval; "you merge" is the gate instead. A GitHub App or a machine account is a later option behind the same `GithubClient` interface.
12. **Bot feedback is bounded.** Rework triggered by comments from `[bot]` authors counts toward `orange.review.max-bot-rounds` (default 2) per task. After that, further bot comments on that task are recorded but don't start runs. Human feedback is uncapped. Ready-to-merge waits up to `orange.review.expected-grace` (10 minutes) after green CI for reviews from `orange.review.expected-reviewers`, which defaults to empty. Copilot is added only if the repo has Copilot auto-review set up, since Copilot can't be requested through the API.
13. **One target repo per deployment.** The schema still carries a `repo` column on `epics`, `tasks` and `events`, because it's cheap now and a retrofit later. Configuration stays single-repo (`orange.github.repo`, base branch, base checkout). Multi-repo configuration is a non-goal for this change set.
14. **Answering from the phone goes through GitHub, with the steering session as a second path.**
    - When a task needs a human, orange posts the question as a PR comment, or as an issue when the task has no PR yet.
    - A reply from an allowed author is the answer, and resets `failures`. `/orange cancel` cancels the task.
    - Each epic's plan is posted as an issue. `/orange approve` or `/orange revise <feedback>` there approves or revises it.
    - ntfy click URLs point at those threads.
    - The steering MCP also works from the phone through a Claude Code Remote Control session.
    - `ORANGE_API_TOKEN` is always required for steering and for mutating REST calls; there's no localhost fallback. Only the webhook path is ever exposed publicly (Funnel).
15. **Process mode is dev-only and does not meet the credential anti-goal.** Agents in process mode run as the user and could read `~/.ssh`, the `gh` login or `orange/.env`. So:
    - Live runs against real repos happen only in container mode, and container isolation moves up to PR 3.
    - The only live check before PR 3 is a tool-less gateway smoke test (`claude -p` through LiteLLM with `--tools ""`), which can't touch the filesystem.
    - The default worktree root moves outside the repo tree, to `~/.orange/worktrees`.

## 5. Architecture

```
steering chat (Claude Code + plugin) ──MCP /mcp/steer──┐
GitHub webhooks ─HMAC─▶ /webhooks/github ─▶ events ────┤
reconciler (polls GitHub) ─────────────────▶ events ───┤
                                                      ▼
                        ┌──────────── coordinator (Spring Boot) ─────────────┐
                        │ event processor → task reactor → run queue         │
                        │ run scheduler (slots per kind) → worker runner     │
                        │ routing, gateway client, notifier, budget guard    │
                        └───┬───────────────────────┬────────────────────────┘
                            │ spawn per run           │ push branch / PR / review (GitHub token)
                            ▼                         ▼
             harness adapter → claude -p (worktree)   GitHub
                     │  MCP /mcp/worker (run token)
                     └─ model calls → LiteLLM :4000 (virtual key) → Anthropic (provider key)
```

| Component | v1 | v2 |
|---|---|---|
| Execution | Temporal `PipelineWorkflow` plus chat claims | `RunScheduler` plus `WorkerRunner` (virtual threads), Postgres leases |
| Agent process | Python agent-runner → Agent SDK → Claude Code | `ClaudeCodeAdapter` → `claude -p` |
| Model access | Direct Anthropic key | LiteLLM gateway, per-run virtual key |
| Completion signal | Exit code plus parsed `report.md` / `VERDICT:` line | MCP `report_result` / `submit_review` / `submit_plan` |
| After the PR opens | Merge webhook only | Events inbox, reactor, reconciler |
| Parallel safety | None | Plan validation plus scope claims held to merge |
| Testing | LLM tester inside the pipeline | CI on the PR, plus an LLM review run (UI QA later) |
| MCP | Python stdio shim calling REST | Java MCP endpoints in the coordinator (MCP Java SDK, stateless or streamable HTTP) |
| Prompts | `agents` table rows | `resources/roles/*.md` |

Code that stays (adapted): the `task` package (DAG, `DagValidator`, repositories, `TaskService.createGraph`), `worktree`, `github` (client and PR service), `webhook` (HMAC filter), `listener` (`pg_notify` listener), `dispatch` / `planner` (reworked), `devenv` (dormant until UI QA), metrics.

## 6. Data model (end state)

**`tasks`** (altered)
- New columns: `epic_id`, `acceptance JSONB` (a list of strings), `difficulty` (S, M or L), `risk BOOLEAN`, `scope TEXT[]` (repo-relative globs), `failures INT`, `branch`, `pr_number`, `pr_url`, `head_sha`, `ci_state` (`none`, `pending`, `green`, `red`), `reviewed_sha`, `review_verdict`, `ready_notified_sha`.
- `description` stays as the spec. `role` takes `implementer` or `chore`.
- Dropped: `pipeline`, `claimed_by`, `claimed_at`, `heartbeat_at`.

**Status FSM**

```
pending ──deps merged──▶ ready ──scope claimed + slot──▶ running ──PR pushed──▶ in_review ──merged──▶ merged
                                                          ▲    │                    │
                                     feedback (CI red,    │    │ request_human /    │ closed unmerged /
                                     comments, conflict)  └────┼── 3rd failure ─────┤ 3rd failure
                                                               ▼                    ▼
                                                           needs_human ──answer──▶ ready (scope and branch kept)
any non-terminal ──▶ cancelled · failed (terminal: the user gives up, or an unrecoverable error)
```

- `running` means a code-changing run (implement or CI fix) is queued or active.
- `in_review` means the PR is open and no code-changing run is pending. A review run may be active.
- Merged tasks promote their dependents through the existing V11 trigger, rewritten to treat `merged` as the satisfied state.
- Data migration of old rows: `in_progress` → `ready`, `pr_open` → `in_review`, `dev_ready`, `in_test` and `test_done` → `merged`.

**New tables**

| Table | Key columns | Notes |
|---|---|---|
| `epics` | id, title, request, status (`planning`, `planned`, `approved`, `done`, `cancelled`), plan JSONB, validation JSONB, architecture_md | One per planning request. Replaces `.plans/` files. |
| `runs` | id, task_id?, epic_id?, kind (`plan`, `implement`, `review`, `ci_fix`), status (`queued`, `running`, `succeeded`, `failed`, `cancelled`), role, tier, model, effort, harness, attempt, input JSONB (feedback consumed), outcome JSONB, worker_id, lease_expires_at, mcp_token_hash, gateway_key_alias, budget_usd, cost_usd, tokens_in, tokens_out, cache_read_tokens, spend_synced_at, session_id, log_path, error, queued_at, started_at, finished_at | Replaces `agent_runs`. Partial unique index: at most one queued-or-running run per task. |
| `run_events` | id, run_id, seq, type, payload | Normalized harness events, used for tailing. The raw stream is kept in a log file. |
| `task_feedback` | id, task_id, source (`ci`, `review`, `human`, `conflict`, `answer`), body JSONB, head_sha, created_at, consumed_by_run | What the next rework run must address. |
| `scope_claims` | task_id, glob, prefix | Live claims. Released on merge, cancel or failure. |
| `notes` | id, epic_id, task_id?, run_id?, kind (`finding`, `decision`, `interface`, `gotcha`), body | Append-only blackboard. |
| `events` | id, source, type, delivery_id UNIQUE, payload, received_at, processed_at, attempts, last_error | Inbox for webhooks, the reconciler, cron and manual commands. |
| `settings` | key, value JSONB | Global pause, budget state. |

`task_events` stays as the audit log. `dev_envs` is kept. `task_claims`, `agents` and `agent_runs` are dropped (local dev data only).

## 7. Runs

- **Kinds and their routing role:**
  - `plan` → planner
  - `implement` → the task's role (implementer or chore)
  - `review` → reviewer
  - `ci_fix` → ci_fixer

  A rework after review comments is an `implement` run with feedback. Merge conflicts go to a `ci_fix` run.
- **Queueing.** The task reactor is the only place that queues runs for a task. It holds the task row locked (`FOR UPDATE`) while it decides. It runs on a notify after any change to a task, run or event, and also on a 30-second sweep. For each non-terminal task with no queued or running run, it applies the first rule that matches:
  1. `ready`, the scope claim succeeds and the budget isn't paused → queue `implement`; the task moves to `running`.
  2. `failures` has reached `needs-human-after` (3) → `needs_human` plus a push.
  3. There is unconsumed feedback → queue a `ci_fix` run (CI or conflict feedback) or an `implement` run (review, human or answer feedback); the task moves to `running`. Human comments are debounced: wait 2 minutes after the last one.
  4. `in_review` with `head_sha` ≠ `reviewed_sha` → queue `review`.
  5. `in_review`, CI green (or CI not required), review approved on `head_sha`, and not yet notified for that SHA → push `ready-to-merge`.
- **Scheduler.** Slot pools per kind: `plan` 1, `implement` 4, `review` 2, `ci_fix` 2. All are configurable, along with a global cap. A slot claims the next queued run of its kind with `FOR UPDATE SKIP LOCKED`, ordered by priority then queue time. It wakes on `pg_notify('run_queued')`.
- **Worker runner.** It handles one run from start to finish:
  1. Prepare the workspace.
  2. Mint the virtual key.
  3. Write the MCP config holding the run token.
  4. Start the adapter.
  5. Heartbeat the lease every 60 seconds while the process is alive (TTL 5 minutes).
  6. Persist normalized events.
  7. Enforce the per-kind timeout (implement 60 min, ci_fix 40, review 20, plan 30) by killing the process.
  8. On exit, read the outcome recorded through MCP, then run the post-run step for that kind. Exit without an outcome counts as a failure.
  9. Block the key and schedule a spend sync.
- **Reaper.** Expired leases mark the run `failed` (`lease_expired`). At startup, runs owned by a previous instance are failed and their processes killed.
- **Post-run steps:**
  - `implement` / `ci_fix` with status `done`:
    1. Check that the branch is ahead of base.
    2. Compute changed files against the task's scope. Out-of-scope files are auto-claimed when free; when another active task holds them, the task goes to `needs_human`.
    3. Push with `--force-with-lease`.
    4. Open the PR, or update it if one exists. The body carries the summary, spec, acceptance checklist, scope, any out-of-scope changes, cost so far and the correlation marker.
    5. Set `head_sha`, set `ci_state=pending`, and move the task to `in_review`.
  - Status `blocked` (from `request_human`) → `needs_human`.
  - Status `failed`, or no outcome → `failures++`.
  - `review`: store the verdict for `head_sha` and post a `COMMENT` review with any inline comments. `request_changes` adds review feedback and `failures++`.
  - `plan`: the last valid `submit_plan` is stored on the epic, which moves to `planned`.
- **Failure counting.** These increment `failures`: a failed implement or CI-fix run, CI red on a head the agent pushed, and a reviewer `request_changes`. Human comments do not. A human answer resets `failures` to 0.

## 8. Harness adapter

```java
interface HarnessAdapter {
  String id();                                   // "claude-code"
  boolean supports(String model);
  RunHandle start(RunSpec spec);                 // prompt, role prompt, model, effort, cwd, env, mcpConfig, tools, permission mode, timeout
}
interface RunHandle { void onEvent(Consumer<RunEvent> l); CompletableFuture<RunExit> exit(); void kill(); }
// RunEvent: Init(sessionId,model) | Assistant(text) | ToolUse(name,input) | ToolResult(id,isError,excerpt) | Result(usage, costUsd, numTurns, isError) | Stderr(line)
```

**Claude Code adapter**
- **Command:**

  ```
  claude -p <prompt> --output-format stream-json --verbose
    --model <id> --effort <level> --append-system-prompt <role prompt>
    --mcp-config <file> --strict-mcp-config --setting-sources project,local
    --allowedTools <list> --permission-mode <acceptEdits|dontAsk|bypassPermissions>
    --session-id <run uuid>
  ```

  The binary path is configurable, so tests can use a fake.
- **Environment.** Built from an allowlist; the coordinator's own environment is never inherited:
  - `PATH`, `HOME`, `LANG`;
  - credentials, which depend on `auth`:
    - **subscription:** only `CLAUDE_CODE_OAUTH_TOKEN`. No base URL, no API key.
    - **gateway:** `ANTHROPIC_BASE_URL` set to LiteLLM, and `ANTHROPIC_AUTH_TOKEN` set to the virtual key.
  - `ANTHROPIC_DEFAULT_HAIKU_MODEL` = the small model;
  - `CLAUDE_CONFIG_DIR` = a per-run directory outside the worktree, so the user's plugins, hooks and logins never load;
  - `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`, `DISABLE_AUTOUPDATER=1`, `ENABLE_TOOL_SEARCH=true`;
  - git author and committer set to `orange[bot]`;
  - no `ANTHROPIC_API_KEY`, no GitHub token, no LiteLLM master key.
- **Tools per kind:**
  - implement / ci_fix: Read, Write, Edit, Bash, Glob, Grep, TodoWrite, plus `mcp__orange__*`;
  - review and plan: Read, Glob, Grep, plus `mcp__orange__*`. Review may also get Bash restricted to `git diff` and `git log`.
- **Parsing.** stream-json lines become `RunEvent`s. The `result` event supplies tokens and the turn count; cost comes from the gateway.
- **An OpenCode adapter comes later** (`opencode run --format json`, OpenAI-compatible base URL to LiteLLM). It isn't built in this set.

## 9. Gateway (LiteLLM)

This section applies to runs whose tier has `auth: gateway`. Subscription runs never touch LiteLLM.

- **Compose:**
  - service `litellm`, a pinned `-stable` image tag (pinned after the March 2026 PyPI incident; the pinned Docker image was unaffected), port 4000;
  - database `litellm`, added by the existing Postgres init script;
  - `LITELLM_MASTER_KEY` and the provider keys come from `orange/.env`;
  - config is `orange/litellm/config.yaml`.
- **Config.**
  - `model_list` uses the real IDs: `claude-fable-5-1`, `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`.
  - Each entry carries explicit `input_cost_per_token`, `output_cost_per_token` and cache read/write costs.
  - Fallbacks are listed per model. Second-provider entries are optional: commented examples only, until a key exists.
- **`GatewayClient`**, built on Spring `RestClient` and using the master key:
  - `mint(runId, models, budgetUsd, ttl)` → `POST /key/generate` with `key_alias=run-<id>`, `models`, `max_budget`, `duration` (the run timeout plus a margin), and metadata `{run_id, task_id, kind}`. It returns the key, which is held only in memory and the child's environment.
  - `block(alias)` → `POST /key/block`.
  - `spend(alias)` → `GET /key/info`.
  - `delete(alias)`.
- **Spend sync.** A job every 60 seconds updates `runs.cost_usd` for finished runs until spend has been stable for 10 minutes, then deletes the key. Cost per task and per merged PR are sums over runs.
- **If the gateway is down,** the scheduler doesn't start runs (a health check runs before minting) and the dashboard and status show `gateway: down`. There is no automatic direct-key bypass; an operator flag could add one later.

## 10. Routing and escalation

Config lives in `application.yaml` under `orange.routing`:

```yaml
tiers:   # auth: subscription (Claude Code → Anthropic, plan token) | gateway (LiteLLM virtual key)
  large:  { model: claude-opus-5-5,   harness: claude-code, auth: subscription, budget-usd: 8 }
  medium: { model: claude-sonnet-5-5, harness: claude-code, auth: subscription, budget-usd: 4 }
  small:  { model: claude-haiku-5-5,  harness: claude-code, auth: subscription, budget-usd: 1 }
# budget-usd is the per-run cap: estimated cost for subscription runs, the virtual key's max_budget for gateway runs
rules:   # first match wins
  - { role: planner,  tier: large,  effort: xhigh }
  - { role: reviewer, tier: large,  effort: high }
  - { role: ci_fixer, tier: medium, effort: medium }
  - { role: chore,    tier: small,  effort: low }
  - { role: implementer, difficulty: L, tier: large,  effort: high }
  - { role: implementer, difficulty: M, tier: medium, effort: high }
  - { role: implementer, difficulty: S, tier: medium, effort: medium }
risk:       { min-tier: large, min-effort: high,
              paths: ["**/auth/**", "**/migrations/**", "**/db/migration/**", "**/payments/**", "infra/**", ".github/workflows/**"] }
escalation: { tier-up-after-failures: 2, needs-human-after-failures: 3 }
small-model: claude-haiku-5-5
```

- **Escalation.** At run start, if `failures ≥ 2`, go up one tier. If the run is already on the large tier, raise effort one step instead (high → xhigh → max).
- **Risk.** A task is risky if the planner flagged it or any of its scope globs overlaps a risk path. Risky tasks always use at least the large tier and high effort.
- A `RouteDecision` (tier, model, effort, harness, budget) is stored on every run.

## 11. Planning and dispatch

- **Plan request.** `orange_plan(request)` creates an epic in `planning` and queues a `plan` run. The run works in a read-only, detached worktree at `origin/<base>`.
- **The planner's tools:** Read, Glob, Grep, `post_note` (research findings), and `submit_plan(plan)`. `submit_plan` returns validation errors, so the planner fixes them in the same run. The last valid submission wins.
- **Plan schema v2:**

  ```json
  { "epic": {"title": "...", "summary": "..."},
    "tasks": [{ "key": "scaffold", "title": "...", "description": "...", "acceptance": ["..."],
                "role": "implementer|chore", "difficulty": "S|M|L", "risk": false,
                "scope": ["snake/Cargo.toml", "snake/src/main.rs"], "priority": 100 }],
    "edges": [{"from": "scaffold", "to": "types"}],
    "notes": [{"kind": "interface|decision", "body": "..."}] }
  ```

- **Validation** (`PlanValidator`, shared by `submit_plan` and `POST /dispatch`):
  - required fields and enums;
  - unique keys;
  - 1 to `max-tasks` tasks (default 12);
  - acyclic (the existing `DagValidator`);
  - every task has at least one scope entry;
  - no scope is the whole repo, unless it's the only task;
  - **concurrency rule:** for every pair of tasks where neither can reach the other in the DAG, the scopes must be disjoint.
    - Overlap test: compare the literal prefixes of the globs, segment by segment. It is conservative, so a false positive only serializes work.
    - `/dispatch?autoSerialize=true` adds edges instead of rejecting.
  - risk is forced on by the path rules.
- **Approval.** `orange_approve(epic_id)` dispatches: it creates the tasks (with `epic_id`), the edges and the notes, and promotes root tasks to `ready`. The epic moves to `approved`, and to `done` when every task is merged or cancelled. `orange_revise_plan(epic_id, feedback)` queues a new plan run carrying the previous plan and the feedback. `POST /dispatch` with a plan body still works from any source, so the planner/dispatcher split is preserved.
- **The planner prompt carries the splitting rules:**
  - one PR per task, small enough to review in one sitting;
  - acceptance criteria that map to tests;
  - skeleton first: one early task creates module stubs, dependencies, lockfiles and registries, so later tasks own separate files;
  - interface notes before implementations;
  - a cap on the number of tasks, preferring abstraction for broad goals;
  - CI must exist, or the first task adds it.

## 12. Scope claims

- **Representation.** A claim is a glob plus its literal prefix: everything before the first wildcard, cut at a path-segment boundary. Two claims overlap when one prefix is a path-prefix of the other.
- **Acquire.** The reactor takes the claim before queueing a task's first run, under `pg_advisory_xact_lock(<scope lock>)`, against the claims of every non-terminal task. If any glob overlaps, the task stays `ready`, and status shows it as "waiting on scope of task X".
- **Hold.** The claim lasts through `running`, `in_review` and `needs_human`. It is released on `merged`, `cancelled` or `failed`.
- **Out-of-scope edits.** After a run, files outside the scope are claimed if free. If another task holds them, the task goes to `needs_human` with an explanation, and the PR stays as a draft.

## 13. Events, reactions, reconciler

- **Webhook intake.** `POST /webhooks/github` (HMAC-checked) stores the event and returns 200. Duplicates are dropped by `X-GitHub-Delivery`.
- **Processor.** Unprocessed events are handled in order with `SKIP LOCKED`. Handlers are idempotent. Failures are retried up to 5 times and recorded in `last_error`.

| Event | Effect |
|---|---|
| `pull_request` opened / synchronize | Update `head_sha`, set `ci_state=pending`. |
| `pull_request` closed, merged | `merged`; release scope; promote dependents (trigger); clean up the workspace later; final spend sync. |
| `pull_request` closed, unmerged | `needs_human`: "PR closed without merge — retry or cancel?" |
| `check_suite` / `check_run` / `workflow_run` completed | Recompute `ci_state` for `head_sha` from the commit's check runs. If red, add `ci` feedback with the failing checks and the log tail of the failed Actions jobs, then `failures++`. |
| `pull_request_review` submitted, `pull_request_review_comment` created, or `issue_comment` created on the PR; author in `feedback-authors` and no orange marker | Add `human` feedback (or `bot` feedback for `[bot]` authors, capped by `max-bot-rounds`). Copilot's `COMMENTED` reviews count. On a `needs_human` task, the reply is the answer. |
| `issue_comment` on an orange question issue or epic issue, author in `feedback-authors` | Answer, `/orange cancel`, `/orange approve`, `/orange revise …` |
| `push` to base | For each `in_review` task, check mergeability. `dirty` → try a local rebase and push, or add `conflict` feedback. |

- **Reconciler** (every 2 minutes). For tasks in `running` or `in_review` that have a PR, fetch the PR state, check runs, reviews and comments since the last check. Changes become synthetic `events` (`source=reconciler`). Mergeability is checked after base-branch movement. This makes the system correct without webhook forwarding.

## 14. needs_human, notifications, budgets

- **`request_human(question)`** is available to implement, CI-fix and plan runs. The run ends `blocked`, and the task (or the epic, for plan runs) moves to `needs_human`.
- **`orange_answer(task_id, answer, action=retry|cancel)`** adds `answer` feedback and resets `failures`, then returns the task to `ready` (keeping its scope and branch) or cancels it.
- **Notifier.** `NtfyNotifier` POSTs to `${ORANGE_NTFY_URL}/${prefix}-<topic>`, with optional token auth, a title, a message and a click URL pointing at the PR or task. Topics: `needs-human`, `ready-to-merge`, `budget`, `digest` (daily at 08:00 local). If unconfigured, it only logs. Compose gets an optional `ntfy` service.
- **Budgets.** Costs are estimates for subscription runs and real spend for gateway runs.
  - **Per run:** the tier's budget.
    - Subscription runs: the runner adds up streamed usage at configured prices and kills the run once the total passes the budget.
    - Gateway runs: the budget is the virtual key's `max_budget`, enforced by LiteLLM.
  - **Plan limits:** a subscription run that fails with "usage limit reached … resets at …" is requeued without counting as a failure. `settings.paused` is set with reason `plan_limit` until the reset time, and a `budget` push goes out.
  - daily cap (`orange.budget.daily-usd`, default 50): a push at 80%; at 100%, `settings.paused` is set with reason `budget` and a push is sent; it auto-resumes at the next local midnight;
  - manual pause and resume through steering tools.

## 15. MCP endpoints

The coordinator serves MCP over HTTP using the MCP Java SDK (or Spring AI's MCP server, whichever supports two endpoints with different tool sets). There are two endpoints.

**`/mcp/worker`** requires `Authorization: Bearer <run token>`. The coordinator resolves the token to a run and rejects it unless that run is `running`. Tools are checked against the run's kind:
- `get_task` (spec, acceptance, scope, notes, feedback, branch/base)
- `post_note(kind, body)`
- `search_notes(query)`
- `report_result(status: done|blocked|failed, summary)` (implement, ci_fix)
- `submit_review(verdict: approve|request_changes, summary, comments[{path, line?, body}])` (review)
- `submit_plan(plan)` (plan)
- `request_human(question)` (not review)

**`/mcp/steer`** requires `ORANGE_API_TOKEN`. Tools:
- `orange_plan`, `orange_epic`, `orange_revise_plan`, `orange_approve`, `orange_dispatch`
- `orange_status` (counts, active runs, needs-human list, ready-to-merge list, spend today against the cap, paused, gateway health)
- `orange_task`, `orange_tail`
- `orange_answer`, `orange_cancel`, `orange_reprioritize`, `orange_pause`, `orange_resume`

**The plugin** (`plugin/`) becomes the steering role:
- `.mcp.json` uses HTTP transport with a header from `${ORANGE_API_TOKEN}`;
- the skill is rewritten around plan → review → approve → watch → answer → merge;
- the `.claude/commands/orange-*.md` slash commands are updated.

## 16. Isolation

- **Process mode (PRs 1–5):**
  - the agent's working directory is its worktree;
  - per-run `CLAUDE_CONFIG_DIR`;
  - project and local settings only, plus strict MCP;
  - an environment allowlist (no tokens).

  Bash is not confined, which is documented. File-tool confinement relies on Claude Code's working-directory permissions.
- **Container mode (PR 6),** selected by `orange.runner.isolation=container`:
  - one `docker run` per run from a worker image (`orange/worker/Dockerfile`: Claude Code, git, ripgrep, plus a toolchain layer per repo);
  - non-root, with `--permission-mode bypassPermissions`;
  - only the workspace is mounted. Because a worktree's `.git` file points at the base repo, container mode switches to per-task clones (a local clone, then fetch from GitHub), or mounts the base `.git` at its absolute path. The builder decides in PR 6, documenting the trade-off.
  - a Docker network that reaches only LiteLLM and the coordinator's worker MCP;
  - no Docker socket;
  - the coordinator kills containers by label on reap or restart.

## 17. Security

- Provider keys live only in the LiteLLM container's environment.
- The LiteLLM master key and the GitHub token live only in the coordinator.
- Agents get a virtual key with a model allowlist and budget, blocked at run end. The per-run MCP token is stored hashed.
- The REST API and steering MCP require `ORANGE_API_TOKEN` (localhost allowed with a warning if it's unset). The webhook stays HMAC-only.
- The coordinator does every push, PR, review post and comment. Agents commit locally and never push.

## 18. Testing

- **Unit:**
  - routing (rules, escalation, risk);
  - scope prefixes and overlap;
  - `PlanValidator` (each rule);
  - the stream-json parser;
  - prompt builder;
  - reactor decision table (pure function over task, run and feedback state).
- **Integration (Testcontainers Postgres):**
  - run claim, lease and reaper, including concurrent slots;
  - the one-active-run invariant;
  - scope-claim concurrency;
  - event processing idempotency;
  - FSM and trigger promotion;
  - migration from v1 data.
- **Gateway:** `MockRestServiceServer` tests for mint, block, spend and delete.
- **Harness:** a fake `claude` executable from test resources. It is a small script that runs a Java single-file program. It emits real stream-json, edits and commits a file, and calls `/mcp/worker` with the run token from `--mcp-config`. End-to-end ITs drive ready → run → PR (fake GitHub client) → review → merge event → dependents ready, with no LLM.
- **Live checks** (need the user's provider key and a spend limit):
  - a smoke task through LiteLLM after PR 1 and after PR 2;
  - the Phase-2 exit test (an epic of 8 or more tasks, zero conflicts) on a sandbox repo with GitHub Actions CI, after PR 5 or 6.

## 19. Phasing (one PR each, in order, each leaving the base branch working)

Order after the builder check: containers move up to PR 3, so live runs happen in containers. The list below still shows the original numbering; the execution order is 1, 2, 6 (containers), 3, 4, 5, 7, 8, 9.

1. **Gateway + Claude Code adapter + routing, under the existing pipeline.**
   - LiteLLM goes into compose with its config.
   - New packages: `GatewayClient`, routing, harness adapter.
   - `PipelineActivitiesImpl.runStage` and `PlannerService` switch from the Python runner to the adapter, using per-run keys.
   - Spend goes into `agent_runs`.
   - `agent-runner/` and `protocol/` are deleted.
   - The jOOQ codegen fix on macOS lands here too.
   - Exit: an IT with the fake harness passes, plus a tool-less live gateway smoke test.
2. **Runs replace Temporal.**
   - FSM v2 migration, plus the `runs`, `run_events`, `events` and `settings` tables.
   - Scheduler, slots, runner, leases and reaper.
   - The worker MCP with `report_result`, `request_human` and `submit_review`.
   - Coordinator push and PR; a review run after the PR opens.
   - Merge handled through the events inbox.
   - Deleted: Temporal (dependency, compose services, `workflow` package), chat claims (`ClaimService`, `/claims`), `PostMergeVerifier`, regression, triage, pipelines.
   - Exit: an e2e IT for ready → PR → review → merged → dependents ready.
3. **Reactions.**
   - `task_feedback` and the reactor rules.
   - CI state, plus a CI-fix run with the logs.
   - Human and Copilot comments trigger rework.
   - Conflict detection, local rebase and the conflict fix run.
   - The reconciler.
   - Ready-to-merge, and closed-unmerged.
   - Exit: an IT per reaction.
4. **Planner v2 + scopes + notes.**
   - The `epics` table and the plan run with `submit_plan`.
   - `PlanValidator`, approve and revise.
   - Scope claims (acquire, hold, auto-extend).
   - The notes blackboard, injected into prompts.
   - The new planner prompt.
   - Exit: an IT where overlapping siblings get serialized or rejected and disjoint siblings run in parallel.
5. **Escalation, needs_human, budgets, steering.**
   - Failure counting and escalation.
   - `orange_answer`.
   - ntfy.
   - The daily cap, pause and resume.
   - The `/mcp/steer` tools and API token.
   - The plugin rewrite; `orange-mcp/` deleted.
   - Docs (README, CLAUDE.md).
   - Exit: ITs pass, plus a manual steering session from Claude Code.
6. **Container isolation.**
   - The worker image and a Docker launcher.
   - The workspace strategy.
   - A network allowlist.
   - Orphan cleanup.
   - Exit: the e2e IT passes in containers; the agent can't see the coordinator's environment or host files.
7. **Thin dashboard.**
   - A JTE page: tasks by status, active runs, needs-human, ready-to-merge, spend today and per merged PR, gateway health, pause.
   - Exit: the page renders against IT data.
8. **UI QA runs.**
   - Run kind `qa` for tasks with `ui: true`.
   - Playwright MCP (`--headless --isolated`) in the worker image.
   - The runner leases a dev env and starts the PR's app next to the run.
   - Screenshots and the trace go on the PR; bugs found become failing tests (rework feedback).
   - Exit: a QA run on a sample web app finds a planted bug.
9. **OpenCode adapter + conformance suite.**
   - `opencode run --format json`.
   - The OpenAI-compatible LiteLLM base URL.
   - A nightly fixture task on each harness and tier; failing adapters are removed from routing, with a push.
   - Exit: the fixture passes on both harnesses.

**Live gates:**
- After PR 1: the tool-less gateway smoke test.
- After reactions (PR 3 in the original numbering): one real task, in containers, from `ready` to a PR with green CI on the sandbox repo.
- After steering (PR 5): the PRD Phase-2 exit test, an epic of 8 or more tasks with zero conflicts, in containers.

## 20. Left to the builder

- Exact package layout.
- MCP library choice (MCP Java SDK servlet transport vs Spring AI starter).
- Fake-harness mechanics.
- LiteLLM image tag (pin the newest `-stable`).
- The jOOQ codegen fix on macOS.
- Prompt wording.
- Timeouts and slot defaults.
- Dashboard look.
- The container workspace strategy.
- Whether review runs also get read-only `git` through Bash.
