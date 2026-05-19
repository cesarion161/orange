# orange-ai TODO

Living tracker for the multi-agent feature-factory roadmap.
See `/Users/alexlexa/.claude/plans/i-will-write-here-humming-cupcake.md` for the rationale and architectural decisions behind each item.

**Status legend:** `NOT_STARTED` · `IN_PROGRESS` · `BLOCKED` · `DONE`

---

## Phase 0 — plumbing
- [x] JOOQ codegen wired into Gradle — Status: DONE (embedded-postgres 18 + Flyway in `generateJooq` doFirst; output `build/generated/sources/jooq/main/java`, package `com.ai.orange.db.jooq`)
- [x] V4 migration: `tasks.pipeline TEXT NOT NULL DEFAULT 'dev_only'` — Status: DONE
- [x] V5 migration: seed `agents` rows for `dev`, `tester`, `planner` — Status: DONE

## Phase 1 — task storage layer (Java)
- [x] `TaskRepository.claimNextReady(role)` — `FOR UPDATE SKIP LOCKED` on `idx_tasks_ready_queue` — Status: DONE
- [x] `TaskRepository` CRUD + FSM-aware `transitionStatus` — Status: DONE
- [x] `TaskEdgeRepository` (insert / parentsOf / childrenOf) — Status: DONE
- [x] `TaskEventRepository.append(taskId, actor, eventType, payload)` — Status: DONE
- [x] `AgentRepository` + `AgentRunRepository` — Status: DONE
- [x] `DagValidator.validate(nodes, edges)` — iterative DFS cycle check, generic over key — Status: DONE
- [x] `TaskService.createGraph(defs, edges)` — validates DAG, transactional insert, dep-free tasks → ready — Status: DONE
- [x] `PgListenerService` — virtual-thread LISTEN loop on `task_ready` + `dev_env_freed`, bridges to `ApplicationEventPublisher`, dedicated DriverManager connection so Hikari `maxLifetime` doesn't evict — Status: DONE
- [x] Concurrent-claim integration test — 4 virtual workers vs 20 ready tasks, no duplicates — Status: DONE
- [x] DAG cycle-rejection integration test (+ duplicate-key, unknown-edge cases) — Status: DONE

## Phase 2 — worktree + agent-runner subprocess
- [x] `WorktreeService.create(taskId, baseRef)` — `git worktree add ${orange.worktrees.root}/${taskId}` — Status: DONE
- [x] `WorktreeService.remove(taskId)` + `prune` — Status: DONE
- [x] `WorktreeService.gcStale()` — sweep non-UUID dirs + git prune — Status: DONE
- [x] `AgentRunnerProcess` — `ProcessBuilder`, sealed `Event`/`Command`, Jackson polymorphism, virtual-thread readers, AutoCloseable lifecycle — Status: DONE
- [x] `AgentRunnerLauncher` Spring factory — Status: DONE
- [x] NDJSON event reader: persist to `task_events`, update `agent_runs.heartbeat_at` — Status: DONE (Phase 3 — `PipelineActivitiesImpl.persistEvent` pumps every NDJSON event into `task_events` and bumps `tasks.heartbeat_at` per event)
- [x] `hook_request` round-trip writer (`hook_response` to stdin) — Status: DONE (Java side; orchestrator decision logic = Phase 3)
- [x] Heartbeat reaper (scheduled): stale `in_progress` tasks → `failed`, active runs → `failed` — Status: DONE
- [x] **agent-runner**: emit `cost` events from SDK metadata — Status: DONE
    - `runner._translate` now handles `SdkResultMessage` (emitted by the SDK with `total_cost_usd` + `usage`) and forwards a `Cost` event with the dollar amount and a flattened token map. Orange's `PipelineActivitiesImpl.persistEvent` already accumulates these into `agent_runs.cost_usd`.
- [x] **agent-runner**: emit `final.status='failure'` on exception path — Status: DONE
- [x] **agent-runner**: consume `signal` stdin command — Status: DONE (forwarded to SDK as user message)
- [x] Subprocess-spawn integration test — Status: DONE (`AgentRunnerProcessIT` × 3 cases via stub Python; `WorktreeServiceIT` × 4 cases against per-test git repo)

## Phase 3 — first end-to-end (dev-only)
- [x] `DevTaskWorkflow` interface (Temporal) — claim → worktree → run → finalize — Status: DONE
- [x] `DevTaskWorkflowImpl` — sequential happy path with try/catch + cleanup — Status: DONE
- [x] `DevTaskActivities` interface + `DevTaskActivitiesImpl` — Status: DONE (real activity logic: pumps NDJSON events into `task_events`, finalises `agent_runs`, transitions `tasks.status`, persists per-event)
- [x] Temporal worker registration via `@WorkflowImpl` / `@ActivityImpl` annotations on `com.ai.orange.workflow` package — Status: DONE
- [x] `WorkflowStarter` — `@EventListener` on `PgNotificationEvent` + `ApplicationReadyEvent` drain; idempotent workflow-id `task-<uuid>`; gated by `orange.workflow.auto-start` — Status: DONE
- [x] `TaskRepository.claimSpecific(taskId, workerId)` — idempotent claim for retry-safety — Status: DONE
- [x] `TaskRepository.findReady()` — used by startup drain — Status: DONE
- [x] `POST /tasks` + `GET /tasks/{id}` REST endpoints — Status: DONE
- [x] `AgentRunnerProcess.awaitTermination` now waits for stdout drain — Status: DONE (closed a race where the Final event was lost when the subprocess exited very fast)
- [x] E2E happy-path verification — Status: DONE (`DevTaskActivitiesIT` × 2: full happy path landing at `pr_open` + second-worker-can't-claim, against a real temp git repo + stub Python script)

## Phase 4 — configurable pipelines + tester loop
- [x] `Pipeline`, `Stage`, `OnFailure`, `StageOutcome` types — Status: DONE
- [x] `PipelineRegistry` with `dev_only`, `dev_qa`, `dev_review_qa` — Status: DONE
- [x] `Stage(name, role, onFailure: RETRY_PREVIOUS | FAIL_TASK)` — Status: DONE
- [x] Renamed `DevTaskWorkflow` → `PipelineWorkflow`; workflow now takes `(taskId, Pipeline)` and iterates stages with retry-previous + MAX_STAGE_ATTEMPTS cap — Status: DONE
- [x] `runStage(taskId, role, worktreePath, augmentation)` activity replaces `runAgent` — Status: DONE
- [x] Tester `report.md` ingestion via `final.artifacts.report` (default `report.md`); `parseVerdict` reads first line for VERDICT: PASS/FAIL — Status: DONE
- [x] Augmentation = previous tester report appended to next dev prompt — Status: DONE
- [x] `WorkflowStarter` resolves pipeline via `PipelineRegistry` and passes the resolved `Pipeline` into the workflow — Status: DONE
- [x] Pipeline integration test (`PipelineRetryIT`): dev → tester(FAIL) → dev(with augmentation) → tester(PASS) → `pr_open`, asserting 4 agent_runs rows — Status: DONE

## Phase 5 — GitHub integration
- [x] `GithubProperties` (token, repo, base-branch, default-reviewers, mergeWaitTimeout, webhookSecret, webhookPath) — replaces older `GithubWebhookProperties` — Status: DONE
- [x] `GithubClient` interface + `KohsukeGithubClient` impl (real, kohsuke github-api), guarded for unconfigured env — Status: DONE
- [x] `GithubPrService.openPrForTask(taskId, worktreePath)` — pushes branch via shelled `git push`, opens PR via client, requests reviewers, stamps PR metadata, embeds `<!-- orange-task: <uuid> -->` in body — Status: DONE
- [x] PR metadata stamped onto `tasks.metadata` (`pr_number`, `pr_url`, `pr_repo`); `TaskRepository.findByPullRequest(repo, number)` — Status: DONE
- [x] `openPr` activity in `PipelineActivities` + workflow integration (called after stages succeed, before `markPrOpen`) — Status: DONE
- [x] `markDevReady` activity for the post-merge transition — Status: DONE
- [x] `GithubEventRouter` parses webhook payload, looks up task by `(repo, pr_number)` then by body marker fallback; transitions `pr_open` → `dev_ready` on `pull_request.closed && merged=true`; logs `pull_request_review.submitted` (signal-driven flow deferred to Phase 6) — Status: DONE
- [x] `GithubWebhookController` replaces the TODO log with `router.route(event, body)` — Status: DONE
- [x] `pull_request_review.submitted` → `prReviewed` Temporal signal — Status: DONE
- [x] `pull_request.closed && merged=true` → `prMerged` Temporal signal + `Workflow.await` stage — Status: DONE
    - `PipelineWorkflow` gains three `@SignalMethod`s: `prReviewed(ReviewInfo)`, `prMerged()`, `prClosedUnmerged()`.
    - After `markPrOpen`, workflow enters a `Workflow.await` loop instead of exiting. Branches: `prMerged` → markDevReady + exit; `prClosedUnmerged` → markFailed + exit; `prReviewed` with `state=changes_requested` → re-run dev stage with review body as augmentation, push to existing branch (new activity `pushBranchUpdate`), loop back. Capped at 5 review retries to stop a stubborn reviewer from looping forever.
    - `GithubEventRouter` now sends signals via untyped `WorkflowStub.signal(...)`, falling back to direct DB transition only when no workflow is alive for the task (back-compat for pre-await tasks).
    - `ReviewInfo` record carries `state`/`body`/`reviewer` to the workflow.
- [x] Tests: `GithubEventRouterIT` × 4 (merged → DEV_READY, closed-unmerged → no-op, unknown PR → no-op, body-marker fallback) and `GithubPrServiceIT` × 1 (real bare-remote push + stub `GithubClient` + metadata stamp) — Status: DONE

## Phase 6 — resource governance
- [x] `DevEnv` record + `DevEnvStatus` enum — Status: DONE
- [x] `DevEnvRepository.tryLease(holder)` — `FOR UPDATE SKIP LOCKED` over the partial index `idx_dev_envs_free` — Status: DONE
- [x] `DevEnvRepository.release(id)` — atomic update; the V3 `notify_dev_env_freed` trigger fires NOTIFY for us — Status: DONE
- [x] `DevEnvService.acquire(holder, timeout)` — blocking front-end; subscribes to `PgNotificationEvent` for channel `dev_env_freed` and combines that with a poll-tick fallback so missed notifications never deadlock — Status: DONE
- [x] Worker concurrency cap wired in `application.yaml` via `spring.temporal.workers[*].capacity.max-concurrent-activity-executors` (env: `ORANGE_AGENTS_MAX_CONCURRENT`, default 5) — Status: DONE
- [x] Worktree count cap (already enforced in `WorktreeService.create` since Phase 2; default `orange.worktrees.max-count=20`) — Status: DONE
- [x] Lease-blocking integration test — `DevEnvServiceIT` × 3: 5-way concurrent claim with no duplicates, 6th-waiter blocks until release wakes it, timeout returns empty — Status: DONE
- [x] Activity wrappers `acquireDevEnv` / `releaseDevEnv` for workflow integration — Status: DONE (Phase 8 — wired into `PipelineActivities` + `PipelineWorkflowImpl`; tester stages auto-lease, compose orchestration optional)
- [x] **awaitTermination deadlock fix (agent-runner stdin pump)** — Status: DONE
    - Python side used `asyncio.to_thread(sys.stdin.readline)`, which blocks a default-executor thread forever on stdin. `asyncio.run()` shutdown waits for the executor → process never exited → Java side's `process.waitFor` looped indefinitely.
    - Rewrote `_stdin_pump` + `_read_start` to use a shared `asyncio.StreamReader` via `loop.connect_read_pipe(sys.stdin)`. Reads are properly async + cancellable; subprocess exits cleanly after emitting `final`.
    - End-to-end smoke now flows: workflow start → worktree → runStage → agent exits → openPr → markFailed/markPrOpen, all within seconds.
- [x] **claim_complete no-PR honesty** — Status: DONE
    - `ClaimService.complete` previously always transitioned IN_PROGRESS → PR_OPEN, even when no PR existed. Now: if `artifacts.pr_url` is present → status=PR_OPEN + `pr_repo`/`pr_number`/`pr_url` stamped on metadata; otherwise → status=DEV_READY ("work done, no PR pending"). FSM `IN_PROGRESS → DEV_READY` added. `claim_complete` event now records the resolved `new_status`. Tests updated to cover both paths.
- [x] **Server-side auto-open-PR on complete** — Status: DONE
    - `complete` accepts an `openPr` flag (`true` | `false` | `null` auto-default).
    - Decision tree: caller passed `pr_url` → trust them, PR_OPEN; else if `openPr != false` AND (`openPr=true` OR `GithubProperties.isPrCreationConfigured()`) → invoke `GithubPrService.openPrForTask` server-side; on success → PR_OPEN with metadata stamped; on failure → log + append `pr_open_skipped` event with reason + fall through to DEV_READY; else → DEV_READY.
    - Smoke-verified all three branches: `openPr=false` → dev_ready clean; `openPr=auto+no-config` → dev_ready clean; `openPr=true+no-remote` → dev_ready with a `pr_open_skipped` event carrying the git-push error. The success path is covered by the existing `GithubPrServiceIT`.
    - `claim_complete` event records `auto_opened_pr` boolean so the dashboard can tell at a glance.
    - `CompleteClaimRequest` DTO + `orange_complete_task` MCP shim both expose the new `openPr` / `open_pr` field.
- [x] **Sub-agent execution pattern in orange-orchestrate skill** — Status: DONE
    - journey.md rewritten: orchestrator never does the work itself AND never holds a claim. Each sub-agent claims its own task via `orange_claim_next_task` (MCP tool, not curl), does work in its worktree, completes via `orange_complete_task`. Orchestrator decides batch size from `orange_list_tasks` + `orange_concurrency`, then spawns N sub-agents in one message → concurrent execution. Orchestrator monitors via `orange_status_live`. Explicit anti-pattern callout: "do NOT do the work in this chat — it serializes everything and pollutes the monitoring context".
    - Two clarifications fixed: (a) ownership: sub-agents own their own leases end-to-end, including heartbeats; orchestrator never holds a claim token. (b) tooling: MCP tools (`orange_*`) are the prescribed path; the curl-equivalents are noted only as a dev-time fallback for when the chat's MCP tool snapshot is stale.
    - Demonstrated live in a (mid-session-snapshot) chat: 3 sub-agents created 3 files on 3 separate branches in 3 worktrees, ~15s in parallel, Max subscription, zero API tokens. In a fresh plugin-installed session this would use the MCP tools natively.
- [x] **Latest-stack version bump** — Status: DONE
    - Spring Boot 4.0.6 → **4.1.0-RC1**
    - Spring AI BOM 2.0.0-M5 → **2.0.0-M6**
    - Temporal SDK 1.23.2 (`temporal-spring-boot-starter-alpha`) → **1.30.1 (`temporal-spring-boot-starter`)** — fixes protobuf-4 mismatch with newer Temporal server, plus the artifact was renamed at 1.24.
    - Flyway 11.1.0 → **12.6.1**
    - PostgreSQL JDBC 42.7.5 → **42.7.11**
    - embedded-postgres 2.1.0 → **2.2.2**; binaries BOM 18.0.0 → **18.3.0**
    - nu.studer.jooq plugin 10.0 → **10.2.1**
    - Temporal server docker image stays at 1.29.6.1 (no 1.30 docker image yet; SDK is forward-compat with the prior major).
    - kohsuke github-api 1.330 left as-is (2.0-rc.6 has a missing transitive dep on Maven Central).
    - `orange.concurrency.per-role: {}` removed from yaml (Spring Boot 4.1's stricter binder treats `{}` as a literal string).
    - Full test suite still green against new versions.

## Phase 7 — planner agent
- [x] Planner agent system prompt + artifact contract (`architecture.md` + `plan.json`) — Status: DONE (already seeded in V5; Phase 7 just consumes it)
- [x] `PlannerProperties` (output-root, run-timeout) + `PlanJson` wire-format record — Status: DONE
- [x] `PlannerService.plan(description)` — looks up planner agent, prepares per-plan output dir, launches the runner, captures `final.artifacts`, parses `plan.json`, returns a `PlanResult` — Status: DONE
- [x] Hand-off into `TaskService.createGraph` (validates DAG + inserts atomically + marks dependency-free tasks `ready`) — Status: DONE
- [x] `POST /plans` REST endpoint + `GET`-friendly `PlanResponse` DTO — Status: DONE
- [x] Planner integration test — `PlannerServiceIT` × 2: stub planner produces a 3-task linear DAG → assertions on task count, root-only-READY, edges persisted, tester role on the test task; plus per-plan UUID output dir isolation — Status: DONE

## Phase 8 — chat orchestrator UX (turn the parts into a journey)

Goal: a user installs one Claude plugin and gets a coherent loop — idea → planner → review → execution → live narration → post-merge gates — without ever opening a browser. We have the execution backbone; this phase is the glue that fronts it.

Reference: `/Users/alexlexa/.claude/projects/-Users-alexlexa-stuff-2026-orange-ai/memory/orchestrator_design.md` carries the design rationale and open questions behind each item below.

- [x] **Per-task pipeline + overrides in `plan.json`** — Status: DONE
    - `PlanJson.PlanTask` gains optional `pipeline` (string, validated against `PipelineRegistry`) and `overrides` (`Map<String,Object>` — `skip_review`, `skip_test`, `requires_auth` recognized, unknown keys preserved).
    - Both `PlannerService.plan` (headless) and `submitPlan` (chat-as-planner) route through a shared `toDefs(tasks, callerDefault)` helper. `submitPlan` accepts an optional `defaultPipeline` parameter for the caller; null falls back to `dev_only`.
    - Overrides round-trip into `tasks.metadata.overrides` so downstream stages can consult them later.
    - Validation: unknown task-level pipeline OR unknown caller default → `IllegalArgumentException` → `ClaimController` exception handler maps to HTTP 422 with the reason inline; no partial DAG insert (transactional rollback).
    - V7 migration rewrites the planner agent's `system_prompt` to document the new `pipeline` + `overrides` fields and when to pick which pipeline.
    - MCP shim: `orange_submit_plan` takes an optional `default_pipeline`; 422 surfaces verbatim to the chat.
    - Tests: `PlannerSubmitPlanIT` × 4 (mixed pipelines persisted, caller default applies to bare tasks, unknown task-pipeline rejects whole plan, unknown caller default rejects). Existing `PlannerServiceIT` × 2 still green.
- [x] **Claude plugin packaging** — Status: DONE
    - `plugin/` subfolder of the monorepo. Layout: `.claude-plugin/plugin.json` (manifest), `.mcp.json` (MCP server config referencing `${CLAUDE_PLUGIN_ROOT}/../orange-mcp`), `skills/orange-orchestrate/SKILL.md`, `README.md`.
    - Install (dev): `claude --plugin-dir /path/to/orange-ai/plugin`.
    - Repo-root `.mcp.json` left in place for the dev-from-the-repo workflow; plugin is the end-user entry point.
    - Marketplace JSON for persistent install is deferred (would need to vendor orange-mcp into the plugin tree or publish a separate plugin repo).
- [x] **Orchestrator skill (`orange-orchestrate`)** — Status: DONE
    - `plugin/skills/orange-orchestrate/SKILL.md` — tight trigger + skeleton + critical rules (heartbeat hygiene, cwd boundary, per-task pipeline in plan not globally, stateless planner, cancel hygiene). Stays under loading-context budget.
    - `journey.md` — full step-by-step playbook (intent detection, server-check + start, follow-up questions with derivation rules for `default_pipeline`, chat-as-planner flow, user review with re-plan loop, execution in chat-as-executor mode, narration, failure-mode recovery table).
    - `plan-template.md` — architecture.md + plan.json templates with rules for picking `pipeline` and `overrides` per task, plan-sizing rules cross-referencing the `project_planner_scope.md` memory.
    - Triggers on multi-task software-dev intents; explicitly defers for single-file edits / pure exploration / direct manual MCP calls.
- [x] **`orange_status_live` MCP tool + `orange_envs`** — Status: DONE
    - `GET /events/live?role=&perTaskLimit=` returns every `in_progress` task with the last N events per task (clamped 1–50). Backed by `TaskRepository.findInProgress(role)` + `TaskEventRepository.findLatestByTask`. Client tracks per-task max-event-id locally for incremental narration.
    - `GET /dev-envs` returns every seeded env with status / ports / `leasedBy` / metadata.
    - `StatusController` owns both endpoints; `LiveTaskResponse` + `DevEnvResponse` DTOs parse JSONB columns into JsonNode.
    - `SecurityConfig` permits `/claims/**`, `/events/**`, `/dev-envs` for the operator-driven phase (auth lands later — fixed a latent 403 on the chat-as-executor paths too).
    - MCP shims: `orange_status_live(role?, per_task_limit?)`, `orange_envs()`. Skill updated to call them instead of looping list+tail.
    - Tests: `StatusControllerIT` × 3 (live with one in_progress task surfaces the `claim_acquired` event, role filter excludes other roles, dev-envs lists 5 seeded). Full suite green.
- [x] **Per-role concurrency caps** — Status: DONE (chat-as-executor path; headless still gated by Temporal cap — see follow-up below)
    - `orange.concurrency.per-role.{role}=N` config (default `{}` = unlimited per role). Approximate cap (no transactional lock around the count) — two workers racing past threshold may both pass, the next one is refused. Good enough as a throttle.
    - `ConcurrencyGate` service consults `TaskRepository.countInProgress(role)`; `ClaimService.claimNext` refuses claims when at cap and logs the reason. `usage()` snapshot always surfaces seeded roles (dev/tester/reviewer/planner) plus any role with a configured cap.
    - `GET /concurrency` returns `[{role, inProgress, cap, atCap}]`. `orange_concurrency` MCP tool prints e.g. `tester 3/3 (AT CAP)`. Skill knows to consult it when a claim returns null.
    - `SecurityConfig` permits `/concurrency`. Tests: `ConcurrencyGateIT` × 3 (third dev claim refused at cap=2, release frees a slot, cap doesn't bleed across roles, usage snapshot includes baseline roles).
    - Decided NOT to split Temporal task queues per role — the chat-side cap covers the immediate operator need; headless workers are still capped by the existing global `ORANGE_AGENTS_MAX_CONCURRENT`. Unified mixed-mode cap is a follow-up if/when both modes run together.
- [x] **Auto env preparation — lease + workflow wiring** — Status: DONE (lease + prompt threading; docker compose orchestration follow-up)
    - `Stage` gains `requiresEnv` flag; `PipelineRegistry` marks tester stages in `dev_qa` + `dev_review_qa` (via `.withEnv()`).
    - `PipelineActivities.acquireDevEnv(taskId)` (long-running, blocks up to `orange.envs.acquire-timeout=30m` on `DevEnvService.acquire`) returns `EnvInfo(envId, name, ports map)`. `releaseDevEnv(envId)` is the idempotent counterpart.
    - `PipelineActivities.runStage` now takes an optional `EnvInfo`. When non-null, the agent prompt gains a "Leased dev environment: env-N" block with each port labeled — the tester reads URLs from it instead of guessing.
    - `PipelineWorkflowImpl` acquires before `requiresEnv` stages (separate activity stub, 35m timeout), passes env into `runStage`, and releases in a finally block so the env is returned regardless of stage outcome.
    - `task_events` gets an `env_acquired` row per lease.
    - Tests: `DevEnvActivitiesIT` × 2 (acquire returns ports + records the event + leases the row; pool exhausted → timeout exception). Existing pipeline ITs pass `null` for env where unchanged.
- [x] **Docker compose orchestration for leased envs** — Status: DONE
    - `EnvComposeRunner` service. `orange.envs.compose-file` config (blank = no-op, back-compat). On lease, runs `docker compose -f <file> -p orange-<env-name> up -d --wait --remove-orphans` with env vars exported (`ORANGE_PORT_WEB/API/DB`, `ORANGE_DATA_DIR`, `ORANGE_FIXTURE_SET`, `ORANGE_AUTH_BYPASS_TOKEN`) so the compose template interpolates them. On release, `down -v --remove-orphans` (failures logged, not propagated).
    - `acquireDevEnv` runs compose up; failure releases the lease and bubbles so the stage fails with a clear cause. `releaseDevEnv(envId)` → `releaseDevEnv(EnvInfo)` so down has the project name handy.
    - `EnvComposeRunnerTest` × 2 (disabled when blank, enabled when configured). Live compose path will get exercised in the end-to-end test pass.
- [x] **Env model fleshing — metadata-side** — Status: DONE (Java side; app-side bypass-token enforcement is a follow-up app concern)
    - V8 migration seeds `data_dir`, `auth_bypass_token`, `fixture_set` into each seeded env's existing `metadata` JSONB. Operator overrides survive a re-seed (`|| metadata` precedence in the upsert).
    - `EnvInfo` gains `dataDir`, `authBypassToken`, `fixtureSet`. `acquireDevEnv` parses them from `dev_envs.metadata`.
    - Prompt env block now renders ports as `http://localhost:<port>` URLs, plus `data_dir` / `auth_bypass_token` / `fixture_set` / a `./login.sh` hint, and instructs the tester to prefer the bypass token over screen-scraping a login form.
    - Kept storage in JSONB instead of typed columns: keeps `dev_envs` schema flexible per-project and dodged a SHMALL-driven codegen blocker on stock macOS (see follow-up below). `DevEnvResponse` already exposes `metadata` so the JSON shape is visible to the chat verbatim.
    - `DevEnvActivitiesIT` extended to assert all four metadata fields surface on `EnvInfo`. Full suite green.
- [x] **App-side `auth_bypass_token` enforcement** — Status: DROPPED (out of scope for orange)
    - Each project orange drives is responsible for its own dev-mode auth escape hatch (env var like `DEV_NO_AUTH=true`, a seeded test user, or `./login.sh`). The `auth_bypass_token` convention remains documented for projects that want a header-based hook, but orange never enforces it — it'd live in the user's app code, not here.
- [x] **macOS SHMALL workaround for embedded-postgres codegen** — Status: DONE
    - `./orange/scripts/setup-macos-shm.sh [--persist]` bumps `kern.sysv.shmmax`/`shmall` to 256MB and optionally writes the values to `/etc/sysctl.conf` so they survive reboot. CLAUDE.md documents the one-line fix + the `-x generateJooq` workaround for the no-schema-change case.
- [x] **Post-merge build verification + worktree disposer** — Status: DONE (periodic regression deferred to follow-up)
    - `PostMergeVerifier` service runs the configured `orange.post-merge.build-command` (default blank = skip) against the freshly fetched `origin/main`. On success: stamps `metadata.main_verified_at` + appends `main_build_ok` event. On failure: appends `build_broken` event + flags `metadata.regression_suspect=true` + `regression_suspect_at`.
    - `GithubEventRouter.handlePullRequest` fires the verifier on a virtual thread after the `PR_OPEN → DEV_READY` transition so the webhook returns immediately.
    - `WorktreeDisposer` scheduled service (default every 5min) calls `WorktreeService.remove(taskId)` for terminal-status tasks (test_done/failed/cancelled) and stamps `metadata.worktree_removed_at` so re-runs no-op. Skips entirely when `orange.worktrees.base-repo` isn't configured (chat-as-executor-only setups).
    - `TaskRepository.findTerminalWithLiveWorktree()` filters by status + missing `worktree_removed_at`.
    - Tests: `PostMergeVerifierIT` × 3 (pass stamps + event, fail flags + event, blank command short-circuits). `WorktreeDisposerIT` × 2 (terminal task with real git-worktree gets removed + stamped; non-terminal task untouched; gated on `git` availability). Full suite green.
    - Refactored verifier with a `(taskId, syncFromOrigin)` overload so tests can run the build without setting up an `origin` remote.
- [x] **Periodic regression** — Status: DONE
    - After each successful build-on-main, `PostMergeVerifier.maybeRunRegression` counts `main_build_ok` events since the most recent `regression_pass`/`regression_fail` and fires `orange.post-merge.regression-command` once that count crosses `regression-every` (default 5). Pass → `regression_pass` event. Fail → `regression_fail` event + auto-enqueued `regression_periodic` triage task. Counter lives in `task_events` — no new schema.
    - Tests: extends `PostMergeVerifierIT` with a 4-build-every-2 case asserting 4 `main_build_ok` + 2 `regression_pass`.
- [x] **Triage agent + reopen FSM + post-merge triage trigger** — Status: DONE (regression path; merge-conflict triage is a follow-up)
    - `TaskStatus.allowedNext` now permits `FAILED → READY` and `TEST_DONE → READY`. `CANCELLED` stays terminal (explicit operator intent).
    - V9 migration seeds the `triage` agent (role=`triage`, read-only tools, model=claude-sonnet-4-6) with a system prompt that scopes it to inspection-plus-`orange_reopen_task`. Never edits code; the fix flows back through the regular `dev` queue with the failure log attached as augmentation.
    - `TaskService.reopenTask(id, reason, actor)` validates the FSM transition, stamps `metadata.reopen_reason = {reason, reopened_at, reopened_by, prior_status}`, and appends a `task_reopened` event. `createTriageTask(kind, relatedIds, description)` inserts a priority-10 triage task that lands READY immediately.
    - `PostMergeVerifier.recordFailure` now auto-enqueues a triage task (when `orange.post-merge.triage-on-fail=true`, default) so a chat-as-executor triage agent picks it up via the normal claim flow.
    - REST: `POST /tasks/{id}/reopen` body `{reason, actor}` → 200 / 404 / 409. MCP: `orange_reopen_task(task_id, reason, actor?)` — 18 tools total.
    - Tests: `TaskReopenIT` × 3 (reopen FAILED→READY stamps reason+event, CANCELLED refused, createTriageTask metadata shape). `PostMergeVerifierIT` extended with a fourth case asserting triage task gets enqueued on build-broken. Full suite green.
- [x] **Merge-conflict triage** — Status: DONE
    - `GithubPrService.pushBranch` now distinguishes non-fast-forward / "updates were rejected" / "fetch first" failures via a `PushConflictException`. Caught in `openPrForTask`, which enqueues a `kind=conflict` triage task with the push-output tail in the description before rethrowing so the workflow marks the stage failed.
    - `GithubPrServiceIT` extended: stand up two worktrees off a shared bare remote, push from A, then push from B → `PushConflictException` + one triage task row.
- [x] **Multi-task narration filters + env list** — Status: DONE
    - `/events/live` (and `orange_status_live`) gain a `pipeline` filter alongside the existing `role` one.
    - New `GET /health/aggregate` + `orange_health` MCP tool: one-shot summary of queue depths (ready/in_progress/pr_open/dev_ready) + env occupancy + per-role concurrency usage. The chat prints it as a single status line.
    - Security: permits `/health/**`. 19 MCP tools total.
- [x] **FSM extension: allow `failed|test_done → ready`** — Status: DONE
    - `TaskStatus.allowedNext` updated (no SQL migration needed — the V1 status CHECK constraint is value-only). Audited via `task_events.event_type='task_reopened'`.

---

## Cross-cutting / nice-to-have (not phased)
- [x] Dashboard view: live task graph + agent activity (JTE + htmx) — Status: DONE (read-only v1)
    - `DashboardController` serves JTE pages: `/` overview (queues + envs + concurrency + 24h spend), `/dashboard/tasks` list (filter by status/role), `/dashboard/tasks/{id}` detail with live event tail, `/dashboard/envs`, `/dashboard/agents` (incl. system-prompt preview).
    - htmx 2.0.3 + sse extension vendored under `src/main/resources/static/static/`. Auto-refresh every 5s via `hx-trigger="every 5s"` on overview/tasks/envs fragments.
    - Live event tail uses Server-Sent Events (`SseEmitter` on a Loom virtual thread per stream) with a `:connected` comment on connect to flush headers immediately.
    - Action buttons (cancel/reopen/replay) deferred to v2 — read-only first as agreed.
- [x] Cost tracking aggregation (`agent_runs.cost_usd` from `cost` events) — Status: DONE — `/health/aggregate` surfaces `cost.{total_usd, last_24h_usd}`; `orange_health` MCP shim prints the 24h spend + total in the one-liner.
- [x] Run replay (`agent_runs.prompt` is stored — surface a "rerun" button) — Status: DONE — `POST /tasks/{id}/replay` + `orange_replay_task` create a fresh ready task with same title/desc/role/pipeline and `metadata.replayed_from` audit pointer.
- [x] Prometheus metrics for queue depth + dev_env occupancy — Status: DONE — `MetricsConfig` registers micrometer gauges (`orange.tasks.{ready,in_progress,pr_open}`, `orange.tasks.in_progress.by_role` labelled by role, `orange.dev_envs.{total,leased,free}`) exposed via `/actuator/prometheus`. Histogram for claim latency deferred (low value compared to gauges).
- [x] **Chat-as-executor MCP surface (approach B)** — Claude Code chat sessions can now act as workers via 8 new MCP tools (`orange_claim_next_task`, `orange_heartbeat`, `orange_complete_task`, `orange_fail_task`, `orange_release_claim`, `orange_submit_plan`, `orange_dep_artifacts`, `orange_my_active_claims`) backed by a Spring `/claims/*` REST surface. orange + Temporal remain the orchestrator; the chat replaces only the subprocess executor. — Status: DONE
    - V6 migration: orange-owned `task_claims` table (`task_id PK, claim_token UNIQUE, worker_id, expires_at, cancel_requested, attempt`).
    - `ClaimService` wraps the FSM-aware claim/heartbeat/complete/fail/release transitions; `ClaimReaper` (scheduled every 30s) bounces expired leases back to `ready` (or `failed` past `orange.claims.max-attempts`).
    - `ClaimController` exposes `POST /claims/next`, `POST /claims/{token}/{heartbeat|complete|fail|release|submit-plan}`, `GET /claims/active`; `TaskController` gains `GET /tasks/{id}/dep-artifacts` for dependency-output peeking.
    - `PlannerService.submitPlan(PlanJson, architectureMd)` re-uses the validation+DAG-insert path; chat-driven planners hit it through `orange_submit_plan`.
    - User-facing tools (`orange_list_tasks`, `orange_task`, `orange_cancel`, `orange_tail`, `orange_create_task`) unchanged. Chat workers and headless workers coexist on the same `ready` queue.
    - `ClaimServiceIT` × 14 (concurrent no-double-claim, lease extension, expiry/cancel surfacing, complete persists artifacts, retryable bounce vs terminal fail, release returns to ready, reaper bounces / fails when budget burnt, active-claims filter, dep_artifacts).
- [x] **Service-side dev_env auto-release (safety net)** — Status: DONE (PR #5 merged 2026-05-19)
    - `DevEnvAutoReleaser` scheduled service (default every 1min, `orange.envs.auto-release-{enabled,delay,initial-delay}`) returns leased envs to the pool when the holder task has moved past the qa stage. Boundary set: `PR_OPEN`, `DEV_READY`, `IN_TEST`, `TEST_DONE`, `FAILED`, `CANCELLED`. Stale `IN_PROGRESS` leases are deliberately left alone — Temporal may resume the workflow; heartbeat-driven detection is a follow-up.
    - Parses the existing `task-<uuid>` holder string written by `PipelineActivitiesImpl.acquireDevEnv` — no schema change. Non-task holders (manual operator leases) are ignored. Calls `composeRunner.down()` before `DevEnvService.release()` so containers don't outlive the lease.
    - Idempotent against the workflow's own `try/finally` release: `repo.release()` filters `status=LEASED` so a double-release returns false harmlessly. JSONB event payload built via `ProtocolCodec.mapper()` (escape-safe).
    - Supporting refactor: `EnvInfo.from(DevEnv)` static factory absorbs `parsePorts`/`parseMetadata`; `acquireDevEnv` now reuses it. `DevEnvRepository.findLeased()` for the sweep iterator.
    - Tests: `DevEnvAutoReleaserIT` × 5 (pr_open release, IN_PROGRESS skip, non-task holder skip, orphan reclaim on task delete, holder parser unit). `ReadyControllerIT` × 2. Live smokes verified both happy-path (workflow's try/finally; auto-releaser silent) and orphan-path (SQL-leased env on PR_OPEN task → swept within 60s, `env_auto_released` event recorded).
- [ ] **Heartbeat-driven detection of stale IN_PROGRESS env leases** — Status: NOT_STARTED
    - The current `DevEnvAutoReleaser` deliberately skips `IN_PROGRESS` to avoid releasing an env that Temporal is about to resume into. But if the workflow truly died and Temporal isn't resuming, the env stays leased indefinitely. Close the gap by reusing `HeartbeatReaper`'s "stale `tasks.heartbeat_at`" detection: when a task is `IN_PROGRESS` past the heartbeat TTL AND holds an env, release it the same way (compose down + repo.release + `env_auto_released` event with `reason=stale_heartbeat`).
- [ ] **Dashboard v2: cancel / reopen / replay action buttons** — Status: NOT_STARTED
    - The JTE+htmx dashboard is read-only today. Add htmx-driven action buttons on `task_detail.jte` for the three existing REST endpoints: `POST /tasks/{id}/cancel`, `POST /tasks/{id}/reopen`, `POST /tasks/{id}/replay`. Auth gating lands when the rest of the dashboard gets auth — for now operator-only by network position.
