# orange v2, PR 2: runs replace Temporal — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Revised 2026-11-09.** On that date the user approved `docs/superpowers/specs/2026-11-09-orange-project-space-design.md` (the "project-space spec"). It removes the PR-centred core of v2 (its §10), and its §11 says how this PR changes.
- Tasks 1 and 2 were built before the revision.
- Task 4 reworks Task 2's lifecycle in place. V14 is not merged anywhere yet, so it is edited rather than followed by a new migration.
- The original Tasks 3–11 were renumbered and re-scoped.

**Goal:** The coordinator runs every agent itself and decides "done" from evidence it collected itself. A task's path:
1. `ready` → an implement run.
2. `verifying`: orange runs the evidence commands itself, then an LLM `diff_review`.
3. `verified`: the integrator rebases onto the base, fast-forwards it and pushes it.
4. `integrated`: its dependents become `ready`.

No Temporal, no chat claims, no pipelines, no pull requests.

**Architecture:**
- **Demolition first (Task 1).** Temporal, the `workflow` package, chat-as-executor claims, triage, the post-merge verifier, the concurrency gate and the dead JTE dashboard go.
- **Schema:**
  - the task lifecycle on `tasks` (V14, reworked in Task 4);
  - `runs`, `run_events` and `settings` (V15);
  - `agents` and `agent_runs` dropped (V16);
  - `evidence` (V17);
  - the `events` inbox (V18).
- **Packages:**
  - `run`: the repository, the three run executors, the scheduler with per-kind slots, the reaper, the task reactor, post-run steps, spend sync and retention. The executors are:
    - the agent runner (PR 1's `AgentRunService` reshaped around `runs` rows);
    - the evidence runner (`verify` runs);
    - the integrator (`integrate` runs).
  - `evidence`: evidence rows and the configured evidence commands.
  - `mcp`: the worker MCP endpoint `/mcp/worker`, with per-run bearer tokens.
  - `events`: the inbox and its processor. Webhooks matter only to the later PR mode.
  - `roles`: role prompt files and tool policy per run kind.
- **Integration modes.** Only merge mode is built here (project-space spec §8, `integration: merge`). PR mode is a later phase, so `GithubClient` and `GithubPrService` stay in the code base unused.
- **Ordering rule.** A run row stays `running` until its post-run step is done. That keeps the reactor from queueing a second run for a task while the first run's effects are still being applied.
- **In parallel.** Phase A of the project-space spec is built on its own branch. Whichever branch merges second renumbers its Flyway files to follow the other's (project-space decision 16).

**Tech Stack:** Java 27, Spring Boot 4.0.9, jOOQ, Flyway, Postgres 18 (Testcontainers), MCP Java SDK 2.1.0 (`mcp-core` + `mcp-json-jackson2`), Claude Code 2.1.319, fake Claude Code from PR 1.

**Spec:**
- Project-space spec §7 (`verify`, `diff_review`), §8 (`integration: merge`, `evidence`, `remote-push`), §10 (what changes in v2) and §11. It wins wherever the two specs differ.
- v2 spec: §6 tables, §7 runs, §8 tools per kind, §13 intake and processor, §15 `/mcp/worker`, §16 process mode, §17, §18 and §19 item 2.
- PR 1's deferred minors assigned to PR 2: GatewayClient timeouts and spend sync, run-dir retention, trimmed DEBUG logging, `api_retry` events.

## Global Constraints

- Keep the existing patterns: `uuidv7()` primary keys, the `touch_updated_at()` trigger, partial indexes on hot queues, `pg_notify` on state changes.
- Migrations in this PR are V14 (edited in place by Task 4, since it is unmerged) through V18.
- PR base branch: `orange-e2e-base`. Merge with `gh pr merge --merge`. Work in the worktree.
- **Evidence decides "done"** (project-space spec §2, anti-goals). An agent reporting `done` only moves a task to `verifying`. `verified` requires:
  - every configured evidence command passed, run by orange itself;
  - a clean worktree;
  - a `diff_review` verdict of `pass`.
- **Evidence commands** come from global config, `orange.evidence.commands`; per-project conventions arrive in project-space Phase C. They run on the host, in the task's worktree, with the harness's allowlisted environment (`orange.harness.claude-code.pass-env`, credential-like names dropped). They never get the coordinator's own environment (GitHub token, LiteLLM master key, OAuth token).
- **Agents commit locally and never push.** The integrator does every push, using the host's git credentials (v2 decision 11).
- **Integration (merge mode)** runs one at a time (the `integrate` slot is 1):
  1. Rebase the task branch onto the base.
  2. If the rebase changed the head, re-verify.
  3. Otherwise fast-forward the base. When a remote exists and `orange.integration.remote-push` is true (the default), push the base.
- **Per-run MCP tokens** are 32 random bytes, base64url. Only the `SHA-256` hex goes in `runs.mcp_token_hash`. The raw token lives only in memory, in the run's MCP config file (deleted when the run ends) and in the child process. It is redacted like a credential.
- Subscription-token and virtual-key rules from PR 1 still hold: only the `claude` child gets them.
- **Slots:**

  | Kind | Slots |
  |---|---|
  | `plan` | 1 |
  | `implement` | 4 |
  | `ci_fix` | 2 |
  | `verify` | 2 |
  | `diff_review` | 2 |
  | `integrate` | 1 |
  | `judge`, `brief` | 0 (project-space phases B and D) |

  Global cap `max-concurrent`: 6.
- **Leases:** heartbeat every 60 s, TTL 5 min.
- **Timeouts:** implement 60 min, ci_fix 40, diff_review 20, plan 30, verify 30, integrate 10.
- **Reactor:** sweeps every 30 s, plus on notify.
- **Events:** processed in id order; a failing event is retried up to 5 attempts.
- **Escalation:** tier up after 2 failures; `needs_human` after 3 failures.
- **Routing.** The `reviewer` rule becomes `tier: medium, effort: low`. Project-space §3 routes verify-review runs to the small or medium tier at low effort.
- **Tools per kind** (v2 §8; project-space decision 13 for `diff_review`):

  | Kind | `--tools` | `--allowedTools` | Permission mode |
  |---|---|---|---|
  | implement, ci_fix | `Read Write Edit Bash Glob Grep TodoWrite` | the same, plus `mcp__orange__report_result`, `mcp__orange__request_human` | `acceptEdits` |
  | diff_review | `Read Glob Grep` | `Read Glob Grep`, `mcp__orange__submit_review` | `dontAsk` |
  | plan | `Read Write Glob Grep WebFetch` (unchanged until v2 PR 4) | the same | `acceptEdits` |

  `verify` and `integrate` are run by orange's own code, not by an agent.
- **Failure reasons:**
  - *Infra* (never counted in `failures`; they pause the scheduler): `subscription_token_missing`, `auth_failed`, `plan_limit`, `gateway_unavailable`, `start_failed`, `workspace_failed`.
  - *Infra* (never counted, no pause): `cancelled`, `lease_expired`, `lease_lost`, `coordinator_restart`.
  - *Model* (each adds 1 to `failures`): `timeout`, `budget`, `error_result`, `no_result`, `exit_<n>`, `no_outcome`, `reported_failed`, `no_commits`.
  - Gaps from a `verify` run or a `diff_review` also add 1.
  - An integration conflict does not count. It sends the task back for a fix run.
  - `integrate_failed` (git or remote trouble while integrating) moves the task to `needs_human`.
- Default worktree root moves outside the repo: `${user.home}/.orange/worktrees` (v2 decision 15).
- **Not in this PR:**
  - PR mode;
  - the rebase sweep when the base moves (project-space Phase C; this PR rebases only at integration);
  - sessions, judge and brief (project-space phases A–D);
  - scope claims, epics and notes (v2 PR 4);
  - ntfy and the steering MCP.

## Review Focus

1. **Coordinator restart mid-run.** Runs a previous instance left `running` must end `failed`/`coordinator_restart`, which is not counted. Their process must die, and the task must get a fresh run instead of sitting in `running` forever. Pinned in Task 8.
2. **Integration races.**
   - Two tasks verified at the same time must both land, with no commit lost.
   - If the base moves between verification and fast-forward, the task is rebased and re-verified. It is never fast-forwarded over code the evidence didn't cover.
   - Pinned in Task 7.
3. **Hot loops.**
   - Failures that recur instantly must pause the scheduler instead of requeueing the same run: plan limit, rejected or missing token, gateway down, missing binary, broken base repo.
   - A verify or diff review that keeps failing must reach `needs_human`.
   - Pinned in Tasks 6, 7 and 8.
4. **MCP token scope.** A token resolves to exactly one `running` run. A finished run's token, a tool of another kind, or no token writes nothing. Pinned in Task 5.
5. **Evidence integrity.**
   - None of these reach `integrated`: a `done` with no commits, uncommitted files, a failing evidence command, or a missing one.
   - Evidence commands never see the coordinator's secrets.
   - Pinned in Task 7.

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

> **Superseded in part (2026-11-09).** The project-space spec replaces this lifecycle, and Task 4 reworks V14 and the task model in place. What stands from this task: the dropped v1 columns and `task_claims`, the roles, `failures`, `repo`, `branch`, `pr_number`/`pr_url` (now PR-mode only), `head_sha`, reopen with its failure reset, `notify_task_changed`, and the promotion trigger's mechanics.

### Task 3: `runs`, `run_events`, `settings` (V15); run repository; scheduler pause

**Files:**
- Create: `db/migration/V15__runs.sql`, `run/RunKind.java`, `run/RunStatus.java`, `run/Run.java`, `run/RunRepository.java`, `run/RunEventRepository.java`, `run/RunEventRow.java`, `run/SchedulerPause.java`
- Modify: `listener/PgListenerService` (add `run_queued`, `run_changed`)
- Test: `run/RunRepositoryIT.java`, `run/SchedulerPauseIT.java`

**Interfaces:**
- Produces:
  - `enum RunKind { PLAN, IMPLEMENT, CI_FIX, VERIFY, DIFF_REVIEW, INTEGRATE, JUDGE, BRIEF }`, with db values `plan implement ci_fix verify diff_review integrate judge brief`, plus `fromDb`.
    - `JUDGE` and `BRIEF` are reserved for project-space phases B and D. They are in the CHECK now so those phases need no migration (project-space §11).
    - `INTEGRATE` is this plan's call. As a run row, an integration gets a lease, the reaper and slot-1 serialization for free.
  - `enum RunStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }`, with `isActive()` for QUEUED and RUNNING.
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
  - `runs` has the v2 §6 columns, minus `epic_id` (v2 PR 4) and `session_id` (project-space Phase A adds it with the `sessions` table). It adds:
    - `priority INT NOT NULL DEFAULT 100`;
    - `auth TEXT CHECK (auth IN ('subscription','gateway'))`;
    - `pid BIGINT`;
    - `gateway_key_deleted_at TIMESTAMPTZ`;
    - `updated_at` with `touch_updated_at`.
  - `kind CHECK` lists all eight kinds.
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
    - `oneActiveRunPerTask`: queue implement → present; queue a diff review on the same task → empty; finish the first → the next one queues. `attempt` counts per kind.
    - `concurrentClaimsNeverShareARun`: 20 queued, 8 threads claim until empty → 20 distinct ids.
    - `claimOrdersByPriorityThenQueueTime`.
    - `heartbeatOnlyExtendsTheOwnersLease`.
    - `outcomeIsOnlyRecordedWhileRunning`.
    - `expiredLeasesAndForeignRunsAreFound`.
    - `planRunsHaveNoTask`: via `insertRunning`.
    - `cancelQueuedCancelsOnlyQueuedRuns`.
    - `runEventsPageBySeq`.
  - `SchedulerPauseIT`: `timedPauseExpires`, `openEndedPauseSurvivesATimedOne`, `laterUntilWins`, `resumeClears`.
- [ ] **Step 2:** Run the two classes. Expected: FAIL (types missing).
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run the two classes, then `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(run): runs, run_events and settings tables; run repository with leases; scheduler pause`

### Task 4: Lifecycle for evidence-gated integration (V14 reworked)

**Files:**
- Modify:
  - `db/migration/V14__fsm_v2.sql`, edited in place since it is unmerged;
  - `task/TaskStatus`, `Task`, `TaskRepository`;
  - `api/TaskResponse`, `StatusController`;
  - `github/GithubEventRouter` (logs only);
  - `devenv/DevEnvAutoReleaser`, `config/MetricsConfig`;
  - `orange-mcp/src/orange_mcp/server.py`, `plugin/skills/orange-orchestrate/*`, `CLAUDE.md`.
- Test: `task/TaskStatusTest`, `db/FlywayMigrationsIT`, `task/TaskServiceCreateGraphIT`, `github/GithubEventRouterIT`, `devenv/DevEnvAutoReleaserIT`, `api/StatusControllerIT`

**Interfaces:**
- Produces:
  - `enum TaskStatus { PENDING, READY, RUNNING, VERIFYING, VERIFIED, INTEGRATED, NEEDS_HUMAN, FAILED, CANCELLED }`, with db values `pending ready running verifying verified integrated needs_human failed cancelled`. `isTerminal()` is true for INTEGRATED, FAILED and CANCELLED. `allowedNext()` (project-space §10):
    - PENDING → READY, NEEDS_HUMAN, CANCELLED, FAILED
    - READY → RUNNING, NEEDS_HUMAN, CANCELLED, FAILED
    - RUNNING → VERIFYING, NEEDS_HUMAN, CANCELLED, FAILED
    - VERIFYING → VERIFIED, RUNNING (gaps), NEEDS_HUMAN, CANCELLED, FAILED
    - VERIFIED → INTEGRATED, VERIFYING (a rebase changed the head), RUNNING (conflict), NEEDS_HUMAN, CANCELLED, FAILED
    - NEEDS_HUMAN → READY, CANCELLED, FAILED
    - FAILED → READY (operator reopen)
    - INTEGRATED, CANCELLED → none
  - `record Task(UUID id, String title, String description, String role, TaskStatus status, int priority, int failures, String repo, String branch, Integer prNumber, String prUrl, String headSha, JSONB metadata, OffsetDateTime createdAt, OffsetDateTime updatedAt)`. `prNumber` and `prUrl` are used only by the later PR mode.
  - `TaskRepository.recordHead(UUID id, String branch, String headSha)`: what verification and integration work from.
- **V14, pinned end state** (replaces Task 2's):
  - Status mapping:
    - `in_progress` → `ready`;
    - `pr_open` → `needs_human`: a v1 PR waits for a person, and PR mode comes later;
    - `dev_ready`, `in_test` and `test_done` → `integrated`.
  - New CHECK on the 9 statuses.
  - Columns: `repo`, `failures`, `branch`, `pr_number`, `pr_url` and `head_sha`. Task 2's `reviewed_sha` and `review_verdict` are removed; the diff-review verdict is evidence.
  - `idx_tasks_active` covers ready, running, verifying, verified and needs_human.
  - `promote_ready_dependents()` treats `integrated` as satisfied.
  - Roles, the dropped v1 columns and table, `notify_task_changed` and the PR backfill stay as Task 2 built them.
- `GithubEventRouter` changes no task. PR mode, a later phase, gives merge events meaning again; Task 9 replaces the router with the inbox.
- `DevEnvAutoReleaser` release boundary: VERIFIED, INTEGRATED, NEEDS_HUMAN, FAILED, CANCELLED.
- Health queues and metric gauges cover READY, RUNNING, VERIFYING, VERIFIED and NEEDS_HUMAN.
- Docs (CLAUDE.md, the orange-mcp status lists, the plugin skill) describe the verify-and-integrate flow; PR wording goes.

- [ ] **Step 1: Write failing tests:**
  - `TaskStatusTest.transitions`: one assertion per row above, plus `INTEGRATED.isTerminal()`.
  - `FlywayMigrationsIT.v1RowsMapToFsmV2`: the mapping above; `pr_open` → `needs_human`; `reviewed_sha` is absent.
  - `TaskServiceCreateGraphIT.integratedParentPromotesChild`: a `verified` parent does not count; once both parents are `integrated`, the child is `ready`.
  - `GithubEventRouterIT.mergedPrEventChangesNoTask`.
  - `DevEnvAutoReleaserIT`: `releases_lease_when_task_is_verified`, `leaves_lease_alone_while_task_is_verifying`.
- [ ] **Step 2:** Run the listed classes. Expected: FAIL.
- [ ] **Step 3: Implement**, updating the listed callers, ITs and docs.
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `refactor(task)!: evidence-gated lifecycle — verifying, verified, integrated (project-space spec §10)`

### Task 5: Worker MCP endpoint (`/mcp/worker`)

**Files:**
- Modify: `build.gradle.kts`: add `implementation("io.modelcontextprotocol.sdk:mcp-core:2.1.0")` and `implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:2.1.0")`.
- Create:
  - `mcp/RunTokens.java`;
  - `run/RunOutcome.java`, `run/ReviewGap.java`;
  - `mcp/WorkerTokenFilter.java`, `mcp/WorkerTools.java`, `mcp/WorkerMcpConfig.java`;
  - `mcp/WorkerMcpUrl.java`, `mcp/WorkerMcpConfigWriter.java`.
- Modify: `config/SecurityConfig` (permit `/mcp/worker`; the token filter authenticates).
- Modify: `src/test/resources/fake-claude/FakeClaude.java`: an MCP client, and default behaviour picked from `--allowedTools` (below).
- Test: `mcp/WorkerMcpIT.java` (`webEnvironment = RANDOM_PORT`), `mcp/McpTestClient.java` (test util), `run/RunOutcomeTest.java`, `harness/claude/WorkerMcpRealClaudeLiveIT.java` (`@Tag("live")`)

**Interfaces:**
- Consumes: `RunRepository.findRunningByTokenHash`, `recordOutcome`, `insertRunning` (tests).
- Produces:
  - `RunTokens`: `static String newToken()`, `static String hash(String token)` (lowercase SHA-256 hex).
  - `record ReviewGap(String path, Integer line, String body)`. `path` and `line` may be null for a gap that isn't about one place.
  - `sealed interface RunOutcome`, with:
    - `record Result(String status /* done|blocked|failed */, String summary, String question) implements RunOutcome`;
    - `record Review(String verdict /* pass|gaps */, String summary, List<ReviewGap> gaps) implements RunOutcome`.

    Methods: `JSONB toJsonb()` (with `"type":"result"|"review"`), `static Optional<RunOutcome> parse(JSONB)`.
  - `WorkerMcpUrl.url()`: `orange.mcp.worker-url` if set, else `http://127.0.0.1:<port>/mcp/worker`, with the port from `WebServerInitializedEvent`.
  - `WorkerMcpConfigWriter.write(Path runDir, String token) : Path` writes `<runDir>/mcp.json` containing `{"mcpServers":{"orange":{"type":"http","url":"<url>","headers":{"Authorization":"Bearer <token>"}}}}`.
  - Tool names: `report_result`, `request_human`, `submit_review`. The server is named `orange`, so Claude Code sees them as `mcp__orange__…`.
- **Behaviour (pinned):**
  - **`WorkerTokenFilter`** is a `FilterRegistrationBean` on `/mcp/worker`.
    - No `Bearer` token, or no `running` run with that hash → HTTP 401 `{"error":"invalid or inactive run token"}`.
    - Otherwise it puts the `Run` in a request attribute. The transport's `contextExtractor` copies it into `McpTransportContext` under `"run"`.
  - **Server.** `McpServer.sync(HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp/worker")…)`, registered with a `ServletRegistrationBean` on `/mcp/worker`. A tool filter shows each kind only its tools:
    - IMPLEMENT and CI_FIX: `report_result`, `request_human`;
    - DIFF_REVIEW: `submit_review`;
    - PLAN: `request_human`;
    - any other kind: none.
  - **Handlers** return `CallToolResult` with `isError=true`, never throw, and write nothing when:
    - the tool is not allowed for the run's kind;
    - arguments are invalid (enums, a blank `summary`, a gap without `body`, verdict `gaps` with no gaps);
    - `recordOutcome` returns false (the run stopped).
  - **On success** the handler stores the `RunOutcome` and returns `"recorded"`:
    - `report_result {status, summary}` → `Result(status, summary, null)`;
    - `request_human {question}` → `Result("blocked", question, question)`;
    - `submit_review {verdict, summary, gaps}` → `Review`.
  - **FakeClaude** reads `--mcp-config` and `--allowedTools` (variadic). It speaks MCP over HTTP the way Claude Code does: POST JSON-RPC `initialize`, then `notifications/initialized`, then `tools/call`, with `Accept: application/json, text/event-stream` and the config's headers. It reads JSON or SSE `data:` responses and appends every reply to `$CLAUDE_CONFIG_DIR/fake-mcp.log`. It also writes the prompt it received to `$CLAUDE_CONFIG_DIR/fake-prompt.txt`.
  - **FakeClaude default behaviour, when no marker overrides it:**
    - **Allowed `mcp__orange__report_result`:**
      1. Append a line to `orange-fake.txt` in its cwd.
      2. If the prompt contains `## Feedback to address`, also create `fixed.txt`.
      3. `git add -A`, then `git commit -m "fake: <first prompt line>"`.
      4. Call `report_result(done, "Implemented: <first prompt line>")`.
    - **Allowed `mcp__orange__submit_review`:** `submit_review(pass, "Looks good", [])`.
  - **FakeClaude markers:**
    - `[[fake:no-commit]]`, `[[fake:report-failed]]`, `[[fake:blocked]]`, `[[fake:no-outcome]]`, `[[fake:wrong-tool]]` (an implement run calls `submit_review`);
    - `[[fake:dirty]]`: commits, then leaves an uncommitted `scratch.txt`;
    - `[[fake:review-gaps]]`: `submit_review(gaps, …)` with one gap on `README.md` line 1 and one without a path.

- [ ] **Step 1: Write failing tests:**
  - `RunOutcomeTest`: JSON round-trip for both records; malformed JSONB → empty.
  - `WorkerMcpIT`. Runs are inserted with `insertRunning` and a hashed token; `McpTestClient` speaks JSON-RPC over HTTP.
    - `missingOrUnknownTokenIs401`.
    - `finishedRunTokenIs401`.
    - `implementRunListsOnlyItsTools`.
    - `diffReviewRunListsOnlySubmitReview`.
    - `reportResultRecordsTheOutcome`.
    - `invalidVerdictIsAnErrorAndRecordsNothing`, plus `gapsVerdictNeedsAGap`.
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

### Task 6: Agent runner on `runs`; role prompts and tool policy; drop `agents`/`agent_runs` (V16)

**Files:**
- Move: `agent/AgentRunService.java` → `run/WorkerRunner.java` (use `git mv` to keep history). Replace `AgentRunRequest`/`AgentRunResult` with `run/RunResult.java`, `run/PreparedRun.java`, `run/RunPreparer.java` and `run/RunExecutor.java`.
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
  - `planner/PlannerService` (uses `runDirect`), `api/StatusController` (cost from `runs`);
  - `task/TaskRepository` (add `incrementFailures`);
  - `worktree/WorktreeService`: add `Path ensure(UUID taskId)`, which creates the task's worktree if it is missing and otherwise returns the existing path, so later runs keep earlier commits;
  - `application.yaml`: `orange.runs`; the routing rule `reviewer` becomes `medium`/`low`.
- Delete: `agent/*` (Agent, AgentRepository, AgentRun, AgentRunRepository, AgentRunStatus, AgentRunRequest, AgentRunResult), `db/ReviewerVerdictBindingMigrationIT`.
- Test: `run/WorkerRunnerIT.java` (from `AgentRunServiceIT`, `RANDOM_PORT`), `run/WorkerRunnerNoTokenIT.java`, `run/WorkerRunnerGatewayIT.java`, `roles/ToolPolicyTest.java`, `roles/RolePromptsTest.java`, `billing/PlanLimitTest` (extend), `harness/claude/StreamJsonParserTest` (extend), `planner/PlannerServiceIT` (update)

**Interfaces:**
- Consumes: Task 3 repositories and `SchedulerPause`; Task 5 `RunTokens`, `WorkerMcpConfigWriter`, `RunOutcome`; PR 1 routing, harness, gateway and billing.
- Produces:
  - `interface RunExecutor { Set<RunKind> kinds(); RunResult execute(Run run); }`. `execute` takes a run already claimed as `running`.
  - `WorkerRunner implements RunExecutor` for the agent kinds PLAN, IMPLEMENT, CI_FIX and DIFF_REVIEW; JUDGE and BRIEF come later. It also provides:
    - `RunResult runDirect(RunKind kind, String role, Path cwd, String prompt, Duration timeout)`: inserts a running row with no task (planner) and executes it;
    - `boolean cancel(UUID taskId)`;
    - `Set<UUID> activeRunIds()`.
  - `record RunResult(UUID runId, RunStatus status, String reason /* null on success */, RunOutcome outcome /* nullable */, String lastAssistantText, BigDecimal costUsd)`.
  - `record PreparedRun(Path cwd, String prompt, String role, String difficulty, boolean risk, int failures, Runnable cleanup)`; `RunPreparer.prepare(Run run) : PreparedRun` handles IMPLEMENT and CI_FIX here; Task 7 adds DIFF_REVIEW.
  - `PostRunSteps.after(Run run, RunResult result) : RunResult` returns the final result. Task 7 adds the `done`, verify, review and integrate steps.
  - `ToolPolicy.forKind(RunKind) : record ToolPolicy(List<String> tools, List<String> allowedTools, String permissionMode, boolean mcp)`, with the values in Global Constraints.
  - `RolePrompts.systemPrompt(String role)`: `implementer` and `chore` → `roles/implementer.md`; `reviewer` and `planner` → their own file.
  - `FailureReasons`: `boolean isInfra(String reason)`; `Optional<Pause> pauseFor(String reason, String resultText, ZonedDateTime now)`:
    - `plan_limit` → until `PlanLimit.resetsAt` or now + 1 h;
    - `auth_failed`, `subscription_token_missing`, `start_failed`, `workspace_failed` → open-ended;
    - `gateway_unavailable` → now + 5 min.
  - `PlanLimit.resetsAt(String text, ZonedDateTime now) : Optional<ZonedDateTime>` parses `resets 5pm (Europe/Berlin)`, `reset at 3am`, `resets 5:30pm (UTC)` and returns the next occurrence after `now`, in the named zone or else `now`'s zone.
  - `RunEvent.ApiRetry(int attempt, int maxRetries, long retryDelayMs, Integer errorStatus, String error)`.
  - `TaskRepository.incrementFailures(UUID id)`.
  - `RunsProperties`:
    - `loopEnabled` (true); `workerId` (blank → `<hostname>-<8 hex>`); `maxConcurrent` 6;
    - `slots` and `timeouts`, maps keyed by kind db value;
    - `leaseTtl` 5m, `heartbeat` 60s, `schedulerPoll` 5s, `reactorSweep` 30s, `reaperSweep` 30s, `retention` 14d.
- **Behaviour (pinned), in order for one agent run:**
  1. **Prepare.** `RunPreparer.prepare`:
     - IMPLEMENT and CI_FIX: `WorktreeService.ensure(taskId)`.
     - Prompt, in order:
       - the task title and description;
       - the operator's `metadata.reopen_reason.reason`, if any;
       - `## Feedback to address`, listing each item of `run.input.feedback` (`[{source, summary, detail}]`);
       - `## Previous attempts`, listing each failed run of the task (attempt, reason, outcome summary or last assistant text, at most 2,000 characters).
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
       - no outcome when the kind requires one (IMPLEMENT, CI_FIX, DIFF_REVIEW) → `no_outcome`;
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
  - `implementer.md` is based on V5's dev prompt. It works only in this repo, commits everything (nothing left uncommitted) but never pushes, and says orange verifies the work itself. It finishes by calling `report_result` (`done` with a summary, or `failed` with why), or `request_human` for a decision.
  - `reviewer.md` is the diff reviewer, based on V10 plus V13. It covers correctness against the stated intent, conventions, tests and risk, has no Bash, and calls `submit_review` exactly once: `pass`, or `gaps` with each gap's path and line where it has one.
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
    - `fixRunPromptCarriesTheFeedback`: a CI_FIX run with `input.feedback` → `fake-prompt.txt` contains `## Feedback to address` and the item.
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
- [ ] **Step 5: Commit.** `feat(run)!: agent runner on runs — MCP outcomes, leases, per-kind tools and timeouts, pause on infra failures; role prompts as files`

### Task 7: Evidence, verification and merge-mode integration (replaces v2's push-and-PR task)

**Files:**
- Create:
  - `db/migration/V17__evidence.sql`;
  - `evidence/Evidence.java`, `evidence/EvidenceRepository.java`, `evidence/EvidenceProperties.java` (`orange.evidence`);
  - `run/EvidenceRunner.java` (VERIFY), `run/Integrator.java` (INTEGRATE), `run/IntegrationProperties.java` (`orange.integration`);
  - `worktree/Git.java`: one place to run git with a timeout; `GitException` carries the exit code and output.
- Modify:
  - `run/RunPreparer` (DIFF_REVIEW), `run/PostRunSteps`;
  - `worktree/WorktreeService`, `WorktreeProperties` (default root `${user.home}/.orange/worktrees`);
  - `application.yaml`.
- Test: `run/EvidenceRunnerIT.java`, `run/ImplementDoneIT.java`, `run/DiffReviewIT.java` (`RANDOM_PORT`), `run/IntegratorIT.java`, `worktree/WorktreeServiceIT` (extend)

**Interfaces:**
- Consumes: Tasks 3–6.
- Produces:
  - **V17:** `evidence (id UUID PK DEFAULT uuidv7(), task_id UUID NOT NULL REFERENCES tasks ON DELETE CASCADE, run_id UUID REFERENCES runs ON DELETE SET NULL, head_sha TEXT NOT NULL, kind TEXT NOT NULL CHECK (kind IN ('tests','build','lint','custom','diff_review','screenshot','manual')), command TEXT, exit_code INT, output_tail TEXT, passed BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`, with index `(task_id, head_sha)`. The `session_id` column comes with project-space Phase A's `sessions` table.
  - `record Evidence(UUID id, UUID taskId, UUID runId, String headSha, String kind, String command, Integer exitCode, String outputTail, boolean passed, OffsetDateTime createdAt)`.
  - `EvidenceRepository`:
    - `Evidence record(UUID taskId, UUID runId, String headSha, String kind, String command, Integer exitCode, String outputTail, boolean passed)`;
    - `List<Evidence> findByTaskAndHead(UUID taskId, String headSha)`;
    - `boolean hasPassingDiffReview(UUID taskId, String headSha)`;
    - `List<Evidence> findByTask(UUID taskId)`, newest first.
  - `EvidenceProperties`: `List<Command> commands`, with `record Command(String kind, String command, Duration timeout /* default 20m */)` (default: none); `int outputTailChars` (4000).
  - `IntegrationProperties`: `boolean remotePush` (true).
  - `EvidenceRunner implements RunExecutor` (VERIFY) and `Integrator implements RunExecutor` (INTEGRATE).
  - `WorktreeService` adds:
    - `String baseRef()`: `origin/<base>` when an `origin` remote exists, else `<base>`;
    - `void fetchBase()`: `git fetch origin <base>` when there is an origin;
    - `boolean hasCapacity()`.
  - `Git`: `String run(Path dir, Duration timeout, String... args)`, `int exitCode(Path dir, Duration timeout, String... args)`, `int commitsAhead(Path dir, String baseRef)`, `String head(Path dir)`, `List<String> porcelain(Path dir)`.
- **Behaviour (pinned):**
  - **IMPLEMENT and CI_FIX post-run, outcome `done`:**
    - `commitsAhead(worktree, baseRef)` = 0 → result FAILED/`no_commits` and `incrementFailures`.
    - Otherwise `recordHead(taskId, "orange/<taskId>", HEAD)`, RUNNING → VERIFYING, and task_event `verification_started {run_id, head_sha}`.
  - **`EvidenceRunner.execute(verify run)`.** The input is `{"head_sha": …}`.
    1. **Workspace.** The task worktree must exist (else `workspace_failed`). Its HEAD must equal `input.head_sha`; if not, record outcome `{"stale": true}` and finish SUCCEEDED with no effect.
    2. **Clean check.** If `git status --porcelain` lists anything, record evidence: kind `custom`, command `git status --porcelain`, `passed=false`, the file list as output.
    3. **Commands.** Run each configured command as `sh -c <command>` in the worktree, with the allowlisted environment and its timeout. Each becomes an evidence row:
       - exit 0 → passed;
       - a timeout → exit `-1` with `timed out` in the output;
       - the output tail is limited to `outputTailChars`.
    4. **Outcome.** `{"passed": bool, "gaps": [{source, command, exit_code, output_tail}]}`; the run SUCCEEDED.
  - **VERIFY post-run:**
    - **Gaps:** VERIFYING → RUNNING, `incrementFailures`, and task_event `verification_failed {run_id, head_sha, gaps}`.
    - **Passed:** nothing. The reactor queues the diff review.
  - **DIFF_REVIEW preparation.**
    - cwd is the task worktree. Its tools are read-only, and no code-changing run is active while a task is verifying.
    - Prompt: the intent (title and description), the base ref, the head sha, `git diff --stat <baseRef>...<head>`, then the full `git diff <baseRef>...<head>`. The prompt is capped at 200,000 characters, with a note saying where it was cut.
  - **DIFF_REVIEW post-run, outcome `Review`:**
    - Record evidence: kind `diff_review`, `passed` = (verdict is `pass`), output = the summary plus the gaps.
    - `pass` → VERIFYING → VERIFIED, with task_event `verified {head_sha}`.
    - `gaps` → VERIFYING → RUNNING, `incrementFailures`, and task_event `review_gaps {run_id, head_sha, gaps}`.
    - A model failure (no outcome) → `incrementFailures`; the task stays VERIFYING.
  - **`Integrator.execute(integrate run)`.** The input is `{"head_sha": …}`.
    1. `fetchBase()`.
    2. Check that the task worktree is at `input.head_sha`; if not, outcome `{"status":"stale"}`.
    3. `git rebase <baseRef>` in the task worktree. On a conflict, `git rebase --abort` and outcome `{"status":"conflict","files":[…]}`.
    4. If the head changed → outcome `{"status":"rebased","head_sha":<new>}`.
    5. Otherwise fast-forward:
       - **With a remote and `remotePush`:** `git push origin HEAD:refs/heads/<base>`. A non-fast-forward rejection → outcome `{"status":"base_moved"}`. After a push, `fetchBase()` again.
       - **The local base branch in the base repo:**
         - if it is checked out and clean, `git merge --ff-only <head>` there;
         - if it isn't checked out, `git update-ref refs/heads/<base> <head> <old>`;
         - if it is checked out and dirty, leave it and say so in the outcome.
       - Outcome `{"status":"integrated","head_sha":…,"pushed":bool,"local_base":"updated|dirty|skipped"}`.
    6. A git error in any of these steps → FAILED/`integrate_failed`.
  - **INTEGRATE post-run:**

    | Outcome | Task effect | task_event |
    |---|---|---|
    | integrated | VERIFIED → INTEGRATED; the V14 trigger promotes dependents | `integrated {head_sha, pushed}` |
    | rebased | `recordHead(new)`, copy the passing diff-review evidence to the new head (`output_tail` "carried over from <old> after a clean rebase"), VERIFIED → VERIFYING | `rebased {from, to}` |
    | conflict | VERIFIED → RUNNING, no `failures` change | `integration_conflict {files}` |
    | base_moved, stale | none; the reactor queues another integrate | — |
    | `integrate_failed` | NEEDS_HUMAN with the reason | — |

    The next fix run's prompt carries the conflict through `run.input.feedback` (Task 8).

- [ ] **Step 1: Write failing tests.** Use a temp base repo with a bare `origin`, and drive the executors directly on runs made with `insertRunning`.
  - `ImplementDoneIT` (`RANDOM_PORT`, real `WorkerRunner` and fake):
    - `doneWithCommitsMovesToVerifying`: `head_sha` is the branch HEAD.
    - `doneWithoutCommitsIsAFailure`: `[[fake:no-commit]]` → `no_commits`, failures 1, task RUNNING.
  - `EvidenceRunnerIT`:
    - `passingCommandsRecordEvidence`: two commands → two passing rows, outcome passed, task still VERIFYING.
    - `failingCommandIsAGapThatCounts`: task RUNNING, failures 1, the gap carries the output tail.
    - `uncommittedFilesAreAGap`.
    - `commandTimeoutIsAGap`.
    - `staleHeadIsANoOp`.
    - `commandsSeeOnlyTheAllowlistedEnvironment`: the command is `env`; every name it prints is in pass-env or the shell's own (`PWD`, `SHLVL`, `_`, `OLDPWD`); `CLAUDE_CODE_OAUTH_TOKEN` and `LITELLM_MASTER_KEY` are absent.
  - `DiffReviewIT` (`RANDOM_PORT`):
    - `passVerifiesTheTask`: evidence `diff_review` passed; `fake-prompt.txt` contains the diff of `orange-fake.txt`.
    - `gapsSendTheTaskBack`: `[[fake:review-gaps]]` → RUNNING, failures 1, evidence passed=false with both gaps.
    - `noOutcomeCounts`.
    - `hugeDiffIsCapped`: a 1 MB file → the prompt is at most 200,000 characters plus the note.
  - `IntegratorIT`:
    - `fastForwardsAndPushes`: origin `main` is the task head; the local `main` (checked out, clean) is updated; the task is INTEGRATED; its dependent is READY.
    - `baseMovedSinceVerificationRebasesAndReverifies`: another commit lands on origin `main` → outcome `rebased`, a new `head_sha`, task VERIFYING, and the diff-review evidence is carried over to the new head.
    - `conflictSendsTheTaskBackWithoutCountingAFailure`: the worktree is clean afterwards, with no rebase in progress.
    - `twoVerifiedTasksBothLand`: two tasks from the same base, integrated one after the other → the first fast-forwards; the second is rebased, re-verified by test code and integrated; origin `main` contains both commits.
    - `noRemoteUpdatesTheLocalBase`.
    - `dirtyBaseCheckoutIsLeftAlone`: outcome `local_base: dirty`, origin still pushed.
- [ ] **Step 2:** Run the listed classes. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(run): evidence runner, diff review and merge-mode integrator — orange verifies and integrates work itself`

### Task 8: Scheduler, reaper, task reactor

**Files:**
- Create: `run/RunScheduler.java`, `run/RunExecutors.java`, `run/RunReaper.java`, `run/TaskReactor.java`, `run/ReactorDecision.java`, `api/SchedulerController.java`, `src/test/resources/config/application.yaml` (turns the run loop off for every test unless a test turns it on)
- Modify: `task/TaskRepository` (add `Optional<Task> lockForUpdate(UUID id)` and `List<UUID> findActiveIds()`), `run/RunRepository` (add `Optional<Run> findLastFinished(UUID taskId)` and `boolean hasSucceeded(UUID taskId, RunKind kind, String headSha)`), `config/SecurityConfig` (permit `/scheduler/**`)
- Test: `run/ReactorDecisionTest.java`, `run/TaskReactorIT.java`, `run/RunSchedulerIT.java`, `run/RunReaperIT.java`, `api/SchedulerControllerIT.java`

**Interfaces:**
- Consumes: Tasks 3–7.
- Produces:
  - `RunExecutors`: collects every `RunExecutor` bean; `Optional<RunExecutor> forKind(RunKind)`.
  - `ReactorDecision.decide(TaskSnapshot s, int needsHumanAfter) : Action`:
    - `record TaskSnapshot(TaskStatus status, int failures, boolean hasActiveRun, boolean paused, boolean workspaceAvailable, String headSha, LastRun lastRun, boolean verifyPassed, boolean reviewPassed)`;
    - `record LastRun(RunKind kind, RunStatus status, JSONB outcome)`;
    - `sealed interface Action` with `QueueImplement(JSONB feedback)`, `QueueFix(JSONB feedback)`, `QueueVerify(String headSha)`, `QueueDiffReview(String headSha)`, `QueueIntegrate(String headSha)`, `MarkVerified`, `NeedsHuman(String reason)`, `None`.
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
  3. `failures ≥ needsHumanAfter` and status RUNNING, VERIFYING or VERIFIED → `NeedsHuman("failures")`.
  4. READY and `workspaceAvailable` → `QueueImplement` (READY → RUNNING). A ready task with no worktree slot stays READY.
  5. RUNNING: the fix kind depends on the last finished run.
     - A VERIFY run with gaps, an INTEGRATE run with a conflict, or a failed CI_FIX → `QueueFix`. The feedback comes from that run's gaps or conflict files; a failed CI_FIX reuses its own input.
     - Otherwise → `QueueImplement`: a first attempt after a reopen, a retry, or rework after DIFF_REVIEW gaps (feedback: the review's gaps).
  6. VERIFYING with `headSha` set:
     - `!verifyPassed` → `QueueVerify(headSha)`;
     - else `!reviewPassed` → `QueueDiffReview(headSha)`;
     - else `MarkVerified` (VERIFYING → VERIFIED). This covers a crash between recording evidence and the transition.
  7. VERIFIED → `QueueIntegrate(headSha)`.
  8. Otherwise None.
- **Snapshot inputs.**
  - `verifyPassed`: `hasSucceeded(task, VERIFY, headSha)`, where the run's outcome has `passed: true`.
  - `reviewPassed`: `EvidenceRepository.hasPassingDiffReview(task, headSha)`.
  - `workspaceAvailable`: the task has a worktree, or `WorktreeService.hasCapacity()`.
- **Queueing.** Each queued run's role:

  | Kind | Role |
  |---|---|
  | IMPLEMENT | the task's role |
  | CI_FIX | `ci_fixer` |
  | DIFF_REVIEW | `reviewer` |
  | VERIFY, INTEGRATE | `orange` |

  Priority is the task's. Input is `{"head_sha": …}` or `{"feedback": […]}`. task_event `run_queued {run_id, kind}` or `needs_human {reason}`. An empty `queue` result → None.
- **`react(taskId)`** runs in a transaction: `lockForUpdate`, build the snapshot, decide, act.
- **Wake-ups:**
  - The reactor runs on `task_changed` and `run_changed` (payload task id; a blank payload is ignored) and every `reactorSweep`.
  - The scheduler runs on `run_queued` and every `schedulerPoll`.
  - The reaper sweeps every `reaperSweep`; `failOrphans` runs on `ApplicationReadyEvent`.
  - All of these only run when `loopEnabled`.
- **Scheduler.**
  - `fillSlots` is synchronized and does nothing while paused.
  - For each kind that has an executor and slots > 0: while `inFlight(kind) < slots(kind)` and the total is below `maxConcurrent`, `claimNext(kind, workerId, leaseTtl)`, then `executor.execute(run)` on a virtual thread.
  - In-flight counts are this instance's, decremented when `execute` returns or throws. A throw marks the run FAILED with `start_failed` if it is still running.
  - Plan runs started with `runDirect` don't take a slot.
- **Reaper.**
  - `reapExpired`: each expired lease → `finish(FAILED, "lease_expired")`; failures unchanged.
  - `failOrphans`: each `running` run with a different `worker_id` → kill its `pid` if the process is alive and its start instant is within [started_at − 5 s, started_at + 60 s]. Then `finish(FAILED, "coordinator_restart")`.

- [ ] **Step 1: Write failing tests:**
  - `ReactorDecisionTest`: one test per rule, plus precedence:
    - an active run beats everything;
    - paused beats queueing;
    - failures beat the retry;
    - verify gaps → fix, but review gaps → implement;
    - verifying with both passed → MarkVerified.
  - `TaskReactorIT`:
    - `readyTaskGetsAnImplementRun`: queued run, task RUNNING, `run_queued` event.
    - `concurrentReactsQueueExactlyOneRun`: 8 threads → 1 run.
    - `verifyGapsQueueAFixWithTheFeedback`.
    - `reviewGapsQueueReworkWithTheGaps`.
    - `verifyingRunsVerifyThenDiffReview`.
    - `verifiedGetsAnIntegrateRun`.
    - `thirdFailureMovesToNeedsHuman`.
    - `pausedQueuesNothing`.
  - `RunSchedulerIT` (`@MockitoBean` executors blocking on a latch):
    - `slotsBoundConcurrency`: `slots.implement=2`, 5 queued → 2 running.
    - `integrationsAreSerialized`: integrate slot 1.
    - `kindsWithoutAnExecutorAreNeverClaimed`.
    - `pausedStartsNothing`.
  - `RunReaperIT`:
    - `expiredLeaseFailsWithoutCountingFailures`.
    - `orphansOfAPreviousInstanceAreKilledAndFailed`: a live `sleep 600` started just before the run's `started_at` → dead after `failOrphans`; run FAILED/`coordinator_restart`; then `TaskReactor.react` queues a fresh run.
  - `SchedulerControllerIT`: pause, then GET shows the reason, then resume clears it.
- [ ] **Step 2:** Run the listed classes. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(run): scheduler with per-kind slots, lease reaper and task reactor for the evidence-gated lifecycle`

### Task 9: Events inbox (V18)

**Files:**
- Create: `db/migration/V18__events.sql`, `events/InboxEvent.java`, `events/EventRepository.java`, `events/EventProcessor.java`, `events/EventHandler.java`, `github/GithubEventHandler.java`
- Modify: `webhook/GithubWebhookController` (store and return 200), `listener/PgListenerService` (add `event_received`), `application.yaml` (`orange.events`)
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
  - `interface EventHandler { boolean handles(InboxEvent e); void handle(InboxEvent e); }`
  - `EventProcessor.drain()`: one event per transaction, until none are left.
- **V18:**
  - `events (id BIGSERIAL PK, source TEXT NOT NULL, type TEXT NOT NULL, repo TEXT, delivery_id TEXT UNIQUE, payload JSONB NOT NULL, received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), processed_at TIMESTAMPTZ, attempts INT NOT NULL DEFAULT 0, last_error TEXT)`.
  - `idx_events_unprocessed (id) WHERE processed_at IS NULL`.
  - `AFTER INSERT` → `pg_notify('event_received', NEW.id::text)`.
- **Behaviour (pinned):**
  - **Webhook.** After the HMAC check, store `source=github`, `type` = `X-GitHub-Event`, `delivery_id` = `X-GitHub-Delivery` (or `body-` + SHA-256 of the body if the header is absent), and `repo` = `repository.full_name`. Reply 200 `{"stored": true|false}`. No inline routing.
  - **`GithubEventHandler`** handles `source=github`. It changes no task, because merge-through-webhook belongs to the later PR mode (project-space §11). It logs the PR number and action.
  - **Events with no handler** are marked processed.
  - **A handler that throws** → `markFailed`. After 5 attempts the event is left with its `last_error`.
  - **Wake-ups.** `drain` runs on `event_received` and every `orange.events.poll` (5 s), when `orange.events.processing-enabled` (true; the test config turns it off).

- [ ] **Step 1: Write failing tests:**
  - `GithubWebhookControllerIT`:
    - `signedDeliveryIsStored`.
    - `duplicateDeliveryIsStoredOnce`.
    - `badSignatureIsRejectedAndNothingStored`.
  - `EventProcessorIT` (processing off; calls `drain()`):
    - `eventsAreProcessedInIdOrder`.
    - `pullRequestEventsChangeNoTask`.
    - `failingHandlerIsRetriedFiveTimesThenParked`: a test handler that throws.
    - `unhandledEventsAreMarkedProcessed`.
- [ ] **Step 2:** Run the two classes. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(events): webhook inbox with idempotent, ordered processing`

### Task 10: Operator API: runs, evidence, live view, health, cancel

**Files:**
- Create: `api/RunController.java`, `api/RunResponse.java`, `api/RunEventResponse.java`, `api/EvidenceResponse.java`
- Modify: `api/StatusController` (`/events/live`, `/health/aggregate`), `api/TaskController` (cancel; `GET /tasks/{id}/evidence`), `config/MetricsConfig` (gauges per status and kind), `orange-mcp/src/orange_mcp/server.py` (`tail` shows the latest run's events after the task events; `status`/`health` use the new fields)
- Test: `api/RunApiIT.java`, `api/StatusControllerIT`, `api/TaskApiIT` (extend)

**Interfaces:**
- Produces:
  - `GET /tasks/{id}/runs` → `List<RunResponse>`, newest first. Fields: id, kind, status, role, attempt, tier, model, effort, auth, cost_usd, error, queued_at, started_at, finished_at, outcome. Never the token hash.
  - `GET /runs/{id}` → `RunResponse`.
  - `GET /runs/{id}/events?since=0&limit=100` → `List<RunEventResponse(seq, type, payload, created_at)>`.
  - `GET /tasks/{id}/evidence` → `List<EvidenceResponse(id, head_sha, kind, command, exit_code, output_tail, passed, run_id, created_at)>`, newest first.
  - `GET /events/live` → one row per running run: `{run_id, task_id, task_title, kind, model, started_at, recent: [last N run events]}`.
  - `GET /health/aggregate` →
    - `queues`: counts for ready, running, verifying, verified, needs_human;
    - `runs`: per kind `{queued, running, slots}`;
    - `paused`;
    - `cost`: `{total_usd, last_24h_usd}` from `runs`;
    - `envs`.
  - `POST /tasks/{id}/cancel`: `cancelQueued`, `WorkerRunner.cancel`, then any non-terminal → CANCELLED. A terminal task → 409.

- [ ] **Step 1: Write failing tests:**
  - `RunApiIT`: `taskRunsAreListedNewestFirst`, `runEventsPageBySeq`, `tokenHashIsNotExposed`, `evidenceIsListedNewestFirst`.
  - `StatusControllerIT`: `liveShowsRunningRuns`, `healthCountsRunsAndPause`.
  - `TaskApiIT`: `cancelCancelsQueuedRuns`, `cancelOfIntegratedTaskIs409`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run `./gradlew test`. Expected: PASS.
- [ ] **Step 5: Commit.** `feat(api): runs, run events and evidence endpoints; live view and health from runs; cancel cancels runs`

### Task 11: Gateway hygiene (timeouts, spend sync, key deletion) and run-dir retention

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

### Task 12: End-to-end run loop, docs, live checks, PR

**Files:**
- Create: `orange/src/test/java/com/ai/orange/e2e/RunLoopE2EIT.java`
- Modify:
  - `CLAUDE.md`: architecture (run loop, reactor, verification, integration, MCP, inbox), config knobs, and that tests need no Temporal;
  - `orange-mcp/README.md`, `plugin/skills/orange-orchestrate/*`;
  - `.env.example` (`ORANGE_MCP_WORKER_URL`, `WORKTREES_ROOT`).

**Interfaces:**
- Consumes: everything above. `orange.runs.loop-enabled=true` and `orange.events.processing-enabled=true`.

- [ ] **Step 1: Write the e2e tests.** Setup: `RANDOM_PORT`, fake claude, a temp base repo with a bare origin, Awaitility with a 60 s cap. The graph is A → B.
  - `readyToIntegratedPromotesDependents`: evidence command `test -f orange-fake.txt`.
    1. A goes `running` → `verifying`.
    2. The verify evidence passes; the diff-review evidence passes.
    3. A is `verified`, then `integrated`. Origin `main` is at A's head, which contains A's commit.
    4. B leaves `pending`.
    5. A's task_events include, in order: `run_queued`, `run_started`, `run_finished`, `verification_started`, `verified`, `integrated`.
  - `failingEvidenceIsFixedThenIntegrated`: evidence command `test -f fixed.txt`.
    1. The first verify has a gap, so a CI_FIX run is queued with the gap as feedback.
    2. The fake's fix run creates `fixed.txt`.
    3. Re-verification passes, and A is integrated with `failures` = 1.
- [ ] **Step 2:** Run `./gradlew test --tests '*RunLoopE2EIT'`. Expected: PASS. If it fails, fix the code it exposes; don't weaken the test.
- [ ] **Step 3: Docs.** CLAUDE.md, the plugin skill, the orange-mcp README and `.env.example`.
- [ ] **Step 4:** Run the full suite and the live checks.
  - `./gradlew test` → green.
  - `./gradlew liveTest --tests '*ClaudeCliContractLiveIT' --tests '*WorkerMcpRealClaudeLiveIT'` → green.
  - `SubscriptionSmokeLiveIT` only if `CLAUDE_CODE_OAUTH_TOKEN` is in `orange/.env`.
- [ ] **Step 5: Commit, push and open the PR** against `orange-e2e-base`. The body covers what changed, what was verified (and how), what wasn't, and the deferred items, including the PR mode and rebase sweep left to later phases.
