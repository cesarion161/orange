# orange v2, PR 2: runs replace Temporal — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The coordinator runs every agent itself. A task goes `ready` → implement run → coordinator push + PR → review run → merge webhook → `merged`, and its dependents become `ready`, with no Temporal, no chat claims and no pipelines.

**Architecture:**
- **Demolition first (Task 1).** Temporal, the `workflow` package, chat-as-executor claims, triage, the post-merge verifier, the concurrency gate and the dead JTE dashboard go. The build stays green; nothing executes tasks until Task 6.
- **New schema:** FSM v2 on `tasks` (V14), then `runs`, `run_events` and `settings` (V15), then `agents` and `agent_runs` dropped (V16), then the `events` inbox (V17).
- **New packages:**
  - `run`: repository, worker runner (PR 1's `AgentRunService` reshaped around `runs` rows), scheduler with per-kind slots, reaper, task reactor, post-run steps, spend sync, retention.
  - `mcp`: the worker MCP endpoint `/mcp/worker`, using the MCP Java SDK stateless servlet transport and per-run bearer tokens.
  - `events`: the inbox and its processor.
  - `roles`: role prompt files and tool policy per run kind.
- **Reshaped:**
  - `github`: the coordinator pushes, opens or updates PRs and posts `COMMENT` reviews.
  - `worktree`: workspaces are reused across a task's runs, and review runs get a detached worktree.
- **Ordering rule.** A run row stays `running` until its post-run step is done. That is what keeps the reactor from queueing a second run while a PR is being published.

**Tech Stack:** Java 27, Spring Boot 4.0.9, jOOQ, Flyway, Postgres 18 (Testcontainers), MCP Java SDK 2.1.0 (`mcp-core` + `mcp-json-jackson2`), `org.kohsuke:github-api` 1.330, Claude Code 2.1.319, fake Claude Code from PR 1.

**Spec:** `docs/superpowers/specs/2026-11-02-orange-factory-v2-design.md`: §6, §7, §8 (tools per kind), §13 (intake, processor, `pull_request` rows), §15 (`/mcp/worker`: `report_result`, `request_human`, `submit_review`), §16 (process mode), §17, §18, §19 item 2. PR 1's deferred minors assigned to PR 2 are folded in: GatewayClient timeouts and spend sync, run-dir retention, trimmed DEBUG logging, `api_retry` events.

## Global Constraints

- Keep the existing patterns: `uuidv7()` primary keys, the `touch_updated_at()` trigger, partial indexes on hot queues, `pg_notify` on state changes. Migrations are new files only: V14, V15, V16 and V17 in this PR.
- PR base branch: `orange-e2e-base`. Merge with `gh pr merge --merge`. Work in the worktree.
- **The coordinator does every push, PR, review post and comment.** Agents commit locally and never push (§17). Pushes use the host's git credentials (decision 11).
- Every PR body and review orange posts carries `<!-- orange:run=<run id> -->`. PR bodies also keep `<!-- orange-task: <task id> -->`, the router's fallback lookup.
- Agent reviews are posted as `COMMENT` reviews (decision 10). The verdict that counts is stored in orange.
- **Per-run MCP tokens** are 32 random bytes, base64url. Only `SHA-256` hex goes in `runs.mcp_token_hash`. The raw token lives only in memory, the run's MCP config file (deleted when the run ends) and the child. It is redacted like a credential.
- Subscription token and virtual-key rules from PR 1 still hold: only the `claude` child gets them.
- **Slots:** `plan` 1, `implement` 4, `review` 2, `ci_fix` 2. Global cap `max-concurrent` 6.
- **Leases:** heartbeat every 60 s, TTL 5 min.
- **Timeouts:** implement 60 min, ci_fix 40, review 20, plan 30.
- **Reactor:** sweeps every 30 s, plus on notify.
- **Events:** processed in id order; a failing event is retried up to 5 attempts.
- **Escalation:** tier up after 2 failures; `needs_human` after 3 failures.
- **Tools per kind** (§8):

  | Kind | `--tools` | `--allowedTools` | Permission mode |
  |---|---|---|---|
  | implement, ci_fix | `Read Write Edit Bash Glob Grep TodoWrite` | the same, plus `mcp__orange__report_result`, `mcp__orange__request_human` | `acceptEdits` |
  | review | `Read Glob Grep Bash` | `Read Glob Grep`, `Bash(git diff:*)`, `Bash(git log:*)`, `Bash(git show:*)`, `mcp__orange__submit_review` | `dontAsk` |
  | plan | `Read Write Glob Grep WebFetch` (unchanged until PR 4) | the same | `acceptEdits` |
- **Failure reasons:**
  - *Infra* (never counted in `failures`): `subscription_token_missing`, `auth_failed`, `plan_limit`, `gateway_unavailable`, `start_failed`, `workspace_failed`, `cancelled`, `lease_expired`, `lease_lost`, `coordinator_restart`.
  - *Model* (each one adds 1 to `failures`): `timeout`, `budget`, `error_result`, `no_result`, `exit_<n>`, `no_outcome`, `reported_failed`, `no_commits`.
- Default worktree root moves outside the repo: `${user.home}/.orange/worktrees` (decision 15).
- No `ci_state`, `task_feedback`, rework, reconciler (PR 3 in spec numbering), scope claims, epics or notes (PR 4), ntfy, `orange_answer` or steering MCP (PR 5) in this PR.

## Review Focus

1. **Coordinator restart mid-run.** Runs a previous instance left `running` must end `failed`/`coordinator_restart` (not counted), their `claude` process must die, and the task must get a fresh run instead of sitting in `running` forever. Pinned in Task 6.
2. **Duplicate and stale webhooks.** GitHub redelivers. A merge event for an already-merged task, or for a PR orange doesn't know, is a no-op, not an error retried 5 times. Pinned in Task 8.
3. **Hot loops.** Some failures recur instantly: plan limit, rejected or missing token, gateway down, missing binary, broken base repo. They must pause the scheduler, not requeue the same run. A review run that keeps failing must reach `needs_human`. Pinned in Tasks 5, 6 and 7.
4. **MCP token scope.** A token resolves to exactly one `running` run. A finished run's token, a tool of another kind, or no token writes nothing. Pinned in Task 4.
5. **Idempotent publishing.** A crash after `git push` but before `pr_number` is saved must not open a second PR. A later implement run on the same task updates the existing PR. Pinned in Task 7.

---

### Task 1: Remove Temporal and the v1 execution paths

**Files:**
- Delete (main):
  - `workflow/*`, except `EnvInfo.java`, which moves to `devenv/EnvInfo.java`;
  - `task/ClaimService`, `ClaimReaper`, `TaskClaim`, `TaskClaimRepository`;
  - `api/ClaimController`, `ActiveClaimResponse`, `ClaimNextRequest`, `ClaimResponse`, `CompleteClaimRequest`, `FailClaimRequest`, `HeartbeatResponse`, `ReleaseClaimRequest`, `SubmitPlanRequest`, `WorkerService`;
  - `concurrency/*`, `postmerge/PostMergeVerifier`, `agent/HeartbeatReaper`;
  - every `src/main/jte/*.jte`. No controller renders them, and they reference fields this PR removes. PR 7 builds the dashboard fresh. Keep the jte plugin.
- Delete (tests): `workflow/*`, `task/ClaimServiceIT`, `concurrency/ConcurrencyGateIT`, `postmerge/PostMergeVerifierIT`, `api/WorkerInstanceActivityTest`, `planner/PlannerSubmitPlanIT`, `task/TaskRepositoryConcurrencyIT` (it covers `claimNextReady`, which is removed; run-claim concurrency is covered in Task 3), and the FakeClaude scenarios `tester-pass`, `tester-fail` and `review-fail`.
- Modify:
  - `TaskService`: drop `createTriageTask`.
  - `GithubPrService`: drop the conflict triage; `PushConflictException` stays.
  - `PlannerService`: drop `savePlan`.
  - `PlanGraphService`, `PlanJson`, `DispatchService`, `DispatchController`, `DispatchRequest`: no `PipelineRegistry` and no pipeline parameter. `TaskDef.pipeline` is passed as null until Task 2 drops the column.
  - `TaskController`: cancel no longer terminates a workflow or touches claims; `dep-artifacts` is removed.
  - `StatusController`: drop `/concurrency` and the concurrency block.
  - `SecurityConfig`: drop `/claims/**`, `/concurrency`, `/dashboard/**`, `/htmx/**`.
  - `GithubEventRouter`: no `PostMergeVerifier`.
  - `build.gradle.kts`: drop the Temporal dependencies.
  - `compose.yaml`: drop `temporal` and `temporal-ui`.
  - `application.yaml`: drop `spring.temporal`, `orange.claims`, `orange.concurrency`, `orange.post-merge`, `orange.envs.acquire-timeout`, and the `tester`/`triage` routing rules.
  - `FakeClaude.register`: no `orange.workflow.auto-start`.
  - `orange-mcp/src/orange_mcp/server.py` and its README: remove `claim_next`, `heartbeat`, `complete_task`, `fail_task`, `release_claim`, `submit_plan`, `my_active_claims`, `dep_artifacts`, `concurrency`.
  - `plugin/skills/orange-orchestrate/*`: remove the chat-as-executor mode and the pipeline guidance.
  - `CLAUDE.md`: tests no longer need Temporal.
- Test: `orange/src/test/java/com/ai/orange/NoTemporalTest.java`

**Interfaces:**
- Consumes: none.
- Produces: none new. Later tasks rely on `workflow`, `concurrency` and the claim classes being gone.

- [ ] **Step 1: Write the failing test.** In `NoTemporalTest`, `temporalIsNotOnTheClasspath`: `assertThatThrownBy(() -> Class.forName("io.temporal.client.WorkflowClient")).isInstanceOf(ClassNotFoundException.class)`.
- [ ] **Step 2:** Run `./gradlew test --tests com.ai.orange.NoTemporalTest`. Expected: FAIL (the class loads).
- [ ] **Step 3: Delete and modify as listed.** `grep -rn 'temporal\|ClaimService\|PipelineRegistry\|createTriageTask\|PostMergeVerifier\|ConcurrencyGate' orange/src orange-mcp plugin` should then find only history-neutral mentions in docs.
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS. No test needs a Temporal server.
- [ ] **Step 5: Commit.** `refactor!: remove Temporal, pipelines, chat claims, triage and the post-merge verifier`

### Task 2: FSM v2 and the task model (V14)

**Files:**
- Create: `orange/src/main/resources/db/migration/V14__fsm_v2.sql`
- Modify:
  - `task/TaskStatus`, `Task`, `TaskDef`, `TaskRepository`, `TaskService`;
  - `api/TaskResponse`, `LiveTaskResponse`, `TaskController`, `StatusController`;
  - `planner/PlanGraphService`, `PlanJson`;
  - `github/GithubPrService` (writes the PR columns instead of `metadata`), `github/GithubEventRouter` (merged → `merged`);
  - `devenv/DevEnvAutoReleaser`, `config/MetricsConfig`;
  - `listener/PgListenerService` (channels `task_changed`, `dev_env_freed`).
- Test: `db/FlywayMigrationsIT` (add a v1-data case), `task/TaskStatusTest`, and the existing ITs updated (`TaskServiceCreateGraphIT`, `TaskReopenIT`, `TaskApiIT`, `GithubEventRouterIT`, `GithubPrServiceIT`, `DevEnvAutoReleaserIT`, `StatusControllerIT`).

**Interfaces:**
- Produces:
  - `enum TaskStatus { PENDING, READY, RUNNING, IN_REVIEW, MERGED, NEEDS_HUMAN, FAILED, CANCELLED }`, with db values `pending ready running in_review merged needs_human failed cancelled`. `isTerminal()` is true for MERGED, FAILED and CANCELLED. `allowedNext()`:
    - PENDING → READY, CANCELLED, FAILED
    - READY → RUNNING, CANCELLED, FAILED
    - RUNNING → IN_REVIEW, NEEDS_HUMAN, MERGED, CANCELLED, FAILED
    - IN_REVIEW → MERGED, RUNNING, NEEDS_HUMAN, CANCELLED, FAILED
    - NEEDS_HUMAN → READY, MERGED, CANCELLED, FAILED
    - FAILED → READY (operator reopen)
    - MERGED, CANCELLED → none
  - `record Task(UUID id, String title, String description, String role, TaskStatus status, int priority, int failures, String repo, String branch, Integer prNumber, String prUrl, String headSha, String reviewedSha, String reviewVerdict, JSONB metadata, OffsetDateTime createdAt, OffsetDateTime updatedAt)`
  - `record TaskDef(String key, String title, String description, String role, Integer priority, JSONB metadata)`
  - `TaskRepository.findByPullRequest(String repo, int prNumber)` reads the columns: `pr_number = ? AND (repo = ? OR repo IS NULL)`.
  - `TaskService.reopenTask`: FAILED or NEEDS_HUMAN → READY, and `failures` is reset to 0.
  - Roles are `implementer` or `chore`. `PlanGraphService` and `TaskController.create` map blank, `dev`, `tester`, `reviewer` and `triage` to `implementer`.
- **V14, pinned end state:**
  - Status mapping: `in_progress` → `ready`, `pr_open` → `in_review`, `dev_ready`, `in_test` and `test_done` → `merged`. New CHECK on the 8 statuses.
  - Every role except `chore` becomes `implementer`. Default `'implementer'`, plus `CHECK (role IN ('implementer','chore'))`.
  - Dropped: columns `pipeline`, `claimed_by`, `claimed_at`, `heartbeat_at`; the v1 status indexes and claim indexes; trigger and function `notify_task_ready`; table `task_claims`.
  - Added: `repo TEXT`, `failures INT NOT NULL DEFAULT 0`, `branch TEXT`, `pr_number INT`, `pr_url TEXT`, `head_sha TEXT`, `reviewed_sha TEXT`, `review_verdict TEXT CHECK (review_verdict IN ('approve','request_changes'))`.
  - Backfill from `metadata->>'pr_repo'`, `'pr_number'` and `'pr_url'`, with `branch = 'orange/' || id` where a PR exists.
  - Indexes: `idx_tasks_ready (priority, created_at) WHERE status='ready'`, `idx_tasks_pr (pr_number) WHERE pr_number IS NOT NULL`, `idx_tasks_active (updated_at) WHERE status IN ('ready','running','in_review','needs_human')`.
  - `promote_ready_dependents()` is rewritten so the satisfied state is `merged`; the advisory lock per child stays.
  - New `notify_task_changed()` sends `pg_notify('task_changed', NEW.id::text)` `AFTER UPDATE` when the status changed.
- `DevEnvAutoReleaser` release boundary: IN_REVIEW, MERGED, NEEDS_HUMAN, FAILED, CANCELLED. The stale-heartbeat branch goes with the column.
- `WorktreeDisposer` / `findTerminalWithLiveWorktree`: statuses `merged`, `failed`, `cancelled`.

- [ ] **Step 1: Write failing tests:**
  - `TaskStatusTest.transitions`: one assertion per row of `allowedNext()` above, plus `MERGED.isTerminal()`.
  - `FlywayMigrationsIT.v1RowsMapToFsmV2`: migrate a fresh schema to V13 with the Flyway API (`target("13")`). Insert one task per v1 status, one with `role='dev'` and `metadata {"pr_repo":"o/r","pr_number":7,"pr_url":"u"}`. Migrate to latest, then assert:
    - each status mapped as pinned;
    - the PR row has `pr_number=7`, `repo='o/r'`, `branch='orange/<id>'`;
    - `role='implementer'`;
    - `task_claims` is gone.
  - `TaskServiceCreateGraphIT.mergedParentPromotesChild`: replaces the `dev_ready` fan-in case. Child → `ready` only after both parents are `merged`.
  - `TaskReopenIT.needsHumanReopensWithFailuresReset`.
- [ ] **Step 2:** Run `./gradlew test --tests '*TaskStatusTest' --tests '*FlywayMigrationsIT' --tests '*TaskServiceCreateGraphIT' --tests '*TaskReopenIT'`. Expected: FAIL.
- [ ] **Step 3: Implement V14 and the model changes; update the listed callers and ITs.**
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(task)!: FSM v2 — running, in_review, merged, needs_human; PR columns on tasks`

### Task 3: `runs`, `run_events`, `settings` (V15); run repository; scheduler pause

**Files:**
- Create: `db/migration/V15__runs.sql`, `run/RunKind.java`, `run/RunStatus.java`, `run/Run.java`, `run/RunRepository.java`, `run/RunEventRepository.java`, `run/RunEventRow.java`, `run/SchedulerPause.java`
- Modify: `listener/PgListenerService` (add `run_queued`, `run_changed`)
- Test: `run/RunRepositoryIT.java`, `run/SchedulerPauseIT.java`

**Interfaces:**
- Produces:
  - `enum RunKind { PLAN, IMPLEMENT, REVIEW, CI_FIX }`, with db values `plan implement review ci_fix`, plus `fromDb`.
  - `enum RunStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }`.
  - `record Run(UUID id, UUID taskId, RunKind kind, RunStatus status, String role, int priority, int attempt, JSONB input, JSONB outcome, String workerId, OffsetDateTime leaseExpiresAt, Long pid, String tier, String model, String effort, String auth, String gatewayKeyAlias, BigDecimal costUsd, String error, OffsetDateTime queuedAt, OffsetDateTime startedAt, OffsetDateTime finishedAt)`
  - `RunRepository`:
    - `Optional<Run> queue(UUID taskId, RunKind kind, String role, int priority, JSONB input)`: empty when the task already has a queued or running run. `attempt` = 1 + the task's runs of that kind.
    - `Run insertRunning(RunKind kind, String role, UUID taskIdOrNull, String workerId, Duration leaseTtl, JSONB input)`
    - `Optional<Run> claimNext(RunKind kind, String workerId, Duration leaseTtl)`: `FOR UPDATE SKIP LOCKED`, ordered by `priority, queued_at`. It sets `running`, `worker_id`, `lease_expires_at` and `started_at`.
    - `void recordRoute(UUID id, String tier, String model, String effort, String harness, String auth, BigDecimal budgetUsd, String gatewayKeyAlias, String mcpTokenHash, String logPath)`
    - `void recordPid(UUID id, long pid)`
    - `boolean heartbeat(UUID id, String workerId, Duration leaseTtl)`: only while `running` and owned by the worker.
    - `boolean recordOutcome(UUID id, JSONB outcome)`: only while `running`; the last call wins.
    - `boolean finish(UUID id, RunStatus status, String error, BigDecimal costUsd, long tokensIn, long tokensOut, long cacheReadTokens, String sessionId)`: only from `running`.
    - `int cancelQueued(UUID taskId)`
    - Lookups: `Optional<Run> findById(UUID)`, `List<Run> findByTask(UUID)`, `Optional<Run> findActiveByTask(UUID)`, `Optional<Run> findRunningByTokenHash(String)`, `List<Run> findExpiredLeases(OffsetDateTime now)`, `List<Run> findRunningNotOwnedBy(String workerId)`, `int countByStatus(RunKind, RunStatus)`.
  - `RunEventRepository`: `void append(UUID runId, int seq, String type, JSONB payload)`, `List<RunEventRow> findByRunSince(UUID runId, int afterSeq, int limit)` (limit clamped to 500).
  - `SchedulerPause` (settings key `paused`; value `{reason, until?, since}`):
    - `Optional<Pause> current()`: a timed pause past its `until` is cleared and reported empty.
    - `void pause(String reason, OffsetDateTime untilOrNull)`: an existing open-ended pause is never replaced by a timed one; otherwise the later `until` wins.
    - `void resume()`.
    - `record Pause(String reason, OffsetDateTime until, OffsetDateTime since)`.
- **V15, pinned:**
  - `runs` has the §6 columns, minus `epic_id` (PR 4). It adds:
    - `priority INT NOT NULL DEFAULT 100`;
    - `auth TEXT CHECK (auth IN ('subscription','gateway'))`;
    - `pid BIGINT`;
    - `gateway_key_deleted_at TIMESTAMPTZ`;
    - `updated_at` with `touch_updated_at`.
  - Defaults: `cost_usd NUMERIC(10,4) NOT NULL DEFAULT 0`; token counters `BIGINT NOT NULL DEFAULT 0`.
  - `mcp_token_hash` is `UNIQUE`.
  - Indexes:
    - `CREATE UNIQUE INDEX idx_runs_one_active_per_task ON runs (task_id) WHERE task_id IS NOT NULL AND status IN ('queued','running')`
    - `idx_runs_queue (kind, priority, queued_at) WHERE status='queued'`
    - `idx_runs_leases (lease_expires_at) WHERE status='running'`
    - `idx_runs_task (task_id, queued_at)`
  - Triggers: `AFTER INSERT` → `pg_notify('run_queued', NEW.kind)`; `AFTER UPDATE` with a status change → `pg_notify('run_changed', coalesce(NEW.task_id::text, ''))`.
  - `run_events (id BIGSERIAL PK, run_id UUID NOT NULL REFERENCES runs ON DELETE CASCADE, seq INT NOT NULL, type TEXT NOT NULL, payload JSONB NOT NULL DEFAULT '{}', created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE (run_id, seq))`.
  - `settings (key TEXT PRIMARY KEY, value JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`.

- [ ] **Step 1: Write failing tests:**
  - `RunRepositoryIT`:
    - `oneActiveRunPerTask`: queue implement → present; queue review on the same task → empty; finish the first → the review queues. `attempt` counts per kind.
    - `concurrentClaimsNeverShareARun`: 20 queued, 8 threads claim until empty → 20 distinct ids.
    - `claimOrdersByPriorityThenQueueTime`.
    - `heartbeatOnlyExtendsTheOwnersLease`.
    - `outcomeIsOnlyRecordedWhileRunning`.
    - `expiredLeasesAndForeignRunsAreFound`.
    - `planRunsHaveNoTask`: via `insertRunning`.
  - `SchedulerPauseIT`: `timedPauseExpires`, `openEndedPauseSurvivesATimedOne`, `resumeClears`.
- [ ] **Step 2:** Run the two classes. Expected: FAIL (types missing).
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run the two classes, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(run): runs, run_events and settings tables; run repository with leases; scheduler pause`

### Task 4: Worker MCP endpoint (`/mcp/worker`)

**Files:**
- Modify: `build.gradle.kts`: add `implementation("io.modelcontextprotocol.sdk:mcp-core:2.1.0")` and `implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:2.1.0")`.
- Create:
  - `mcp/RunTokens.java`;
  - `run/RunOutcome.java`, `github/ReviewComment.java`;
  - `mcp/WorkerTokenFilter.java`, `mcp/WorkerTools.java`, `mcp/WorkerMcpConfig.java`;
  - `mcp/WorkerMcpUrl.java`, `mcp/WorkerMcpConfigWriter.java`.
- Modify: `config/SecurityConfig` (permit `/mcp/worker`; the token filter authenticates).
- Modify: `src/test/resources/fake-claude/FakeClaude.java`: an MCP client, and default behaviour picked from `--allowedTools` (below).
- Test: `mcp/WorkerMcpIT.java` (`webEnvironment = RANDOM_PORT`), `mcp/McpTestClient.java` (test util), `run/RunOutcomeTest.java`, `harness/claude/WorkerMcpRealClaudeLiveIT.java` (`@Tag("live")`)

**Interfaces:**
- Consumes: `RunRepository.findRunningByTokenHash`, `recordOutcome`, `insertRunning` (tests).
- Produces:
  - `RunTokens`: `static String newToken()`, `static String hash(String token)` (lowercase SHA-256 hex).
  - `record ReviewComment(String path, Integer line, String body)` (`line` may be null).
  - `sealed interface RunOutcome`, with `record Result(String status /* done|blocked|failed */, String summary, String question) implements RunOutcome` and `record Review(String verdict /* approve|request_changes */, String summary, List<ReviewComment> comments) implements RunOutcome`. Methods: `JSONB toJsonb()` (with `"type":"result"|"review"`), `static Optional<RunOutcome> parse(JSONB)`.
  - `WorkerMcpUrl.url()`: `orange.mcp.worker-url` if set, else `http://127.0.0.1:<port>/mcp/worker`, with the port from `WebServerInitializedEvent`.
  - `WorkerMcpConfigWriter.write(Path runDir, String token) : Path` writes `<runDir>/mcp.json` containing `{"mcpServers":{"orange":{"type":"http","url":"<url>","headers":{"Authorization":"Bearer <token>"}}}}`.
  - Tool names: `report_result`, `request_human`, `submit_review`. The server is named `orange`, so Claude Code sees them as `mcp__orange__…`.
- **Behaviour (pinned):**
  - **`WorkerTokenFilter`** is a `FilterRegistrationBean` on `/mcp/worker`.
    - No `Bearer` token, or no `running` run with that hash → HTTP 401 `{"error":"invalid or inactive run token"}`.
    - Otherwise it puts the `Run` in a request attribute. The transport's `contextExtractor` copies it into `McpTransportContext` under `"run"`.
  - **Server.** `McpServer.sync(HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp/worker")…)`, registered with a `ServletRegistrationBean` on `/mcp/worker`. A tool filter shows each kind only its tools:
    - IMPLEMENT and CI_FIX: `report_result`, `request_human`;
    - REVIEW: `submit_review`;
    - PLAN: `request_human`.
  - **Handlers** return `CallToolResult` with `isError=true`, never throw, and write nothing when:
    - the tool is not allowed for the run's kind;
    - arguments are invalid (enums, a blank `summary`, a comment without `path` or `body`);
    - `recordOutcome` returns false (the run stopped).
  - **On success** the handler stores the `RunOutcome` and returns `"recorded"`:
    - `report_result {status, summary}` → `Result(status, summary, null)`;
    - `request_human {question}` → `Result("blocked", question, question)`;
    - `submit_review {verdict, summary, comments}` → `Review`.
  - **FakeClaude** reads `--mcp-config` and `--allowedTools` (variadic). It speaks MCP over HTTP the way Claude Code does: POST JSON-RPC `initialize`, then `notifications/initialized`, then `tools/call`, with `Accept: application/json, text/event-stream` and the config's headers. It reads JSON or SSE `data:` responses and appends every reply to `$CLAUDE_CONFIG_DIR/fake-mcp.log`.
  - **FakeClaude default behaviour, when no marker overrides it:**
    - **Allowed `mcp__orange__report_result`:**
      1. Append a line to `orange-fake.txt` in its cwd.
      2. `git add`, then `git commit -m "fake: <first prompt line>"`.
      3. Call `report_result(done, "Implemented: <first prompt line>")`.
    - **Allowed `mcp__orange__submit_review`:** call `submit_review(approve, "Looks good", [])`.
  - **FakeClaude markers:** `[[fake:no-commit]]`, `[[fake:report-failed]]`, `[[fake:blocked]]`, `[[fake:no-outcome]]`, `[[fake:request-changes]]` (one inline comment on `README.md` line 1 plus one without a line), `[[fake:wrong-tool]]` (an implement run calls `submit_review`).

- [ ] **Step 1: Write failing tests:**
  - `RunOutcomeTest`: JSON round-trip for both records; malformed JSONB → empty.
  - `WorkerMcpIT`. Runs are inserted with `insertRunning` and a hashed token; `McpTestClient` speaks JSON-RPC over HTTP.
    - `missingOrUnknownTokenIs401`.
    - `finishedRunTokenIs401`.
    - `implementRunListsOnlyItsTools`.
    - `reviewRunListsOnlySubmitReview`.
    - `reportResultRecordsTheOutcome`.
    - `invalidVerdictIsAnErrorAndRecordsNothing`.
    - `implementTokenCannotSubmitReview`.
    - `aTokenOnlyWritesItsOwnRun`: two runs; A's call leaves B's outcome null.
    - `rawTokenIsNeverStored`: the run row cast to text doesn't contain the token, and `mcp_token_hash = RunTokens.hash(token)`.
  - `WorkerMcpRealClaudeLiveIT`. Skipped unless `claude` is on PATH; tagged live; no secret; no model call.
    - Boots the app on a random port and inserts a running implement run.
    - Writes its MCP config and runs the real `claude` through `ClaudeCodeAdapter` with an invalid OAuth token, `--mcp-config`, and the implement tool policy.
    - Asserts the init event shows `mcp_servers` containing `{"name":"orange","status":"connected"}`, and that `tools` includes `mcp__orange__report_result` but not `mcp__orange__submit_review`.
- [ ] **Step 2:** Run `./gradlew test --tests '*RunOutcomeTest' --tests '*WorkerMcpIT'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Confirm the SDK calls against the 2.1.0 jar; the signatures were checked while planning:
  - `HttpServletStatelessServerTransport.Builder.messageEndpoint/contextExtractor/jsonMapper`;
  - `McpServer.sync(McpStatelessServerTransport)` → `StatelessSyncSpecification.tools(...)/addToolFilter(...)`;
  - `SyncToolSpecification.builder().tool(...).callHandler((McpTransportContext, CallToolRequest) -> CallToolResult)`.
- [ ] **Step 4:** Run the unit and IT classes, then `./gradlew liveTest --tests '*WorkerMcpRealClaudeLiveIT'`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(mcp): worker MCP endpoint with per-run tokens — report_result, request_human, submit_review`

### Task 5: Worker runner on `runs`; role prompts and tool policy; drop `agents`/`agent_runs` (V16)

**Files:**
- Move: `agent/AgentRunService.java` → `run/WorkerRunner.java` (use `git mv` to keep history). Replace `AgentRunRequest`/`AgentRunResult` with `run/RunResult.java`, `run/PreparedRun.java` and `run/RunPreparer.java`.
- Create:
  - `run/RunsProperties.java` (`orange.runs`);
  - `run/FailureReasons.java`, `run/PostRunSteps.java`;
  - `roles/RolePrompts.java`, `roles/ToolPolicy.java`;
  - `src/main/resources/roles/implementer.md`, `reviewer.md`, `planner.md`;
  - `db/migration/V16__drop_agents.sql`.
- Modify:
  - `harness/RunEvent` (add `ApiRetry`), `harness/claude/StreamJsonParser` (parse `system/api_retry`);
  - `harness/RunHandle` (add `long pid()`), `harness/ProcessRunHandle`;
  - `harness/RunSpec` (add `List<String> redact`, extra secrets to mask), `harness/claude/ClaudeCodeAdapter` (redact them);
  - `billing/PlanLimit` (add `resetsAt`);
  - `planner/PlannerService` (uses `runDirect`), `api/StatusController` (cost from `runs`), `task/TaskRepository` (add `incrementFailures`), `application.yaml`;
  - `worktree/WorktreeService`: add `Path ensure(UUID taskId)`, which creates the task's worktree if it is missing and otherwise returns the existing path, so later runs keep earlier commits.
- Delete: `agent/*` (Agent, AgentRepository, AgentRun, AgentRunRepository, AgentRunStatus, AgentRunRequest, AgentRunResult), `db/ReviewerVerdictBindingMigrationIT`.
- Test: `run/WorkerRunnerIT.java` (from `AgentRunServiceIT`, `RANDOM_PORT`), `run/WorkerRunnerNoTokenIT.java`, `run/WorkerRunnerGatewayIT.java`, `roles/ToolPolicyTest.java`, `roles/RolePromptsTest.java`, `billing/PlanLimitTest` (extend), `harness/claude/StreamJsonParserTest` (extend), `planner/PlannerServiceIT` (update)

**Interfaces:**
- Consumes: Task 3 repositories and `SchedulerPause`; Task 4 `RunTokens`, `WorkerMcpConfigWriter`, `RunOutcome`; PR 1 routing, harness, gateway and billing.
- Produces:
  - `WorkerRunner`:
    - `RunResult execute(Run run)`: for a run already claimed as `running`;
    - `RunResult runDirect(RunKind kind, String role, Path cwd, String prompt, Duration timeout)`: inserts a running row with no task (planner) and executes it;
    - `boolean cancel(UUID taskId)`;
    - `Set<UUID> activeRunIds()`.
  - `record RunResult(UUID runId, RunStatus status, String reason /* null on success */, RunOutcome outcome /* nullable */, String lastAssistantText, BigDecimal costUsd)`.
  - `record PreparedRun(Path cwd, String prompt, String role, String difficulty, boolean risk, int failures, Runnable cleanup)`; `RunPreparer.prepare(Run run) : PreparedRun` handles IMPLEMENT here; Task 7 adds REVIEW.
  - `PostRunSteps.after(Run run, RunResult result) : RunResult` returns the final result. Task 7 adds publishing and reviews.
  - `ToolPolicy.forKind(RunKind) : record ToolPolicy(List<String> tools, List<String> allowedTools, String permissionMode, boolean mcp)`, with the values in Global Constraints.
  - `RolePrompts.systemPrompt(String role)`: `implementer` and `chore` → `roles/implementer.md`; `reviewer` and `planner` → their own file.
  - `FailureReasons`: `boolean isInfra(String reason)` (the Global Constraints list); `Optional<Pause> pauseFor(String reason, String resultText, ZonedDateTime now)`:
    - `plan_limit` → until `PlanLimit.resetsAt` or now + 1 h;
    - `auth_failed`, `subscription_token_missing`, `start_failed`, `workspace_failed` → open-ended;
    - `gateway_unavailable` → now + 5 min.
  - `PlanLimit.resetsAt(String text, ZonedDateTime now) : Optional<ZonedDateTime>` parses `resets 5pm (Europe/Berlin)`, `reset at 3am`, `resets 5:30pm (UTC)` and returns the next occurrence after `now`, in the named zone or else `now`'s zone.
  - `RunEvent.ApiRetry(int attempt, int maxRetries, long retryDelayMs, Integer errorStatus, String error)`.
  - `TaskRepository.incrementFailures(UUID id)`.
  - `RunsProperties`: `loopEnabled` (true), `workerId` (blank → `<hostname>-<8 hex>`), `maxConcurrent` 6, `slots` (map by db value), `timeouts` (map by db value), `leaseTtl` 5m, `heartbeat` 60s, `schedulerPoll` 5s, `reactorSweep` 30s, `reaperSweep` 30s, `retention` 14d.
- **Behaviour (pinned), in order for one run:**
  1. **Prepare.** `RunPreparer.prepare`:
     - IMPLEMENT: `WorktreeService.ensure(taskId)`.
     - Prompt: the task title and description, then `## Previous attempts` listing each failed run of the task (attempt, reason, outcome summary or last assistant text, at most 2,000 characters).
     - Failure → `workspace_failed`.
  2. **Route.** Role from the run, difficulty and risk from task metadata (default `M` and false), `failures` from the task row. `recordRoute(...)` is written before start.
  3. **Credentials.** Unchanged from PR 1.
  4. **MCP.** When `ToolPolicy.mcp`: generate a token, store its hash, write `mcp.json` in `<run-root>/<runId>/` and pass it as `--mcp-config`. The token is added to `RunSpec.redact`.
  5. **Start and watch.** Call `recordPid`. `run_events` get `seq` 1, 2, …:

     | Event | `type` | Payload |
     |---|---|---|
     | Init | `init` | `{session_id, model}` |
     | AssistantText | `assistant` | `{text}` |
     | ToolUse | `tool_use` | `{id, tool, input}` |
     | ToolResult | `tool_result` | `{id, is_error, excerpt}` |
     | Result | `result` | `{is_error, subtype, num_turns, cost_usd}` |
     | ApiRetry | `api_retry` | `{attempt, max_retries, retry_delay_ms, error_status, error}` |

     - Stderr and Unparsed lines are logged at DEBUG, truncated to 200 characters, and not persisted.
     - The budget watch is as in PR 1.
     - The lease heartbeats every `heartbeat`. If `heartbeat()` returns false, kill with `lease_lost`.
     - Kill on timeout, using the per-kind timeout.
  6. **Exit.** Re-read the run.
     - **No longer `running`** (the reaper took it): skip post-run and finish, and only log.
     - **Still `running`:** `reason` = PR 1's `failureReason`, but on a clean exit:
       - no outcome when the kind requires one (IMPLEMENT, CI_FIX, REVIEW) → `no_outcome`;
       - outcome `Result(failed)` → `reported_failed`.
  7. **Post-run.** `result = postRunSteps.after(run, result)`. This happens **before** the run row is finished. In this task:
     - **Task runs.** A model failure → `incrementFailures`. Outcome `blocked` → task → NEEDS_HUMAN, with a task_event `needs_human {run_id, question}`.
     - **Infra failure.** `SchedulerPause.pause(...)` from `FailureReasons.pauseFor`.
  8. **Finish.**
     - `finish(...)` with tokens, cost (gateway spend, result cost or estimate, as in PR 1) and session id.
     - task_events for task runs: `run_started {run_id, kind, tier, model, effort}` at start; `run_finished {run_id, kind, status, reason?, cost_usd}` at the end.
     - Block the gateway key in a `finally`, delete `mcp.json`, and run `PreparedRun.cleanup`.
- **V16:** `DROP TABLE agent_runs; DROP TABLE agents;`.
- **Role prompts:**
  - `implementer.md` is based on V5's dev prompt. It works only in this repo, commits but never pushes, and finishes by calling `report_result` (`done` with a PR-ready summary, or `failed` with why), or `request_human` for a decision.
  - `reviewer.md` is based on V10 plus V13. It covers correctness, conventions, tests and risk; uses `git diff`/`git log`/`git show` read-only; and calls `submit_review` exactly once: `approve` when there are no blocking findings, inline comments with `path` and `line`.
  - `planner.md` is V7's planner minus `pipeline`/`overrides`, with roles `implementer|chore`.
- `PlannerService.plan` calls `runDirect(PLAN, "planner", outputDir, description, timeouts.plan)`. Its result handling is unchanged.

- [ ] **Step 1: Write failing tests.** Each IT creates tasks with `TaskService.createGraph` and runs `insertRunning` + `execute`, with the scheduler and reactor loops off (`orange.runs.loop-enabled=false`).
  - `WorkerRunnerIT`:
    - `implementRunRecordsTheMcpOutcome`: the fake's default → SUCCEEDED, outcome `Result(done)`, `run_events` with `init`, `assistant` and `result`, `mcp.json` deleted.
    - `noOutcomeIsAModelFailure`: `[[fake:no-outcome]]` → FAILED/`no_outcome`, task `failures` = 1.
    - `reportedFailureCounts`: `[[fake:report-failed]]` → `reported_failed`, failures 1.
    - `blockedMovesTheTaskToNeedsHuman`.
    - `budgetKill`.
    - `timeoutKill`: implement timeout set to 3 s.
    - `planLimitPausesUntilReset`: the fake's plan-limit text → paused until 17:00 in Europe/Berlin; failures 0.
    - `rejectedTokenPausesOpenEnded`: `[[fake:auth-failed]]`.
    - `leaseIsHeartbeated`: heartbeat 1 s → `lease_expires_at` moves forward during `[[fake:hang]]`.
    - `apiRetryIsPersisted`: the `auth-failed` scenario emits two retries → two `api_retry` events.
    - `retryReusesTheWorktree`: the first run fails with `[[fake:report-failed]]` after committing; the second run starts in the same worktree and sees the commit (`WorktreeService.ensure`).
    - `credentialsAndRunTokenAreRedacted`: `[[fake:leak-env]]`, extended to print the MCP header → no token or credential in `run_events`, `stream.jsonl` or `stderr.log`.
  - `WorkerRunnerNoTokenIT.missingTokenFailsFastAndPauses`.
  - `WorkerRunnerGatewayIT`: PR 1's mint/block cases on `runs`.
  - `ToolPolicyTest`: one assertion per row of the tools table.
  - `RolePromptsTest`: the `chore` and `implementer` files are the same; a missing role throws.
  - `PlanLimitTest.resetsAt`: the three phrasings, plus the case where the time has already passed today → tomorrow.
  - `StreamJsonParserTest.apiRetry`: PR 1's captured line from 2.1.319.
  - `PlannerServiceIT`: the planner run is a `runs` row of kind `plan`, SUCCEEDED, with no task.
- [ ] **Step 2:** Run the listed classes. Expected: FAIL.
- [ ] **Step 3: Implement**, deleting `agent/*` and its ITs.
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(run)!: worker runner on runs — MCP outcomes, leases, per-kind tools and timeouts, pause on infra failures; role prompts as files`

### Task 6: Scheduler, reaper, task reactor

**Files:**
- Create: `run/RunScheduler.java`, `run/RunReaper.java`, `run/TaskReactor.java`, `run/ReactorDecision.java`, `api/SchedulerController.java`
- Modify: `task/TaskRepository` (add `Optional<Task> lockForUpdate(UUID id)` and `List<UUID> findActiveIds()` for ready/running/in_review), `config/SecurityConfig` (permit `/scheduler/**`), `harness/FakeClaude.register` (sets `orange.runs.loop-enabled=false`; the e2e test turns it on)
- Test: `run/ReactorDecisionTest.java`, `run/TaskReactorIT.java`, `run/RunSchedulerIT.java`, `run/RunReaperIT.java`, `api/SchedulerControllerIT.java`

**Interfaces:**
- Consumes: Tasks 3 and 5.
- Produces:
  - `ReactorDecision.decide(TaskSnapshot s, int needsHumanAfter) : Action`
    - `record TaskSnapshot(TaskStatus status, int failures, boolean hasActiveRun, boolean paused, boolean workspaceAvailable, String headSha, String reviewedSha)`
    - `sealed interface Action` with `QueueImplement`, `QueueReview(String headSha)`, `NeedsHuman(String reason)`, `None`.
  - `TaskReactor`: `void react(UUID taskId)`, `void sweep()`.
  - `RunScheduler`: `void fillSlots()`.
  - `RunReaper`: `void reapExpired()`, `void failOrphans()`.
  - REST:
    - `GET /scheduler` → `{paused: {reason, until, since} | null, runs: {<kind>: {queued, running, slots}}, max_concurrent}`
    - `POST /scheduler/pause` with body `{reason?, minutes?}`
    - `POST /scheduler/resume`
- **Reactor rules (pinned order; first match wins):**
  1. The task is terminal or `pending`, or `hasActiveRun` → None.
  2. `paused` → None.
  3. `failures ≥ needsHumanAfter` and status RUNNING or IN_REVIEW → `NeedsHuman("failures")`.
  4. READY and `workspaceAvailable` → QueueImplement (READY → RUNNING). A ready task with no worktree slot stays READY.
  5. RUNNING (the last code-changing run failed) → QueueImplement. This retry rule is implied by §7 failure counting and escalation.
  6. IN_REVIEW, `headSha != null` and `!headSha.equals(reviewedSha)` → `QueueReview(headSha)`.
  7. Otherwise None.
- **`react(taskId)`** runs in a transaction:
  1. `lockForUpdate`, build the snapshot (`workspaceAvailable` = the task has a worktree or `WorktreeService.hasCapacity()`; Task 7 adds that method, so use `true` here until then), and decide.
  2. Queue with `priority` = the task's priority and role = the task's role (implement) or `reviewer` (input `{"head_sha": …}`).
  3. An empty `queue` result → None.
  4. task_event `run_queued {run_id, kind}` or `needs_human {reason}`.
- **Wake-ups:**
  - The reactor runs on `task_changed` and `run_changed` (payload task id; a blank payload is ignored) and every `reactorSweep`.
  - The scheduler runs on `run_queued` and every `schedulerPoll`.
  - The reaper sweeps every `reaperSweep`; `failOrphans` runs on `ApplicationReadyEvent`.
  - All of these only run when `loopEnabled`.
- **Scheduler.** `fillSlots` is synchronized and does nothing while paused. Per kind, while `inFlight(kind) < slots(kind)` and the total is below `maxConcurrent`: `claimNext(kind, workerId, leaseTtl)`, then `WorkerRunner.execute(run)` on a virtual thread. In-flight counts are this instance's, decremented when `execute` returns or throws. A throw marks the run FAILED with `start_failed` if it is still running. Plan runs started with `runDirect` don't take a slot.
- **Reaper.**
  - `reapExpired`: each expired lease → `finish(FAILED, "lease_expired")`; failures unchanged.
  - `failOrphans`: each `running` run with a different `worker_id` → kill its `pid` if the process is alive and its start instant is within [started_at − 5 s, started_at + 60 s]. Then `finish(FAILED, "coordinator_restart")`, and remove its review worktree if it has one (Task 7).

- [ ] **Step 1: Write failing tests:**
  - `ReactorDecisionTest`: one test per rule, plus precedence: active run beats everything; paused beats queueing; failures beat the retry; review needs a non-null head.
  - `TaskReactorIT`:
    - `readyTaskGetsAnImplementRun`: queued run, task RUNNING, `run_queued` event.
    - `concurrentReactsQueueExactlyOneRun`: 8 threads → 1 run.
    - `runningTaskWithoutRunIsRetried`.
    - `inReviewWithNewHeadGetsAReview`: input `head_sha`.
    - `thirdFailureMovesToNeedsHuman`.
    - `pausedQueuesNothing`.
  - `RunSchedulerIT` (`@MockitoBean WorkerRunner` blocking on a latch; `slots.implement=2`):
    - `slotsBoundConcurrency`: 5 queued → 2 running, the rest queued; release → all 5 eventually run.
    - `pausedStartsNothing`.
  - `RunReaperIT`:
    - `expiredLeaseFailsWithoutCountingFailures`.
    - `orphansOfAPreviousInstanceAreKilledAndFailed`: a live `sleep 600` started just before the run's `started_at` → dead after `failOrphans`; run FAILED/`coordinator_restart`; then `TaskReactor.react` queues a fresh implement run.
  - `SchedulerControllerIT`: pause, then GET shows the reason, then resume clears it.
- [ ] **Step 2:** Run the listed classes. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(run): scheduler with per-kind slots, lease reaper and task reactor`

### Task 7: Coordinator push and PR after implement; review runs and `COMMENT` reviews

**Files:**
- Create: `worktree/Git.java` (one place to run git with a timeout; `GitException` carries exit code and output), `github/PrPublisher.java` (replaces `GithubPrService`; its IT is reworked), `github/PublishException.java`
- Modify:
  - `github/GithubClient`, `KohsukeGithubClient`;
  - `worktree/WorktreeService`, `WorktreeProperties` (default root `${user.home}/.orange/worktrees`);
  - `run/RunPreparer` (REVIEW), `run/PostRunSteps`, `run/RunReaper` (review worktree cleanup), `run/TaskReactor` (`hasCapacity`);
  - `task/TaskRepository` (`recordPublished`, `recordReview`).
- Test: `github/PrPublisherIT.java`, `run/ImplementFlowIT.java`, `run/ReviewFlowIT.java`, `worktree/WorktreeServiceIT` (extend)

**Interfaces:**
- Consumes: Tasks 2, 3, 5 and 6.
- Produces:
  - `GithubClient` adds:
    - `Optional<PrInfo> findOpenPullRequest(String headBranch)`: kohsuke `queryPullRequests().head(owner + ":" + branch).state(OPEN)`;
    - `PrInfo updatePullRequest(int number, String title, String body)`;
    - `void createReview(int number, String commitSha, String body, List<ReviewComment> comments)`: `createReview().commitId(sha).body(body).event(COMMENT)`, with `singleLineComment(body, path, line)` for each comment that has a line.
  - `WorktreeService` adds:
    - `boolean hasCapacity()`: only top-level task dirs count toward `max-count`.
    - `Path createReviewWorktree(UUID runId, String sha)`: `git fetch origin` best effort, then `git worktree add --detach <root>/review/<runId> <sha>`.
    - `void removeReviewWorktree(UUID runId)`.
    - `String baseRef()`: `origin/<base>` when an `origin` remote exists, else `<base>`.
    - `gcStale` skips `review/`.
  - `PrPublisher.publish(Task task, Run run, String summary) : PublishResult`, with `record PublishResult(int prNumber, String prUrl, String headSha, boolean created)`. It throws `PublishException(String reason)`, where reason is `no_commits`, `push_rejected` or `github_failed`.
  - `TaskRepository` adds `recordPublished(UUID id, String repo, String branch, int prNumber, String prUrl, String headSha)` and `recordReview(UUID id, String sha, String verdict)`.
- **Behaviour (pinned):**
  - **Publish:**
    1. `commitsAhead(worktree, baseRef)` = 0 → `no_commits`.
    2. `git push --force-with-lease -u origin orange/<taskId>`; a rejection → `push_rejected`.
    3. Find the PR: `task.prNumber`, or else `findOpenPullRequest(branch)`. Update it if found; otherwise open one with title = the task title.
    4. The body:

       ```
       <summary>

       ## Task
       <title>

       <description>

       ---
       Cost so far: $<sum of runs.cost_usd for the task, 2 dp> · run <run id>
       <!-- orange-task: <task id> -->
       <!-- orange:run=<run id> -->
       ```
       The acceptance checklist, scope and out-of-scope sections from §7 join the body in PR 4, with the columns they come from.
    5. `recordPublished` with `repo = orange.github.repo`, then RUNNING → IN_REVIEW, and task_event `pr_published {run_id, pr_number, pr_url, head_sha, created}`.
    6. GitHub not configured, or an API error → `github_failed`.
  - **`PostRunSteps` additions.** Effects apply only if the task is still non-terminal and in the state the run expects.
    - **IMPLEMENT, outcome `done`:** publish.
      - `no_commits` → result FAILED/`no_commits` and `incrementFailures`.
      - `push_rejected` or `github_failed` → task NEEDS_HUMAN with task_event `needs_human {reason}`. The run still SUCCEEDED, because the model's work is done.
    - **REVIEW, outcome `Review`:**
      1. `recordReview(task, run.input.head_sha, verdict)`.
      2. `createReview(prNumber, head_sha, body, inline comments)`. The body is `**orange review: <verdict>**`, the summary, comments without a line as a `- path: body` list, then the run marker. A GitHub error is logged and recorded as task_event `review_post_failed`; the verdict stays stored.
      3. `request_changes` → `incrementFailures`.
      4. task_event `review_submitted {run_id, verdict, head_sha, comments}`.
    - **REVIEW model failure** → `incrementFailures`. This is a ruling: otherwise a reviewer that keeps failing requeues forever.
  - **Review workspace.** `RunPreparer` for REVIEW: `createReviewWorktree(run.id, input.head_sha)`; cleanup removes it.
  - **Review prompt:** `# Review: <title> (PR #<n>)`, the description, then "The change is `git diff <baseRef>...HEAD` (head <sha>); see also `git log <baseRef>..HEAD`."

- [ ] **Step 1: Write failing tests.** Use a bare origin remote and a `@Primary` stub `GithubClient` that keeps PRs and reviews in memory, as in today's `GithubPrServiceIT`.
  - `PrPublisherIT`:
    - `firstPublishPushesAndOpensAPr`: the branch is on the remote at `headSha`, and the body has both markers and the cost line.
    - `secondPublishUpdatesTheSamePr`: a new commit → no new PR, and `head_sha` is updated.
    - `crashAfterPushDoesNotOpenASecondPr`: `prNumber` is null but the stub already has an open PR for the branch → it is updated, `created=false`.
    - `noCommitsIsRejected`.
    - `pushRejectedWhenTheRemoteBranchMoved`.
  - `ImplementFlowIT` (`RANDOM_PORT`, real `WorkerRunner` and fake):
    - `doneOutcomePublishesAndMovesToInReview`.
    - `noCommitFailsAndCounts`: `[[fake:no-commit]]` → failures 1, task stays RUNNING.
    - `githubDownMovesToNeedsHuman`: the stub throws.
  - `ReviewFlowIT`:
    - `approveStoresTheVerdictAndPostsACommentReview`: `reviewed_sha` = head, the stub review has event COMMENT, commit = head and the run marker.
    - `requestChangesCountsAndPostsInlineComments`: `README.md:1` is inline and the line-less comment is in the body.
    - `reviewWorktreeIsRemoved`.
  - `WorktreeServiceIT`: `reviewWorktreesDoNotCountTowardTheCap`, `baseRefPrefersOrigin`.
- [ ] **Step 2:** Run the listed classes. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(github): coordinator push and PR after implement runs; review runs post COMMENT reviews`

### Task 8: Events inbox; merge, close and head updates through events (V17)

**Files:**
- Create: `db/migration/V17__events.sql`, `events/InboxEvent.java`, `events/EventRepository.java`, `events/EventProcessor.java`, `github/GithubEventHandler.java`
- Modify: `webhook/GithubWebhookController` (store and return 200), `listener/PgListenerService` (add `event_received`), `task/TaskRepository` (`setHeadSha`), `application.yaml` (`orange.events`)
- Delete: `github/GithubEventRouter`, `GithubEventRouterIT`
- Test: `events/EventProcessorIT.java`, `webhook/GithubWebhookControllerIT.java`

**Interfaces:**
- Produces:
  - `record InboxEvent(long id, String source, String type, String repo, String deliveryId, JSONB payload, int attempts)`
  - `EventRepository`:
    - `boolean insert(String source, String type, String repo, String deliveryId, JSONB payload)`: false on a duplicate `delivery_id`.
    - `Optional<InboxEvent> lockNext(int maxAttempts)`: `processed_at IS NULL AND attempts < maxAttempts`, ordered by id, `FOR UPDATE SKIP LOCKED`, inside the caller's transaction.
    - `void markProcessed(long id)`.
    - `void markFailed(long id, String error)`: attempts + 1, `last_error`; runs in its own transaction.
  - `EventProcessor.drain()`: one event per transaction, until none are left.
  - `GithubEventHandler.handle(InboxEvent)`.
- **V17:**
  - `events (id BIGSERIAL PK, source TEXT NOT NULL, type TEXT NOT NULL, repo TEXT, delivery_id TEXT UNIQUE, payload JSONB NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), processed_at TIMESTAMPTZ, attempts INT NOT NULL DEFAULT 0, last_error TEXT)`.
  - `idx_events_unprocessed (id) WHERE processed_at IS NULL`.
  - `AFTER INSERT` → `pg_notify('event_received', NEW.id::text)`.
- **Behaviour (pinned):**
  - **Webhook.** After the HMAC check, store `source=github`, `type` = `X-GitHub-Event`, `delivery_id` = `X-GitHub-Delivery` (or `body-` + SHA-256 of the body if the header is absent), and `repo` = `repository.full_name`. Reply 200 `{"stored": true|false}`. No inline routing.
  - **Task lookup.** `findByPullRequest(repo, number)`, else the body marker. No task → ignore, and mark the event processed.
  - **`pull_request`:**
    - **`closed`, merged:**
      - A non-terminal task → cancel its queued runs and kill its active run, then → MERGED, with task_event `merged {pr_number}`. The V14 trigger promotes dependents.
      - Already MERGED or another terminal state → no-op.
    - **`closed`, not merged:** a non-terminal task → NEEDS_HUMAN, with task_event `needs_human {reason: "PR closed without merge — retry or cancel?"}`.
    - **`opened`, `reopened`, `synchronize`:** a non-terminal task whose `head_sha` differs → `setHeadSha(pull_request.head.sha)`.
  - **Anything else** is marked processed with no effect. PR 3 adds checks, reviews and comments.
  - **A handler that throws** → `markFailed`. After 5 attempts the event is left with its `last_error`.
  - **Wake-ups.** `drain` runs on `event_received` and every `orange.events.poll` (5 s), when `orange.events.processing-enabled` (true; tests turn it off).

- [ ] **Step 1: Write failing tests:**
  - `GithubWebhookControllerIT`:
    - `signedDeliveryIsStored`.
    - `duplicateDeliveryIsStoredOnce`.
    - `badSignatureIsRejectedAndNothingStored`.
  - `EventProcessorIT` (processing off; calls `drain()`):
    - `mergeMovesTaskToMergedAndPromotesDependents`: fan-in, two parents.
    - `mergeOfAlreadyMergedTaskIsANoOp`: processed, no error.
    - `unknownPrIsIgnored`.
    - `closedUnmergedNeedsHuman`.
    - `synchronizeUpdatesHeadSha`.
    - `mergeCancelsTheActiveRun`: a queued run → CANCELLED.
    - `failingEventIsRetriedFiveTimesThenParked`: a `pull_request` payload without `number`.
    - `eventsAreProcessedInIdOrder`.
- [ ] **Step 2:** Run the two classes. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(events): webhook inbox with idempotent, ordered processing; merge and close handled through events`

### Task 9: Operator API: runs, live view, health, cancel

**Files:**
- Create: `api/RunController.java`, `api/RunResponse.java`, `api/RunEventResponse.java`
- Modify: `api/StatusController` (`/events/live`, `/health/aggregate`), `api/TaskController` (cancel), `api/TaskResponse` (new columns), `config/MetricsConfig` (gauges per status and kind), `orange-mcp/src/orange_mcp/server.py` (`tail` shows the latest run's events after the task events; `status`/`health` use the new fields)
- Test: `api/RunApiIT.java`, `api/StatusControllerIT`, `api/TaskApiIT` (extend)

**Interfaces:**
- Produces:
  - `GET /tasks/{id}/runs` → `List<RunResponse>`, newest first. Fields: id, kind, status, role, attempt, tier, model, effort, auth, cost_usd, error, queued_at, started_at, finished_at, outcome. Never the token hash.
  - `GET /runs/{id}` → `RunResponse`.
  - `GET /runs/{id}/events?since=0&limit=100` → `List<RunEventResponse(seq, type, payload, created_at)>`.
  - `GET /events/live` → one row per running run: `{run_id, task_id, task_title, kind, model, started_at, recent: [last N run events]}`.
  - `GET /health/aggregate` →
    - `queues`: counts for ready, running, in_review, needs_human;
    - `runs`: per kind `{queued, running, slots}`;
    - `paused`;
    - `cost`: `{total_usd, last_24h_usd}` from `runs`;
    - `envs`.
  - `POST /tasks/{id}/cancel`: `cancelQueued`, `WorkerRunner.cancel`, then any non-terminal → CANCELLED. A terminal task → 409.

- [ ] **Step 1: Write failing tests:**
  - `RunApiIT`: `taskRunsAreListedNewestFirst`, `runEventsPageBySeq`, `tokenHashIsNotExposed`.
  - `StatusControllerIT`: `liveShowsRunningRuns`, `healthCountsRunsAndPause`.
  - `TaskApiIT`: `cancelCancelsQueuedRuns`, `cancelOfMergedTaskIs409`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(api): runs and run events endpoints; live view and health from runs; cancel cancels runs`

### Task 10: Gateway hygiene (timeouts, spend sync, key deletion) and run-dir retention

**Files:**
- Modify: `gateway/GatewayClient` (timeouts; `spendByAlias`, `deleteByAlias`), `gateway/GatewayProperties` (`connect-timeout` 5s, `read-timeout` 30s), `run/RunRepository` (spend-sync and retention queries)
- Create: `run/SpendSync.java`, `run/RunRetention.java`
- Test: `gateway/GatewayClientTest` (extend), `run/SpendSyncIT.java`, `run/RunRetentionIT.java`, `gateway/LiteLlmGatewayLiveIT` (extend)

**Interfaces:**
- Produces:
  - `Optional<BigDecimal> GatewayClient.spendByAlias(String alias)`: `GET /key/list?key_alias=<alias>&return_full_object=true`, using the first key's `spend`.
  - `void GatewayClient.deleteByAlias(String alias)`: `POST /key/delete {"key_aliases":[alias]}`.
  - `RunRepository`: `List<Run> findSpendSyncCandidates()` (finished, `gateway_key_alias` set, key not deleted), `void recordSpend(UUID id, BigDecimal cost)` (also sets `spend_synced_at`), `void markKeyDeleted(UUID id)`, `List<Run> findFinishedBefore(OffsetDateTime t)`.
  - `SpendSync.sync()` runs every 60 s:
    - spend changed → `recordSpend`;
    - unchanged, with `spend_synced_at` (or `finished_at`) more than 10 min ago → `deleteByAlias` + `markKeyDeleted`.
  - `RunRetention.sweep()` runs every 6 h: delete `<run-root>/<runId>` for runs finished before now − `retention` (14 d).
- **Gate.** Verify against the real container first, in `LiteLlmGatewayLiveIT` (no model calls), that `/key/list` filters by `key_alias` and `/key/delete` accepts `key_aliases` on v1.106.6. If either doesn't, rule on the fallback (store LiteLLM's hashed token at mint time) before writing `SpendSync`.

- [ ] **Step 1: Write failing tests:**
  - `GatewayClientTest`:
    - `spendByAliasReadsKeyList` (MockRestServiceServer).
    - `deleteByAliasSendsAliases`.
    - `readTimeoutFailsFast`: a `ServerSocket` that accepts and never answers, with read timeout 200 ms → `GatewayException` within 2 s.
  - `SpendSyncIT`:
    - `changedSpendIsRecorded`.
    - `stableSpendDeletesTheKeyAfterTenMinutes`.
    - `subscriptionRunsAreIgnored`.
  - `RunRetentionIT`: `oldRunDirsAreDeletedRecentOnesKept`.
  - `LiteLlmGatewayLiveIT`: `aliasLookupAndDeleteWork` (live).
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run `./gradlew test` and the live gateway IT (with `LITELLM_MASTER_KEY` and a throwaway container, as in PR 1). Expected: PASS.
- [ ] **Step 5: Commit.** `feat(gateway): client timeouts, spend sync by key alias and key deletion; run-dir retention`

### Task 11: End-to-end run loop, docs, live checks, PR

**Files:**
- Create: `orange/src/test/java/com/ai/orange/e2e/RunLoopE2EIT.java`
- Modify: `CLAUDE.md` (architecture: run loop, reactor, MCP, inbox; config knobs; tests need no Temporal), `orange-mcp/README.md`, `.env.example` (`ORANGE_MCP_WORKER_URL`, `WORKTREES_ROOT`)

**Interfaces:**
- Consumes: everything above. `orange.runs.loop-enabled=true` and `orange.events.processing-enabled=true`.

- [ ] **Step 1: Write the e2e tests.** Setup: `RANDOM_PORT`, fake claude, a temp base repo with a bare origin, the in-memory stub `GithubClient`, a webhook secret, Awaitility with a 60 s cap. The graph is A → B.
  - `readyToMergedPromotesDependents`:
    1. A reaches IN_REVIEW with PR #1 for `orange/<A>`, and the remote branch is at `head_sha`.
    2. A review run SUCCEEDED; `reviewed_sha = head_sha`, verdict `approve`, and the stub holds a COMMENT review with the run marker.
    3. POST a signed `pull_request` closed/merged event for #1 → A is MERGED and B leaves `pending`.
    4. A's task_events include, in order: `run_queued`, `run_started`, `run_finished`, `pr_published`, `run_queued`, `review_submitted`, `merged`.
  - `requestChangesCountsButDoesNotRework`: the task description carries `[[fake:request-changes]]` → `failures` = 1, the task stays IN_REVIEW, and no further run is queued (rework arrives in PR 3).
- [ ] **Step 2:** Run `./gradlew test --tests '*RunLoopE2EIT'`. Expected: PASS. If it fails, fix the code it exposes; don't weaken the test.
- [ ] **Step 3: Docs.** CLAUDE.md, the orange-mcp README and `.env.example`.
- [ ] **Step 4:** Run the full suite and the live checks.
  - `./gradlew test` → green.
  - `./gradlew liveTest --tests '*ClaudeCliContractLiveIT' --tests '*WorkerMcpRealClaudeLiveIT'` → green.
  - `SubscriptionSmokeLiveIT` only if `CLAUDE_CODE_OAUTH_TOKEN` is in `orange/.env`.
- [ ] **Step 5: Commit, push and open the PR** against `orange-e2e-base`. The body covers what changed, what was verified (and how), what wasn't, and the deferred items.
