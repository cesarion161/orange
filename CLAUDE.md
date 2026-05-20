# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

This is a multi-component repo with three peers under the root (no top-level build):

- **`orange/`** — Java 26 / Spring Boot 4 orchestrator. Owns persistence (Postgres + Flyway + JOOQ), workflow state (Temporal), GitHub webhooks, and the dashboard (JTE + htmx). It spawns one agent-runner subprocess per agent run.
- **`agent-runner/`** — Python 3.12 sidecar wrapping the Claude Agent SDK. **One process == one agent run.** Reads NDJSON commands from stdin, drives a `ClaudeSDKClient`, emits NDJSON events on stdout, logs to stderr.
- **`protocol/`** — JSON Schemas (`events.schema.json`, `commands.schema.json`) shared by the two sides. **If you add or change an event/command, edit the schema and the Python `protocol.py` dataclasses together** — they are the wire contract.

`agent-runner/src/agent_runner/protocol.py` mirrors `protocol/events.schema.json`; the orchestrator parses against the same schemas.

## Common commands

### orange (Java)
Run from `orange/`:
- **Run app**: `./gradlew bootRun` — automatically loads `orange/.env` into the process env (see `parseDotEnv()` in `build.gradle.kts`).
- **jOOQ codegen**: runs automatically before `compileJava` via the `nu.studer.jooq` plugin. The `generateJooq` task spins up an embedded Postgres 18 (no Docker needed — `io.zonky.test:embedded-postgres` ships native binaries on the build classpath), runs all Flyway migrations against it, then generates Java sources under `build/generated/sources/jooq/main/java` in package `com.ai.orange.db.jooq`. First build extracts ~50MB of native binaries; subsequent builds reuse them. To regenerate after editing a migration, just `./gradlew generateJooq`.
- **All tests**: `./gradlew test` — uses JUnit 5, Testcontainers (Postgres 18, Temporal). Same `.env` and Docker-socket env vars are wired in.
- **Single test class**: `./gradlew test --tests com.ai.orange.webhook.GithubWebhookHmacFilterTest`
- **Build**: `./gradlew build`
- **Local infra (Postgres + Temporal + Grafana LGTM)**: `docker compose up -d` (uses `compose.yaml`). Spring Boot's `spring-boot-docker-compose` dev dependency also brings these up automatically when running with the `dev` profile via `bootRun`.
- **Run with Testcontainers-managed Postgres instead of compose**: `mvn`-equivalent is `./gradlew bootTestRun` using `TestOrangeApplication`.
- **Forward GitHub webhooks to localhost during dev**: `./scripts/forward-webhooks.sh` (requires `gh` + `gh-webhook` extension; reads `GITHUB_ORG` xor `GITHUB_REPO` and `GITHUB_WEBHOOK_SECRET` from `.env`).

Docker socket note: tests on macOS auto-detect `~/.docker/run/docker.sock` and set `DOCKER_HOST` / `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` for Testcontainers — see `tasks.withType<Test>` in `build.gradle.kts`.

macOS jOOQ codegen note: stock macOS ships `kern.sysv.shmall=1024` (4MB), too small for embedded-postgres `initdb` during `generateJooq` (fails with "could not create shared memory segment"). Run `./orange/scripts/setup-macos-shm.sh --persist` once (bumps to 256MB; requires sudo) to fix it permanently. Workaround for a single build when SHM is tight: `./gradlew test -x generateJooq` — only valid when the cached generated sources still match the schema. Linux is unaffected.

### agent-runner (Python)
Run from `agent-runner/`:
- **Setup**: `uv sync` (requires [uv](https://docs.astral.sh/uv/), Python 3.12+).
- **Run standalone**: `uv run python -u -m agent_runner < example_start.jsonl` — `-u` is **mandatory** (orchestrator buffers stdout otherwise).
- **Tests**: `uv run pytest` (config in `pyproject.toml`: `asyncio_mode = "auto"`, `testpaths = ["tests"]`).
- **Single test**: `uv run pytest tests/test_protocol.py::test_emit_writes_ndjson`
- **Lint**: `uv run ruff check` (rules: `E,F,I,UP,B,ASYNC,SIM`, line-length 100).
- **Type-check**: `uv run mypy src`

The orchestrator invokes the runner as `python -u -m agent_runner` from the venv configured by `orange.agent-runner.python` (defaults to `../agent-runner/.venv/bin/python` — keep that path working or override via `AGENT_RUNNER_PYTHON`).

## Architecture

### Orchestrator ↔ runner protocol
The wire is **NDJSON over the child's stdio**, one JSON object per line, trailing `\n` and flush after every write. See `protocol/README.md`.

- **Stdin (orange → runner)**: `start` (must be first), `hook_response`, `signal`, `cancel`.
- **Stdout (runner → orange)**: `ready`, `assistant_message`, `thinking`, `tool_use`, `tool_result`, `hook_request`, `subagent_start/end`, `cost`, `final`, `error`.
- **Stderr**: human-readable logs; orange tees to its own logs.
- The runner exits 0 only after emitting `final`. Non-zero exit without a `final` is a hard failure.
- `id` correlates round-trip pairs: a `hook_request` from runner is answered by a `hook_response` on stdin with the same `id`.
- **Versioning**: when adding an event/command type, bump `$id` query string (`?v=2`) on both schemas simultaneously, and reject mismatched protocol versions in the `ready` handshake.

### Hooks (`agent-runner/src/agent_runner/hooks.py`)
- **`worktree_guard`** is load-bearing and always installed: blocks `Write`/`Edit`/`NotebookEdit` whose `file_path` escapes the configured `cwd`, and `Bash` invocations whose `cwd` does the same. Combined with `chdir_guard` (which `os.chdir`s into the worktree) this is the sandboxing boundary for an agent run.
- The orchestrator can opt in to round-tripping additional hook events (currently only `PreToolUse`) by passing `options.hooks_via_runner: ["PreToolUse"]` in the `start` command. The runner then emits a `hook_request` per call and waits up to 300s for a `hook_response`; on timeout it **fails open** (worktree guard still applies).

### Orange data model
Three Flyway migrations under `orange/src/main/resources/db/migration/`:

- **`tasks`** — DAG of work units with status FSM (`pending → ready → in_progress → pr_open → dev_ready → in_test → test_done | failed | cancelled`). Hot path is "claim next ready task" via partial index `idx_tasks_ready_queue` + `FOR UPDATE SKIP LOCKED`. Status transitions to `ready`/`dev_ready` fire `pg_notify('task_ready', ...)` so workers don't poll.
- **`agents`** + **`agent_runs`** — agent definitions and per-attempt run history (one row per `(task, attempt)`, prompt stored for replay).
- **`dev_envs`** — leasable port-pool envs (5 pre-seeded). Returning to `free` fires `pg_notify('dev_env_freed', ...)`.

When adding or modifying tables, follow the existing patterns: `uuidv7()` PKs, `touch_updated_at()` trigger, partial indexes on hot-status queues, `pg_notify` on state changes that callers might `LISTEN` for.

### Webhook ingress
`POST /webhooks/github` is HMAC-authenticated by `GithubWebhookHmacFilter` (verifies `X-Hub-Signature-256` against `orange.github.webhook-secret`). The filter wraps the request via `CachedBodyHttpServletRequest` so the controller can re-read the body. Security config (`SecurityConfig`) permits this path and lets the HMAC filter authenticate; CSRF is disabled because there are no browser-form writes.

The controller (`GithubWebhookController`) currently only logs — routing webhook payloads to a Temporal signal is the next planned wiring (see TODO).

### Spring config
- App config lives in `orange/src/main/resources/application.yaml`. Notable knobs: `orange.agent-runner.python` / `orange.agent-runner.module`, `orange.worktrees.root`, `orange.github.webhook-secret`. All take env-var overrides.
- Anthropic Spring AI key comes from `ANTHROPIC_API_KEY`.
- Temporal target defaults to `local` and discovers workers from package `com.ai.orange.workflow` (package not yet present — add new workers there).
- JTE templates live in `orange/src/main/jte/`; precompiled in prod, hot-reloaded in dev (`gg.jte.development-mode=true`).

## Environment / secrets

- `orange/.env` is read by `bootRun`/`test` and the webhook-forward script; it is `.gitignore`d. Required keys when working end-to-end: `ANTHROPIC_API_KEY`, `GITHUB_WEBHOOK_SECRET`, and one of `GITHUB_ORG` / `GITHUB_REPO` for the forwarder.
- Do **not** check `.env` files in.
