# R3 · orange on the VPS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** orange runs on the VPS as a systemd service in container mode, reachable only over the tailnet; the laptop runs only the plugin, whose reporter queues events on disk while orange is out of reach.

**Architecture:** Four small code changes make orange safe to put behind `tailscale serve` on Linux:
- the API token guards the whole operator API;
- orange listens on loopback plus the run network's gateway only;
- run containers name that gateway as `host.docker.internal`;
- a replayed stop older than `hook-wait` is never judged.

Then the reporter gets its disk queue. A `deploy/` directory holds an idempotent deploy, run from the laptop over SSH: a jar built locally, compose for Postgres and ntfy, a systemd unit, ufw, an sshd drop-in, `tailscale serve`, the worker image built on the host, the run network and two checks (health, the run network guard).

**Tech Stack:** Java 27 / Spring Boot 4 (Tomcat), Python 3 (reporter), bash, Docker Compose, systemd, ufw, Tailscale.

**Spec:** `docs/superpowers/specs/2026-11-12-orange-remote-first-design.md` §6, §8 item 3, builder's calls 1–7.

## Global Constraints

- The repo is public: no IP address, tailnet name, MagicDNS name or device name in any committed file. The deploy discovers them on the host.
- The PAT and `CLAUDE_CODE_OAUTH_TOKEN` never appear in an argv, a log or a committed file. They cross to the VPS on SSH stdin into `/etc/orange/orange.env` (mode 600, root).
- orange runs as the non-root user `orange` in the `docker` group; a coordinator running as root is refused.
- Postgres and ntfy publish on 127.0.0.1 only. orange listens on 127.0.0.1 and on the run network's gateway. ufw denies incoming except on `tailscale0`, port 22/tcp, and the run bridge to orange's port.
- sshd: key only, `PermitRootLogin prohibit-password`.
- With `ORANGE_API_TOKEN` blank everything stays open as today (local development, the test suite).

## Review Focus

1. A request through `tailscale serve` arrives from 127.0.0.1: every operator endpoint must want the token, not just the session ones (Task 1 tests each prefix).
2. The reporter must never block a session: orange down → the hook returns within its 2 s budget, even with a full queue (Task 4 times it).
3. A queued event that orange rejects (400) must not block the queue forever; one refused for auth (401/403) must stay queued (Task 4).
4. A replayed stale stop must not queue a judge run, which would spend the Max plan (Task 3).
5. The extra listen address must not fail startup when the configured list is blank (Task 2 runs the whole suite with it blank).

---

### Task 1: The API token guards the whole operator API

**Files:**
- Modify: `orange/src/main/java/com/ai/orange/config/SecurityConfig.java`, `config/ApiToken.java` (Javadoc)
- Modify: `orange-mcp/src/orange_mcp/server.py`: send `Authorization: Bearer $ORANGE_API_TOKEN` when set; `ORANGE_BASE_URL` falls back to `ORANGE_URL`
- Modify: `.mcp.json` (repo root): `ORANGE_BASE_URL` = `${ORANGE_URL:-http://localhost:8080}`, pass `ORANGE_API_TOKEN`
- Test: `orange/src/test/java/com/ai/orange/config/ApiTokenIT.java`

- [ ] **Step 1: Write `ApiTokenIT`.** RANDOM_PORT, `orange.api.token=t0ken`.
  - `everyOperatorEndpointWantsTheToken`: for each of `GET /tasks`, `/plans`, `/scheduler`, `/runs/<random uuid>`, `/events/live`, `/dev-envs`, `/health/aggregate`, `/api/status`, `/actuator/prometheus`, `/actuator/metrics`, `POST /dispatch`, assert 403 without the header; then assert `GET /tasks` and `GET /scheduler` with `Bearer t0ken` are 200.
  - `probesAndOwnAuthStayOpen`: `GET /actuator/health` → 200 without the token, and `/api/ready` too; `POST /mcp/worker` without the token is not 403 from the API token (401 from the worker filter).
- [ ] **Step 2: Run it.** `./gradlew test --tests com.ai.orange.config.ApiTokenIT`. Expected: FAIL (403 expected, got 200 on `/tasks`).
- [ ] **Step 3: In `SecurityConfig`,** swap every `permitAll()` operator matcher (tasks, plans, dispatch, scheduler, runs, events, dev-envs, health, api/status, actuator metrics and prometheus) for `.access(hasApiToken)`. Keep `permitAll` for `/actuator/health`, `/actuator/info`, `/api/ready`, `/webhooks/github`, `/mcp/worker`, `/error` and `/brief/*`.
- [ ] **Step 4: Run** `ApiTokenIT`, `SecuredPushIT` and `./gradlew test`. Expected: all pass.
- [ ] **Step 5: Shim and the repo's `.mcp.json`;** `uv run --directory orange-mcp python -c 'import orange_mcp.server'` imports.
- [ ] **Step 6: Commit** `feat(api): with a token set, the token guards the whole operator API`.

### Task 2: Listening on Linux: loopback plus the run network's gateway

**Files:**
- Create: `orange/src/main/java/com/ai/orange/config/ExtraListenAddresses.java`, a `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` that adds one `Connector` per address of `orange.server.extra-addresses` (comma list, blank = none) at the factory's port, each with the property `address`.
- Modify: `harness/container/RunnerProperties.java` (`container.hostAddress`, default `host-gateway`) and `DockerCommand.java` (`--add-host host.docker.internal:<hostAddress>`); `application.yaml` (`orange.server.extra-addresses: ${ORANGE_SERVER_EXTRA_ADDRESSES:}`, `orange.runner.container.host-address: ${ORANGE_RUNNER_HOST_ADDRESS:host-gateway}`)
- Test: `config/ExtraListenAddressesIT.java`, `harness/container/DockerCommandTest.java`

- [ ] **Step 1: Tests.**
  - `ExtraListenAddressesIT` (DEFINED_PORT on a free port picked in a `@DynamicPropertySource`, `server.address=127.0.0.1`, `orange.server.extra-addresses=::1`): `GET http://[::1]:<port>/actuator/health` → 200 and `GET http://127.0.0.1:<port>/actuator/health` → 200.
  - `DockerCommandTest.aHostAddressNamesTheHost`: with `hostAddress=10.0.0.1` the argv has `--add-host`, `host.docker.internal:10.0.0.1`; the default still gives `host-gateway`.
- [ ] **Step 2: Run them.** Expected: FAIL (connection refused on ::1; argv has host-gateway).
- [ ] **Step 3: Implement** as in Files.
- [ ] **Step 4: Run** both and `./gradlew test`. Expected: pass.
- [ ] **Step 5: Commit** `feat(runner): orange listens where the run network reaches it, and runs name that address`.

### Task 3: A stop older than hook-wait is never judged

**Files:**
- Modify: `orange/src/main/java/com/ai/orange/session/SessionService.java`, `judging()`: `Judging.OFF` when `Duration.between(e.at(), now) > judge.hookWait()`
- Test: the session judge IT (`SessionJudgeIT` or the existing one that queues judge runs)

- [ ] **Step 1: Test `aReplayedStopOlderThanHookWaitIsNotJudged`:** a stop whose `at` is now − (hook-wait + 1 min) and whose last message asks a question. Assert: no `judge` run queued for the session, session not `judging`, an open question item (the rules applied). A stop at now − 1 min is still judged.
- [ ] **Step 2: Run.** Expected: FAIL (a judge run is queued).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the class and `./gradlew test`. Expected: pass.
- [ ] **Step 5: Commit** `fix(sessions): a stop that arrives after its hook stopped waiting is not judged`.

### Task 4: The reporter queues on disk and replays in order

**Files:**
- Modify: `plugin/hooks/orange-report.py`; `plugin/.claude-plugin/plugin.json` → 0.2.3
- Test: `orange/src/test/java/com/ai/orange/session/SessionReporterIT.java`

Design (builder's calls): the queue is a directory `<node dir>/queue/`, one file per event named `<time_ns>-<event id>.json`, written to a temp name and renamed. A hook:
1. builds its event;
2. if the queue is empty, posts it; on a failure it may retry (connection error, timeout, 5xx, 401, 403, 429), it enqueues it;
3. if the queue is not empty, enqueues its event and flushes: under a non-blocking `flock` on `<queue>/.lock` (another hook flushing → return), oldest first, delete each file once orange took it;
   - a retryable failure stops the flush;
   - any other 4xx drops that file;
   - the whole hook keeps within `ORANGE_REPORT_TIMEOUT`;
4. prunes before flushing: files older than 7 days go, and the newest 1000 stay.

A stop polls for its verdict only if its own event was delivered.

- [ ] **Step 1: Tests.**
  - `orangeDownQueuesTheEventAndReturnsAtOnce`: ORANGE_URL at a closed port, a `user-prompt` hook returns 0 within 4 s, leaving one file in `queue/`.
  - `theNextHookReplaysTheQueueFirstInOrder`: two hooks while down (`session-start`, then `user-prompt`), then a `stop` hook with orange up: the queue is empty, and `session_events` for the session are `session_started`, `user_prompt`, `stopped` in that order.
  - `aReplayIsIdempotent`: copy a queued file before the replay, replay, put the copy back, hook again: one `user_prompt` event, not two.
  - `theQueueIsBounded`: 1005 synthetic files plus one with an mtime 8 days old; one hook while down leaves 1000 files, none older than 7 days.
  - `aRejectedEventIsDroppedNotRetried`: a queued file whose JSON lacks `type` (400) is removed by the next flush, and the hook's own event arrives.
- [ ] **Step 2: Run** `SessionReporterIT`. Expected: the new tests FAIL (no `queue/` directory).
- [ ] **Step 3: Implement** in `orange-report.py`: `queue_dir()`, `enqueue(event)`, `prune()`, `flush(deadline, headers)` and `post(event, headers, timeout) -> "ok"|"retry"|"drop"`; update the docstring.
- [ ] **Step 4: Run** `SessionReporterIT` and `./gradlew test`. Expected: pass.
- [ ] **Step 5: Commit** `feat(reporter): queue events on disk while orange is out of reach, replay them in order`.

### Task 5: The deploy

**Files (all new, under `deploy/`):**
- `deploy/deploy.sh [host]` (laptop; default host `orange-vps`):
  - `./gradlew bootJar`;
  - rsync `deploy/vps/`, `orange/worker/` and the jar to `/opt/orange/`;
  - `push-secrets.sh`;
  - `ssh root@host /opt/orange/deploy/setup.sh`;
  - prints the result of the checks.
- `deploy/push-secrets.sh [host]`: reads `CLAUDE_CODE_OAUTH_TOKEN` and `ORANGE_GITHUB_TOKENS` from `orange/.env` and writes them into `/etc/orange/orange.env` on the host over SSH stdin; never in an argv.
- `deploy/vps/setup.sh` (root, idempotent; safe to rerun):
  1. the `orange` user (system, home `/var/lib/orange`, `docker` group) and its directories;
  2. `/etc/orange/orange.env` (600): missing generated values are added and never overwritten (Postgres password, `ORANGE_API_TOKEN`, ntfy publisher token, the attention topic), plus fixed values (container mode, repo, run and worktree roots, the datasource, listen addresses, `ORANGE_PUBLIC_URL` from the host's MagicDNS name);
  3. the run network: `orange-runs`, a fixed subnet, bridge name `orange-runs0`, ICC off, label `orange.managed=true`;
  4. `compose.yaml` up: Postgres 18 and ntfy, both on 127.0.0.1, with ntfy users and access;
  5. Claude Code at the worker's pinned version for the `orange` user (plan, brief and judge runs run on the host);
  6. the worker image built as `orange` when its inputs' hash changed;
  7. the systemd unit (`orange.service`) installed, restarted, and waited on for health;
  8. ufw rules and an sshd drop-in, validated with `sshd -t` and only reloaded if valid;
  9. `tailscale serve`: 443 → orange, 8443 → ntfy;
  10. checks: health, the guard from a container on the run network (`/tasks` 403, `/mcp/worker` not 403), outbound HTTPS from a run container.
- `deploy/vps/orange.service`, `deploy/vps/compose.yaml`, `deploy/vps/ntfy/server.yml.tmpl`, `deploy/README.md` (what it does, how to rerun, where secrets live, how to rotate).

- [ ] **Step 1:** `bash -n` on every script, and `shellcheck` if it is installed. Expected: clean.
- [ ] **Step 2: Commit** `feat(deploy): orange on the VPS, one idempotent script`.

### Task 6: Docs

- [ ] CLAUDE.md: the API token's reach, the listen addresses and host address, the reporter queue, the stale stop, a new "Deploy" section; `.env.example` lists the new keys. Commit `docs: R3, orange on the VPS`.

### After merge (live; R3's exit)

1. Run `deploy/deploy.sh`.
2. Retire the laptop's local stack.
3. Point the plugin at the VPS (Claude Code user settings `env`: `ORANGE_URL`, `ORANGE_API_TOKEN`).
4. Register the sandbox repo as a project with `sh test.sh` in force as evidence, and run one task to integrated.
5. Send a test push to the phone.
6. Replay a queue across an outage.
7. Watch a scheduled brief arrive.
