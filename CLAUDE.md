# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

- **`orange/`** — Java 27 / Spring Boot 4 coordinator. Owns persistence (Postgres + Flyway + jOOQ), task state and the run loop: it starts Claude Code (`claude -p`) directly, one process per agent run, verifies the work itself and integrates it. No Temporal, no external workers.
- **`orange-mcp/`** — Python stdio MCP shim the Claude Code plugin uses to call orange's REST API.
- **`plugin/`** — the Claude Code plugin (skills, slash commands).
- **`docs/superpowers/`** — the v2 design spec, the project-space spec that amends it, and per-PR implementation plans.

Inside `orange/src/main/java/com/ai/orange/`:
- **`run/`** — the run loop. `RunRepository` (runs with leases), `TaskReactor` + `ReactorDecision` (each task's next run), `RunScheduler` (per-kind slots), `RunReaper` (expired leases, orphans of a previous instance), and the three executors: `WorkerRunner` (agent runs), `EvidenceRunner` (verify) and `Integrator` (integrate). `RunPreparer` builds prompts, `PostRunSteps` applies a run's effects, `FailureReasons` classifies failures, `SchedulerPause` is the stop switch, `SpendSync` and `RunRetention` keep house.
- **`harness/`** — harness-neutral run model (`RunSpec`, `RunEvent`, `HarnessAdapter`, `ProcessRunHandle`) and **`harness/claude/`**, the Claude Code adapter (`ClaudeCodeCommand` builds argv + env, `StreamJsonParser` reads `--output-format stream-json`).
- **`roles/`** — `RolePrompts` (role instructions from `src/main/resources/roles/*.md`) and `ToolPolicy` (tools per run kind).
- **`mcp/`** — the worker MCP endpoint `/mcp/worker` (`WorkerTools`: `report_result`, `request_human`, `submit_review`) with per-run bearer tokens.
- **`evidence/`** — evidence rows and the configured evidence commands.
- **`events/`** — the inbox (webhooks are stored first, processed later) and its processor.
- **`routing/`** — the rule table: role × difficulty × risk × prior failures → tier (model, harness, auth, budget) + effort.
- **`gateway/`** — LiteLLM admin client (per-run virtual keys, spend by alias, timeouts).
- **`billing/`** — price table, cost estimate, plan-limit detection and reset time.
- **`worktree/`** — per-task git worktrees (`WorktreeService`) and the one git helper (`Git`).

## Common commands

Run from `orange/`:
- **Run app**: `./gradlew bootRun` — automatically loads `orange/.env` into the process env (see `parseDotEnv()` in `build.gradle.kts`). Copy `orange/.env.example` to start.
- **jOOQ codegen**: runs automatically before `compileJava` via the `nu.studer.jooq` plugin. The `generateJooq` task spins up an embedded Postgres 18 (no Docker needed), runs all Flyway migrations against it, then generates Java sources under `build/generated/sources/jooq/main/java` in package `com.ai.orange.db.jooq`. To regenerate after editing a migration, just `./gradlew generateJooq`.
- **All tests**: `./gradlew test` — JUnit 5 + Testcontainers (Postgres 18). Needs a running Docker daemon and nothing else (no Temporal, no model calls). The run loop and the events processor are off in tests (`src/test/resources/config/application.yaml`); `RunLoopE2EIT` turns them on and takes a task from ready to integrated.
- **Live tests** (tagged `live`, excluded from `test`): `./gradlew liveTest`.
  - `ClaudeCliContractLiveIT` runs the installed `claude` with orange's full command line and an invalid token (no secret, no model call); rerun it after Claude Code updates itself.
  - `WorkerMcpRealClaudeLiveIT` checks that the real `claude` connects to `/mcp/worker` and sees the run's tools (invalid token, no model call).
  - `SubscriptionSmokeLiveIT` makes one tool-less Haiku call on the Max plan (needs `CLAUDE_CODE_OAUTH_TOKEN`).
  - `LiteLlmGatewayLiveIT` exercises the virtual-key lifecycle and the alias lookups against a running LiteLLM (needs `LITELLM_MASTER_KEY`), with no model calls.
- **Single test class**: `./gradlew test --tests com.ai.orange.run.TaskReactorIT`
- **Build**: `./gradlew build`
- **Local infra (Postgres + Grafana LGTM)**: `docker compose up -d`. Spring Boot's `spring-boot-docker-compose` dev dependency also brings these up when running `bootRun` with the `dev` profile.
- **LiteLLM gateway** (only for routing tiers with `auth: gateway`): `docker compose --profile gateway up -d litellm`. It refuses to start without `LITELLM_MASTER_KEY`; `litellm-db-init` creates its database on existing Postgres volumes.
- **Run with Testcontainers-managed Postgres instead of compose**: `./gradlew bootTestRun` using `TestOrangeApplication`.
- **Forward GitHub webhooks to localhost during dev**: `./scripts/forward-webhooks.sh` (requires `gh` + `gh-webhook` extension; reads `GITHUB_ORG` xor `GITHUB_REPO` and `GITHUB_WEBHOOK_SECRET` from `.env`).

The Java toolchain (JDK 27) is provisioned automatically by the foojay resolver if it isn't installed.

Docker socket note: tests on macOS auto-detect `~/.docker/run/docker.sock` and set `DOCKER_HOST` / `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` for Testcontainers — see `tasks.withType<Test>` in `build.gradle.kts`.

## Architecture

### The run loop
Every unit of agent or verification work is a `runs` row (kinds `plan`, `implement`, `ci_fix`, `verify`, `diff_review`, `integrate`; `judge` and `brief` come with the project-space phases). A task has at most one queued or running run (partial unique index).
- **`TaskReactor`** reacts to `task_changed` / `run_changed` (and sweeps every `orange.runs.reactor-sweep`). It locks the task row and applies `ReactorDecision`, a pure rule table: `ready` → implement; `running` → a `ci_fix` after verify gaps, an integration conflict or a failed fix, otherwise implement (with review gaps, or the failed attempt's own feedback); `verifying` → verify, then diff review, then `verified`; `verified` → integrate. Too many failures (`orange.routing.escalation.needs-human-after-failures`) or a worktree that moved under orange → `needs_human`. Nothing happens while paused.
- **`RunScheduler`** claims queued runs (on `run_queued`, when a run ends, and every `scheduler-poll`) up to each kind's slots and `max-concurrent`, and runs each on a virtual thread. Plan runs (`WorkerRunner.runDirect`) take no slot.
- **Leases**: a running run renews its lease every `heartbeat`; `RunReaper` fails expired leases (`lease_expired`) and, at startup, kills and fails runs a previous instance left running (`coordinator_restart`). Neither counts against the task.
- **Ordering rule**: a run stays `running` until its post-run steps (`PostRunSteps`) have landed, so the reactor never queues a task's next run on half-applied state.
- **`SchedulerPause`**: failures that would recur at once pause the scheduler — a plan limit until its reset time, a rejected or missing token until resumed, the gateway down for 5 minutes, a missing binary or broken workspace until resumed. `GET /scheduler`, `POST /scheduler/pause|resume`.

### Running Claude Code (`WorkerRunner`)
1. **Prepare** (`RunPreparer`): the task's worktree (`WorktreeService.ensure` keeps earlier attempts' commits) and a prompt — the task, the operator's reopen note, `input.feedback` to address, how earlier attempts ended, and the base the branch integrates onto. A diff review gets the intent and the whole diff instead (cut at 200,000 characters).
2. **Route** (`RoutingTable`, `orange.routing`) to a tier (model, harness, auth, budget) and an effort. Two failures move the run one tier up (or raise effort at the top tier); risky tasks get at least the large tier.
3. **Credentials** by the tier's `auth`:
   - `subscription` (default): the Max-plan token from `claude setup-token` (`CLAUDE_CODE_OAUTH_TOKEN`); Claude Code talks to Anthropic directly. Anthropic's terms restrict subscription tokens to Claude Code itself, so the token goes only into the `claude` child's environment — never to LiteLLM, logs, `run_events`, `task_events` or `runs`.
   - `gateway`: a LiteLLM virtual key minted for this run (`GatewayClient`), limited to the run's models and budget, always blocked when the run ends; `SpendSync` later books its final spend and deletes it.
4. **MCP**: agent kinds that report get a per-run token (only its SHA-256 is stored) in a `mcp.json` passed as `--mcp-config` and deleted afterwards. Outcomes come only from `/mcp/worker` (`report_result` done|blocked|failed, `request_human`, `submit_review` pass|gaps) — never parsed out of transcripts. Tools per kind come from `ToolPolicy` (implement/ci_fix edit and commit; diff_review is read-only with no Bash).
5. **Start** `claude -p --output-format stream-json --verbose --model … --effort … --setting-sources project,local --strict-mcp-config …`. The prompt goes on **stdin** (never argv). The child environment is built from an allowlist (`orange.harness.claude-code.pass-env`); credential-like variables are dropped even if allowlisted, and `CLAUDE_CONFIG_DIR` points at a per-run directory under `orange.harness.run-root` so the user's own Claude Code login, plugins and hooks never load. The credential and the MCP token are redacted from everything the run writes.
6. **Watch**: events go to `run_events` (`init`, `assistant`, `tool_use`, `tool_result`, `result`, `api_retry`); subscription runs are killed when their estimated cost passes the tier budget; every run is killed at its kind's timeout. Kills take the whole process tree.
7. **Outcome**: no report → `no_outcome`, a `failed` report → `reported_failed`; `FailureReasons` decides what counts (model failures add to `tasks.failures`) and what pauses.

### Verification and integration (project-space spec §7–§8)
An agent's `done` only starts verification; evidence decides "done".
- **Implement done**: with commits on the branch, the head is recorded and the task moves to `verifying`; without, the run fails as `no_commits`.
- **`EvidenceRunner`** (`verify` runs, orange's own code): the worktree must be at the queued head and clean, and every `orange.evidence.commands` entry must pass, run as `sh -c` in the worktree with only the allowlisted environment. Each check is an `evidence` row; failures are gaps that send the task back for a `ci_fix` and count.
- **`diff_review`**: a read-only reviewer compares the diff with the intent; its verdict is evidence. `pass` → `verified`; gaps send the task back for rework and count.
- **`Integrator`** (`integrate` runs, merge mode, one at a time): rebase onto the base; a changed head goes back to `verifying` (the diff review carries over, the commands re-run); otherwise push the base when there is an origin (`orange.integration.remote-push`) and move the local base branch along (`merge --ff-only` in a clean checkout, `update-ref` when not checked out, left alone when dirty). A conflict goes back for a fix without counting; git trouble goes to a person. `integrated` promotes the task's dependents.

Isolation: in process mode Claude Code confines its file tools to the worktree but its Bash tool is not confined. Process mode is for local development; container isolation is the boundary for unattended runs (v2 plan, spec §16).

Tests never call a model: `orange/src/test/resources/fake-claude/FakeClaude.java` is a single-file program that speaks real stream-json and the worker MCP protocol, launched via `com.ai.orange.harness.FakeClaude.command()` / `FakeClaude.register(...)`. With `report_result` allowed it implements and commits; with `submit_review` allowed it reviews and passes. Other scenarios are picked by `[[fake:<name>]]` markers in the prompt (`no-commit`, `report-failed`, `blocked`, `no-outcome`, `dirty`, `wrong-tool`, `review-gaps`, `error`, `hang`, `expensive`, `plan-limit`, `auth-failed`, `leak-env`, `plan`).

### Orange data model
Flyway migrations live under `orange/src/main/resources/db/migration/` (immutable once merged; add `V<n+1>__…` files):

- **`tasks`** — DAG of work units with the evidence-gated lifecycle (`pending → ready → running → verifying → verified → integrated`, plus `needs_human`, `failed`, `cancelled`; see `TaskStatus` and the project-space spec §10). A child becomes `ready` when every parent is `integrated` (trigger `promote_ready_dependents`). `branch` and `head_sha` are what verification and integration work from; `pr_number`/`pr_url` are for the later PR mode; `failures` counts model failures. Every status change fires `pg_notify('task_changed', <id>)`.
- **`runs`** — one row per run: kind, status, role, attempt, route (tier, model, effort, auth, budget), lease, pid, MCP token hash, gateway key alias, cost and tokens, `input` (head or feedback) and `outcome`. Inserts fire `run_queued`, status changes `run_changed`. **`run_events`** holds each run's event stream; **`settings`** holds the scheduler pause.
- **`evidence`** — every check orange ran on a task's head, and every diff review of it.
- **`events`** — the inbox (webhook deliveries, once per delivery id); inserts fire `event_received`.
- **`dev_envs`** — leasable port-pool envs (5 pre-seeded; dormant until UI QA). Returning to `free` fires `pg_notify('dev_env_freed', ...)`.

When adding or modifying tables, follow the existing patterns: `uuidv7()` PKs, `touch_updated_at()` trigger, partial indexes on hot-status queues, `pg_notify` on state changes that callers might `LISTEN` for.

### Webhook ingress
`POST /webhooks/github` is HMAC-authenticated by `GithubWebhookHmacFilter` (verifies `X-Hub-Signature-256` against `orange.github.webhook-secret`); the filter wraps the request via `CachedBodyHttpServletRequest` so the controller can re-read the body. The controller only stores the delivery in the `events` inbox and answers 200 `{stored}`; `EventProcessor` handles stored events one per transaction in id order and parks an event after 5 failed attempts. `GithubEventHandler` only logs: orange integrates verified work itself, and webhooks change tasks only in the later PR integration mode. Security config (`SecurityConfig`) permits this path and lets the HMAC filter authenticate; CSRF is disabled because there are no browser-form writes.

### Operator API
`/tasks` (create, list, events, reopen, replay, cancel — cancel also cancels queued runs and kills the running one), `/tasks/{id}/runs`, `/tasks/{id}/evidence`, `/runs/{id}`, `/runs/{id}/events`, `/events/live` (running runs with their latest events), `/health/aggregate`, `/scheduler`, `/plans`, `/dispatch`, `/dev-envs`. No auth yet: bind it to localhost.

### Spring config
- App config lives in `orange/src/main/resources/application.yaml`. Notable knobs: `orange.runs` (loop switch, worker id, slots and timeouts per kind, lease TTL, heartbeat, sweeps, retention), `orange.evidence` (commands, output tail), `orange.integration.remote-push`, `orange.events` (processing switch, poll, max attempts), `orange.routing` (tiers, rules, risk paths, escalation), `orange.harness` (run root, Claude Code command, env allowlist), `orange.billing` (subscription token, prices), `orange.gateway` (LiteLLM URL, master key, timeouts), `orange.mcp.worker-url`, `orange.worktrees` (root, base repo, base branch, max count), `orange.github.webhook-secret`. All take env-var overrides.

## Environment / secrets

- `orange/.env` is read by `bootRun`/`test`, docker compose and the webhook-forward script; it is `.gitignore`d. `orange/.env.example` lists every key. For end-to-end runs: `CLAUDE_CODE_OAUTH_TOKEN` (from `claude setup-token`), `WORKTREES_BASE_REPO`, and for webhooks `GITHUB_WEBHOOK_SECRET`, `GITHUB_ORG` / `GITHUB_REPO` for the forwarder. `LITELLM_MASTER_KEY` and `ANTHROPIC_API_KEY` are only needed for gateway tiers.
- Do **not** check `.env` files in.
