# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

- **`orange/`** — Java 27 / Spring Boot 4 orchestrator. Owns persistence (Postgres + Flyway + jOOQ), task state, GitHub webhooks, and the agent runs themselves: it starts Claude Code (`claude -p`) directly, one process per agent run.
- **`orange-mcp/`** — Python stdio MCP shim the Claude Code plugin uses to call orange's REST API.
- **`plugin/`** — the Claude Code plugin (skills, slash commands).
- **`docs/superpowers/`** — the v2 design spec and per-PR implementation plans.

Inside `orange/src/main/java/com/ai/orange/`:
- **`harness/`** — harness-neutral run model (`RunSpec`, `RunEvent`, `HarnessAdapter`, `ProcessRunHandle`) and **`harness/claude/`**, the Claude Code adapter (`ClaudeCodeCommand` builds argv + env, `StreamJsonParser` reads `--output-format stream-json`).
- **`routing/`** — the rule table: role × difficulty × risk × prior failures → tier (model, harness, auth, budget) + effort.
- **`gateway/`** — LiteLLM admin client (per-run virtual keys, spend).
- **`billing/`** — price table, cost estimate, plan-limit detection.
- **`agent/AgentRunService`** — the one place that runs an agent: route → credentials → harness → events → outcome.

## Common commands

Run from `orange/`:
- **Run app**: `./gradlew bootRun` — automatically loads `orange/.env` into the process env (see `parseDotEnv()` in `build.gradle.kts`). Copy `orange/.env.example` to start.
- **jOOQ codegen**: runs automatically before `compileJava` via the `nu.studer.jooq` plugin. The `generateJooq` task spins up an embedded Postgres 18 (no Docker needed), runs all Flyway migrations against it, then generates Java sources under `build/generated/sources/jooq/main/java` in package `com.ai.orange.db.jooq`. To regenerate after editing a migration, just `./gradlew generateJooq`.
- **All tests**: `./gradlew test` — JUnit 5 + Testcontainers (Postgres 18). Needs a running Docker daemon and nothing else.
- **Live tests** (tagged `live`, excluded from `test`): `./gradlew liveTest`. `ClaudeCliContractLiveIT` runs the installed `claude` with orange's full command line and an invalid token (no secret, no model call) — rerun it after Claude Code updates itself; `SubscriptionSmokeLiveIT` makes one tool-less Haiku call on the Max plan (needs `CLAUDE_CODE_OAUTH_TOKEN`); `LiteLlmGatewayLiveIT` exercises the virtual-key lifecycle against a running LiteLLM (needs `LITELLM_MASTER_KEY`), with no model calls.
- **Single test class**: `./gradlew test --tests com.ai.orange.webhook.GithubWebhookHmacFilterTest`
- **Build**: `./gradlew build`
- **Local infra (Postgres + Grafana LGTM)**: `docker compose up -d`. Spring Boot's `spring-boot-docker-compose` dev dependency also brings these up when running `bootRun` with the `dev` profile.
- **LiteLLM gateway** (only for routing tiers with `auth: gateway`): `docker compose --profile gateway up -d litellm`. It refuses to start without `LITELLM_MASTER_KEY`; `litellm-db-init` creates its database on existing Postgres volumes.
- **Run with Testcontainers-managed Postgres instead of compose**: `./gradlew bootTestRun` using `TestOrangeApplication`.
- **Forward GitHub webhooks to localhost during dev**: `./scripts/forward-webhooks.sh` (requires `gh` + `gh-webhook` extension; reads `GITHUB_ORG` xor `GITHUB_REPO` and `GITHUB_WEBHOOK_SECRET` from `.env`).

The Java toolchain (JDK 27) is provisioned automatically by the foojay resolver if it isn't installed.

Docker socket note: tests on macOS auto-detect `~/.docker/run/docker.sock` and set `DOCKER_HOST` / `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` for Testcontainers — see `tasks.withType<Test>` in `build.gradle.kts`.

## Architecture

### Running Claude Code
`AgentRunService.run` is the only path that starts an agent:
1. **Route** the run (`RoutingTable`, `orange.routing` in `application.yaml`) to a tier (model, harness, auth, per-run budget) and an effort. Two failed attempts move the run one tier up (or raise effort at the top tier); risky tasks get at least the large tier.
2. **Credentials** by the tier's `auth`:
   - `subscription` (default): the Max-plan token from `claude setup-token` (`CLAUDE_CODE_OAUTH_TOKEN`), and Claude Code talks to Anthropic directly. Anthropic's terms restrict subscription tokens to Claude Code itself, so the token goes only into the `claude` child's environment — never to LiteLLM, logs, `task_events` or `agent_runs`.
   - `gateway`: a LiteLLM virtual key minted for this run (`GatewayClient`), limited to the run's models and budget, and always blocked when the run ends.
3. **Start** `claude -p --output-format stream-json --verbose --model … --effort … --setting-sources project,local --strict-mcp-config …`. The prompt goes on **stdin** (never argv). The child environment is built from an allowlist (`orange.harness.claude-code.pass-env`); credential-like variables are dropped even if allowlisted, and `CLAUDE_CONFIG_DIR` points at a per-run directory under `orange.harness.run-root` so the user's own Claude Code login, plugins and hooks never load.
4. **Watch**: events go to `task_events` (`ready`, `assistant_message`, `tool_use`, `tool_result`, `final`, `run_finished`); the task heartbeat is refreshed on every event and every 30 s; subscription runs are killed when their estimated cost passes the tier budget; every run is killed at its timeout. Kills take the whole process tree.
5. **Outcome** in `agent_runs` (status, cost, tokens; route + run id in `metadata`). A "usage limit reached" failure is reported as `plan_limit` and a rejected token or key as `auth_failed`; like a missing token, a gateway outage or a cancel, neither counts towards escalation.

Isolation: in this process mode, Claude Code confines its file tools to the worktree but its Bash tool is not confined. Process mode is for local development; container isolation is the boundary for unattended runs (v2 plan, spec §16).

Tests never call a model: `orange/src/test/resources/fake-claude/FakeClaude.java` is a single-file program that speaks real stream-json, launched via `com.ai.orange.harness.FakeClaude.command()` / `FakeClaude.register(...)`. Scenarios are picked by `[[fake:<name>]]` markers in the prompt or the agent's system prompt (`error`, `hang`, `expensive`, `plan-limit`, `auth-failed`, `leak-env`, `plan`).

### Orange data model
Flyway migrations live under `orange/src/main/resources/db/migration/` (immutable once merged; add `V<n+1>__…` files):

- **`tasks`** — DAG of work units with status FSM (`pending → ready → in_progress → pr_open → dev_ready → in_test → test_done | failed | cancelled`). Hot path is "claim next ready task" via partial index `idx_tasks_ready_queue` + `FOR UPDATE SKIP LOCKED`. Status transitions to `ready`/`dev_ready` fire `pg_notify('task_ready', ...)` so workers don't poll.
- **`agents`** + **`agent_runs`** — per-role prompts/tools and per-attempt run history (one row per `(task, attempt)`, prompt stored for replay).
- **`dev_envs`** — leasable port-pool envs (5 pre-seeded). Returning to `free` fires `pg_notify('dev_env_freed', ...)`.

When adding or modifying tables, follow the existing patterns: `uuidv7()` PKs, `touch_updated_at()` trigger, partial indexes on hot-status queues, `pg_notify` on state changes that callers might `LISTEN` for.

### Webhook ingress
`POST /webhooks/github` is HMAC-authenticated by `GithubWebhookHmacFilter` (verifies `X-Hub-Signature-256` against `orange.github.webhook-secret`). The filter wraps the request via `CachedBodyHttpServletRequest` so the controller can re-read the body. Security config (`SecurityConfig`) permits this path and lets the HMAC filter authenticate; CSRF is disabled because there are no browser-form writes. `GithubEventRouter` handles a merged PR (`pr_open → dev_ready`); other events are logged.

### Spring config
- App config lives in `orange/src/main/resources/application.yaml`. Notable knobs: `orange.routing` (tiers, rules, risk paths, escalation), `orange.harness` (run root, Claude Code command, env allowlist), `orange.billing` (subscription token, prices), `orange.gateway` (LiteLLM URL and master key), `orange.worktrees.root`, `orange.github.webhook-secret`. All take env-var overrides.

## Environment / secrets

- `orange/.env` is read by `bootRun`/`test`, docker compose and the webhook-forward script; it is `.gitignore`d. `orange/.env.example` lists every key. For end-to-end runs: `CLAUDE_CODE_OAUTH_TOKEN` (from `claude setup-token`), `GITHUB_WEBHOOK_SECRET`, `ORANGE_GITHUB_TOKEN`, `ORANGE_GITHUB_REPO`, and `GITHUB_ORG` / `GITHUB_REPO` for the forwarder. `LITELLM_MASTER_KEY` and `ANTHROPIC_API_KEY` are only needed for gateway tiers.
- Do **not** check `.env` files in.
