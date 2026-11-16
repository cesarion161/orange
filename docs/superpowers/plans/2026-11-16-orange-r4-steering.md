# R4 · The steering tab drives tasks — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** from one Claude Code tab the user creates a project, hands it a goal and follows it. orange plans the work and decides whether to ask first. Stuck tasks reach the user, and "are we on track" is answered from facts.

**Architecture:**
- **The task tools move into `/mcp/steering`** (`SteeringTools`). The Python `orange-mcp` shim, the repo's `.mcp.json` and the old slash commands are deleted.
- **A plan is a row** (`plans`, V24). The planner runs in the background (`PlanService`, started directly like today) on a read-only clone of the project's repo, with the project's facts in its prompt. orange's own rule (`PlanApproval`, pure) then decides:
  - dispatch at once;
  - or file one urgent `plan_approval` item, which the user resolves with approve, drop or a note that revises the plan.
- **Resolving acts.** `AttentionService.resolve` hands plan and task items to an `AttentionResolver` (plans: dispatch, drop or re-plan; tasks: reopen with the answer as the note) before it marks them resolved.
- **A task that needs a person files attention** (`TaskAttention`), urgent. Its branch is pushed as `orange/task/<short id>` so the user can look at it from the laptop, and the branch is deleted once the task is integrated or cancelled (builder's call 13).
- **Milestones get tasks.** Tasks remember the plan and the milestone they serve. `MilestoneTracks` reads each milestone's tasks through its supersede chain, and `OnTrack` (pure) labels a dated milestone `on_track` or `behind`. The brief, `orange_status` and the tab's start context all read from it.
- **A steering session** is a Claude Code session started with `ORANGE_STEERING=1` or inside the steering directory. The reporter marks its events, and orange records the session but never judges it, files nothing about it and maps it to no project. At SessionStart the reporter prints a few lines of orange's status as context.

**Tech Stack:** Java 27, Spring Boot 4, jOOQ + Flyway (Postgres 18), the MCP Java SDK (stateless steering server), Python 3 (reporter), JUnit 5 + Testcontainers, FakeClaude. Local bare repos stand in for GitHub, and a JDK `HttpServer` stands in for GitHub's REST API.

**Spec:** `docs/superpowers/specs/2026-11-12-orange-remote-first-design.md`: §7.1 (`orange_project_create`), §7.2, §7.5, §7.6, §8 (R4), §9, builder's calls 8, 12 and 13.

## Global Constraints

- **Migrations:** add `V24__steering.sql`; merged migrations are immutable. Follow the existing patterns: `uuidv7()` PKs, the `touch_updated_at()` trigger, partial indexes on hot statuses.
- **The PAT** goes only into orange's own git (through `Mirrors`/`GitAuth`) and, for creating a repo, into one HTTPS request's `Authorization` header. It never appears in an argv, a URL, a log line, an exception message, a DB row or a container.
- **The subscription token** goes only into the `claude` child's environment (unchanged).
- **Only the user puts evidence in force** (spec §7.1, builder's call 12). The planner may propose commands. They become the `evidence` convention only when the user approves the plan that carries them (author `user`).
- **"No evidence commands, no integration" is unchanged.**
- **Steering handlers never throw.** A bad call comes back as an error result that says what to correct (the `spec(...)` wrapper).
- **The repo is public:** no IPs, hostnames, tailnet names, tokens or device names anywhere.
- **Tests never call a model** (FakeClaude) **and never touch the network.**

## Rulings in this plan (builder's calls, flagged for the user)

1. **Risk stays a boolean.** The spec's `risk: low | high` is the plan's existing `risk: true|false`, plus a new `risk_reason`.
2. **No evidence in force means the plan asks, whatever `plan-approval` says.** Approving it is how the user puts the commands in force (call 12). A `never` plan without evidence would only wait in `verifying`.
3. **The planner sees the repo.** It gets a clone of `origin/<base>` at `repo/` in its directory, with its existing tools (Read, Write, Glob, Grep, WebFetch; no Bash). orange runs no git there after the clone, and Claude Code loads settings only from the directory it starts in, which has none.
4. **`orange_plan` returns at once** with the plan's id and `planning`. The plan then dispatches or asks within minutes, and `orange_status` shows where it is. A paused scheduler refuses the call.
5. **The size threshold:** a plan with more than `orange.planner.ask-above-tasks` (default 6) tasks asks first.
6. **The on-track margin** is `orange.brief.on-track-margin`, default 0.20. A dated milestone with no tasks gets no label.
7. **No code moves a task to `failed` today,** so only `needs_human` files task attention. That covers the reactor's give-up, a blocked report, `request_human` and a failed integration. The `task_failed` kind is not added until something sets that status.
8. **The steering marker** is `ORANGE_STEERING=1` in Claude Code's environment, or a session whose cwd is under `ORANGE_STEERING_DIR` (default `~/.orange/steering`). The plugin README suggests `alias orange='mkdir -p ~/.orange/steering && cd ~/.orange/steering && claude'`.
9. **Task ids** are accepted as a full UUID or an unambiguous prefix of at least 8 hex characters, as the old shim did.

## Review Focus

1. **The user resolves an approval item twice, or resolves it while it is being re-planned.** Approve dispatches the graph exactly once: the plan row moves `asking → dispatched` under a row lock, and a second resolve of a resolved item is a no-op. Test: `PlanFlowIT.approvingTwiceDispatchesOnce`.
2. **orange restarts while a plan is `planning`.** The plan must not stay `planning` forever. At startup it becomes `failed` ("coordinator restarted") and files a normal `plan_failed` item. Test: `PlanFlowIT.aPlanLeftPlanningByARestartFails`.
3. **A milestone is edited (superseded) after tasks were linked to it.** The tasks still count for the current milestone, and not for a dropped one. Test: `MilestoneTracksIT.tasksFollowTheSupersedeChain`.
4. **A stuck task is reopened over REST, or cancelled, instead of answered.** Its open attention item is resolved, so no stale item waits for an answer. Test: `TaskAttentionIT.reopeningOverRestResolvesTheItem`.
5. **The reporter at SessionStart of a steering session while orange is down.** The hook still exits 0 within its budget, prints nothing and queues the event as before. Test: `SessionReporterIT.steeringStartWithOrangeDownPrintsNothing`.

---

### Task 1: Schema and domain plumbing

**Files:**
- Create: `orange/src/main/resources/db/migration/V24__steering.sql`, `orange/src/main/java/com/ai/orange/planner/Plan.java`, `orange/src/main/java/com/ai/orange/planner/PlanRepository.java`
- Modify: `task/Task.java`, `task/TaskDef.java`, `task/TaskRepository.java` (insert, mapping, `findByIdPrefix`), `session/Session.java`, `session/SessionRepository.java`, `attention/AttentionItem.java`, `attention/AttentionRepository.java`
- Test: `orange/src/test/java/com/ai/orange/planner/PlanRepositoryIT.java`, `orange/src/test/java/com/ai/orange/task/TaskRepositoryIT.java` (extend, or create when absent)

**Interfaces:**
- **V24 adds:**
  - the table `plans`:
    - `id uuid pk default uuidv7()`;
    - `project_id uuid not null references projects`;
    - `request text not null`, `note text` (the revision note);
    - `revises_id uuid references plans`;
    - `status text not null check in ('planning','asking','dispatched','dropped','failed')`;
    - `ask_first boolean`, `reason text`, `plan jsonb`, `architecture text`, `failure text`;
    - `milestone_id uuid references space_items on delete set null`;
    - `run_id uuid references runs on delete set null`;
    - `attention_id uuid`;
    - `created_at` and `updated_at`, with the touch trigger;
    - a partial index on `(status)` where status in ('planning','asking');
  - `tasks.plan_id uuid references plans on delete set null` and `tasks.milestone_id uuid references space_items on delete set null`, with an index on `milestone_id`;
  - `sessions.steering boolean not null default false`.
- **`record Plan`** holds every column. Its status constants are `PLANNING`, `ASKING`, `DISPATCHED`, `DROPPED` and `FAILED`.
- **`PlanRepository` methods:**
  - `Plan insert(UUID projectId, String request, String note, UUID revisesId, UUID milestoneId)`;
  - `Optional<Plan> findById(UUID)` and `Optional<Plan> lock(UUID)` (`FOR UPDATE`);
  - `List<Plan> findByStatus(Collection<String>)` and `List<Plan> findRecent(UUID projectId, int limit)`;
  - `void finishPlanning(UUID id, String status, Boolean askFirst, String reason, JSONB plan, String architecture, String failure, UUID runId, UUID milestoneId)`;
  - `boolean move(UUID id, String from, String to)` and `void setAttention(UUID id, UUID attentionId)`.
- **`TaskDef`** gains `UUID planId, UUID milestoneId`, with a constructor that keeps today's arguments and sets both to null. `Task` gains `planId()` and `milestoneId()`.
- **`TaskRepository.findByIdPrefix(String prefix)`** returns `List<Task>`, at most 2 rows.
- **`Session`** gains `boolean steering`. `SessionRepository.save` writes it.
- **New `AttentionItem` kinds:** `PLAN_APPROVAL = "plan_approval"`, `PLAN_FAILED = "plan_failed"`, `TASK_NEEDS_HUMAN = "task_needs_human"`.
- **`AttentionRepository`:**
  - the existing `insert` already takes `taskId`; `AttentionService.raise` gains an overload with `UUID taskId`;
  - add `int resolveForTask(UUID taskId, String by, String resolution)`, which resolves every unresolved item of the task.

- [ ] **Step 1:** Write `PlanRepositoryIT`:
  - `insertsAPlanningPlanAndFinishesIt`;
  - `moveOnlyFromTheExpectedStatus`;
  - `findByStatusSeesPlanningAndAsking`.

  In `TaskRepositoryIT`:
  - `insertKeepsPlanAndMilestone`;
  - `findByIdPrefixNeedsEightCharactersAndFindsOne`, where a 7-character prefix is refused with `IllegalArgumentException`.

  Run `./gradlew test --tests '*PlanRepositoryIT' --tests '*TaskRepositoryIT'`. Expected: compilation FAIL.
- [ ] **Step 2:** Write V24, the records and the repositories. `./gradlew generateJooq` runs before `compileJava` anyway.
- [ ] **Step 3:** Run the same tests, then the whole suite with `./gradlew test`. Expected: PASS, and no other test broke from the new `TaskDef` and `Session` fields.
- [ ] **Step 4:** Commit: `feat(db): plans, task plan and milestone links, steering sessions (V24)`.

### Task 2: The plan-approval rule

**Files:**
- Create: `orange/src/main/java/com/ai/orange/planner/PlanApproval.java`
- Modify:
  - `planner/PlannerProperties.java`: add `@DefaultValue("6") int askAboveTasks`;
  - `application.yaml`: `orange.planner.ask-above-tasks: 6`, and `orange.conventions.plan-approval: auto` with a comment;
  - `mcp/SteeringTools.java`: list `plan-approval` among the keys in `orange_set`'s description.
- Test: `orange/src/test/java/com/ai/orange/planner/PlanApprovalTest.java`

**Interfaces:**
- `final class PlanApproval` exposes `static Decision decide(String convention, boolean evidenceInForce, PlanJson plan, int askAboveTasks)`.
- `record Decision(boolean ask, String reason)`. The reason is one line, shown to the user, and never null when `ask` is true.
- The rules, first match wins:
  1. `!evidenceInForce` asks with `"no tests in force yet"`.
  2. Convention `always` asks with `"plan-approval is always"`.
  3. Convention `never` dispatches.
  4. Otherwise (`auto`, blank, or unknown, which is treated as auto):
     - `plan.askFirst()` asks with the planner's reason, or `"the planner asks first"` when it gave none;
     - more than `askAboveTasks` tasks asks with `"<n> tasks (more than <m>)"`;
     - the first task with `risk == true` asks with `"<title> is risky: <risk_reason or 'flagged by the planner'>"`;
     - else it dispatches with reason `null`.

- [ ] **Step 1:** Write `PlanApprovalTest`:
  - `noEvidenceAlwaysAsksEvenUnderNever`;
  - `alwaysAsks`;
  - `neverDispatchesARiskyPlan`;
  - `plannerAskFirstCarriesItsReason`;
  - `aLargePlanAsks` (7 tasks against 6);
  - `aRiskyTaskAsksWithItsReason`;
  - `aSmallSafePlanDispatches`;
  - `anUnknownConventionIsAuto`.

  This task adds the `PlanJson` fields the rule reads (`ask_first`, the plan's `reason`, a task's `risk_reason`); Task 3 adds `milestone` and `evidence`.

  Run it. Expected: FAIL (no class).
- [ ] **Step 2:** Implement the rule and the fields.
- [ ] **Step 3:** Run it. Expected: PASS.
- [ ] **Step 4:** Commit: `feat(planner): plan approval by orange's judgement`.

### Task 3: What the planner is told and what it writes

**Files:**
- Create: `orange/src/main/java/com/ai/orange/planner/PlannerPrompt.java`
- Modify:
  - `planner/PlanJson.java`, which gains (beyond Task 2's `ask_first`, `reason`, `risk_reason`):
    - `String milestone`, the id of a milestone from the prompt;
    - `List<String> evidence`, the proposed commands;
    - keep every existing constructor;
  - `planner/PlannerService.java`: add `PlanResult plan(Path outputDir, String prompt)`, which runs in a directory the caller prepared. The old `plan(String)` delegates to it;
  - `resources/roles/planner.md`;
  - `test/resources/fake-claude/FakeClaude.java`.
- Test: `orange/src/test/java/com/ai/orange/planner/PlannerPromptTest.java`, `orange/src/test/java/com/ai/orange/planner/PlannerServiceIT.java` (extend)

**Interfaces:**
- `PlannerPrompt` exposes `static String build(String request, String note, Facts facts)`.
- `record Facts(String project, List<String> repos, String baseBranch, List<String> goals, List<Milestone> milestones, List<String> decisions, List<String> notes, List<String> evidence, String review, boolean repoCloned)`, with `record Milestone(String id, String text, String due)`.
- The prompt:
  - starts with the project's facts, including `evidence in force: none` when there is none, and `The repository is checked out read-only at repo/` when `repoCloned`;
  - then gives the user's request;
  - then gives the revision note, under "The user asked for this revision of an earlier plan:", when there is one.

  The request and the note are the user's own words, so they need no fence. Decisions and notes are user or accepted items.
- `planner.md` adds the new fields to the schema and says when to set each one:
  - `ask_first` and `reason`: the four cases of spec §7.2, in one plain line each;
  - `risk_reason`, whenever `risk` is true;
  - `milestone`: only an id from the list, when the request serves it;
  - `evidence`: only when the prompt says none is in force. These are commands that run the project's tests, usually something the first task sets up.

  It also says to read `repo/` before planning when it exists, and never to write inside it.
- **FakeClaude's plan scenario:**
  - `[[fake:plan]]` keeps writing a small 1-task plan with `ask_first: false`;
  - `[[fake:plan-ask]]` writes `ask_first: true, reason: "the request is ambiguous"`;
  - `[[fake:plan-risky]]` writes one task with `risk: true, risk_reason: "touches the migrations"`;
  - `[[fake:plan-evidence]]` adds `evidence: ["sh test.sh"]`;
  - `[[fake:plan-milestone:<id>]]` sets `milestone` to `<id>`;
  - markers combine;
  - the fake also writes `fake-probe.txt` listing whether `repo/` exists, so a test can see the clone.

- [ ] **Step 1:** Tests:
  - `PlannerPromptTest`:
    - `factsComeBeforeTheRequest`;
    - `saysWhenNoEvidenceIsInForce`;
    - `listsMilestonesWithTheirIds`;
    - `carriesTheRevisionNote`;
  - in `PlannerServiceIT`, `parsesAskFirstRiskReasonMilestoneAndEvidence`, with `[[fake:plan-ask]] [[fake:plan-risky]] [[fake:plan-evidence]]`.

  Run them. Expected: FAIL.
- [ ] **Step 2:** Implement the prompt, the `PlanJson` fields, `plan(Path, String)`, the role text and the fake's scenarios.
- [ ] **Step 3:** Run `./gradlew test --tests '*Planner*' --tests '*Dispatch*'`. Expected: PASS, and the existing planner and dispatch tests stay green.
- [ ] **Step 4:** Commit: `feat(planner): project facts in, ask_first, risk reasons, milestone and evidence out`.

### Task 4: Plans that dispatch or ask

**Files:**
- Create:
  - `orange/src/main/java/com/ai/orange/planner/PlanService.java`;
  - `orange/src/main/java/com/ai/orange/attention/AttentionResolver.java`;
  - `orange/src/main/java/com/ai/orange/planner/PlanText.java`, the item's body: one line per task with its risk mark, the edges, the reason and the proposed evidence.
- Modify:
  - `attention/AttentionService.java`: in `resolve`, before `items.resolve`, the resolver whose `kinds()` contains the item's kind gets `onResolve`. Resolvers come through `ObjectProvider<AttentionResolver>`, which breaks the cycle;
  - `planner/PlanGraphService.java`: `ingest(PlanJson, String project, String repo, UUID planId, UUID milestoneId)` sets both links on every `TaskDef`;
  - `dispatch/DispatchService.java`, only if it needs the new overload.
- Test: `orange/src/test/java/com/ai/orange/planner/PlanFlowIT.java`, `orange/src/test/java/com/ai/orange/planner/PlanTextTest.java`

**Interfaces:**
- `interface AttentionResolver { Set<String> kinds(); void onResolve(AttentionItem item, String answer); }`. It throws `IllegalArgumentException` to refuse an answer, and then nothing is resolved.
- `PlanService implements AttentionResolver`, with kinds `plan_approval` and `plan_failed`:
  - `Plan start(String projectRef, String request, String milestoneRef)`. It throws `IllegalArgumentException` when:
    - the project is unknown or not `active`;
    - the project has no GitHub repo;
    - the request is blank;
    - the milestone is not an active milestone of that project;
    - the scheduler is paused (the message says so).

    It inserts the row and runs `plan(id)` on a virtual thread.
  - `void plan(UUID planId)` (package-private, for tests to call synchronously):
    1. Fetch the mirror and clone `refs/remotes/origin/<base>` into `<outputRoot>/<planId>/repo`. A failed clone plans without it (`repoCloned=false`) and logs a warning.
    2. Build `PlannerPrompt.Facts` from `SpaceService.current`, `Conventions` and the repos.
    3. Call `PlannerService.plan(dir, prompt)`.
    4. If the run did not succeed, the plan becomes `failed`, with the planner's status as `failure`, and files a normal `plan_failed` item (dedupe `plan_failed:<id>`, title `Plan for <project> failed: <status>`).
    5. Otherwise it records the plan. The milestone is the request's milestone, or else the planner's when that id is an active milestone of the project, or else none.
    6. Then `PlanApproval.decide(...)` with `plan-approval` from `Conventions.resolve(..., projectId, null)` and whether `evidence` is in force:
       - **dispatch:** `ingest`, then `move(asking|planning → dispatched)`;
       - **ask:** `move(planning → asking)`, then file an urgent `plan_approval` item (dedupe `plan_approval:<id>`, title `Approve the plan for <project>? <reason>`, body `PlanText.body`, proposed answer `approve`), then `setAttention`.
  - `onResolve`, for `plan_approval`, on the locked plan row:
    - `approve`, `yes` or `ok`:
      - if the plan carries `evidence` and none is in force, call `space.setConvention(projectId, null, "evidence", <array>, Author.USER)`;
      - then ingest and set the plan `dispatched`;
    - `drop` or `no` sets the plan `dropped`;
    - any other non-blank text starts a new plan with the same request, `note = answer` and `revises_id`. The old one becomes `dropped`;
    - a blank answer throws `"answer approve, drop, or what to change"`;
    - a plan that is not `asking` resolves the item with no other effect.
  - `onResolve`, for `plan_failed`, does nothing.
  - `@EventListener(ApplicationReadyEvent.class) void failLeftovers()`: every `planning` plan becomes `failed`, with failure `"coordinator restarted"` and a `plan_failed` item.

- [ ] **Step 1:** Write `PlanFlowIT`. It uses Testcontainers Postgres, FakeClaude and a local bare repo as GitHub, with a project that has a repo and `sh test.sh` evidence in force unless a test says otherwise:
  - `aSmallSafePlanDispatchesOnItsOwn`: tasks exist with `plan_id`, and no item is filed;
  - `aRiskyPlanAsksFirstAndApproveDispatches`: an urgent item with the reason, then approve, then tasks exist;
  - `approvingTwiceDispatchesOnce`;
  - `dropLeavesNoTasks`;
  - `aNoteRevisesThePlan`: a new plan with `revises_id`, whose prompt carries the note;
  - `withoutEvidenceItAsksAndApprovePutsTheProposedCommandsInForce`: the convention's author is `user`;
  - `theRequestsMilestoneLinksEveryTask`;
  - `aPlannersUnknownMilestoneIsIgnored`;
  - `aPlanLeftPlanningByARestartFails`;
  - `aPausedSchedulerRefusesToPlan`;
  - `thePlannerSeesTheRepo`, through the fake's probe file.

  `PlanTextTest`: `listsTasksRiskAndProposedEvidence`.

  Run them. Expected: FAIL.
- [ ] **Step 2:** Implement `AttentionResolver`, the `AttentionService` seam, `PlanService`, `PlanText` and the ingest overload.
- [ ] **Step 3:** Run the new tests, then `./gradlew test --tests '*Attention*' --tests '*Project*'`. Expected: PASS. Resolving a proposed project still works.
- [ ] **Step 4:** Commit: `feat(planner): plans dispatch on their own or ask first`.

### Task 5: Stuck tasks become attention

**Files:**
- Create: `orange/src/main/java/com/ai/orange/task/TaskAttention.java`
- Modify:
  - `run/PostRunSteps.java`: `needsHuman(...)` calls `TaskAttention.stuck` after its event;
  - `run/TaskReactor.java`: the `NeedsHuman` action calls it too;
  - `task/TaskService.java`: `reopenTask` resolves the task's items, `resolveForTask(id, actor, "reopened")`;
  - `run/TaskCancellation.java`: cancelling resolves them with `"cancelled"`.
- Test: `orange/src/test/java/com/ai/orange/task/TaskAttentionIT.java`

**Interfaces:**
- `TaskAttention implements AttentionResolver`, with kinds `task_needs_human`.
- `void stuck(Task task, String why, long eventId)` files an urgent item:
  - dedupe `task_needs_human:<taskId>:<eventId>`, so a task stuck again after a reopen files a new one;
  - title `<task title> needs you`;
  - body: the project, the question or reason, the task's last 3 events in one line each, and the pushed branch when Task 6 pushed one;
  - `projectId` and `taskId` set.
- `onResolve`:
  - a non-blank answer, with the task still `needs_human`, calls `TaskService.reopenTask(taskId, answer, "user")`;
  - otherwise the item just resolves.
- `PostRunSteps.needsHuman` and the reactor's `NeedsHuman` need the event id: `TaskEventRepository.append` already returns `long`.

- [ ] **Step 1:** Write `TaskAttentionIT`:
  - `aBlockedReportFilesAnUrgentItem` (`[[fake:blocked]]`), whose body carries the question;
  - `requestHumanFilesAnUrgentItem`;
  - `tooManyFailuresFileAnItem`;
  - `resolvingWithAnAnswerReopensTheTaskWithThatNote`, where the next implement prompt carries the note;
  - `resolvingBlankDismisses`;
  - `reopeningOverRestResolvesTheItem`;
  - `cancellingResolvesTheItem`;
  - `stuckAgainAfterAReopenFilesANewItem`.

  Run it. Expected: FAIL.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run it, then `./gradlew test --tests '*RunLoop*' --tests '*TaskReactor*'`. Expected: PASS.
- [ ] **Step 4:** Commit: `feat(attention): a task that needs a person asks for one`.

### Task 6: A stuck task's branch on GitHub (builder's call 13)

**Files:**
- Create: `orange/src/main/java/com/ai/orange/task/StuckBranches.java`
- Modify:
  - `task/TaskAttention.java`: push before filing, and name the branch in the body;
  - `run/PostRunSteps.java`: after `integrated`, delete the branch;
  - `run/TaskCancellation.java`: delete it on cancel.
- Test: `orange/src/test/java/com/ai/orange/task/StuckBranchesIT.java`

**Interfaces:**
- `Optional<String> push(Task task)`:
  - only for a task of a repo whose project has `remote-push` on and whose branch has commits;
  - pushes `orange/<taskId>` to `refs/heads/orange/task/<first 8 hex>` through `Mirrors.remote(key)` from the task's worktree;
  - records a task event `branch_pushed` with the branch;
  - returns the branch name. Any failure logs a warning and returns empty; it never fails the caller.
- `void delete(Task task)`: only when a `branch_pushed` event exists, push `:refs/heads/orange/task/<short>`; failures are logged.

- [ ] **Step 1:** Write `StuckBranchesIT`, with local bare repos as GitHub:
  - `aStuckTaskBranchIsPushedAndNamedInItsItem`;
  - `integratingDeletesIt`;
  - `cancellingDeletesIt`;
  - `noRemotePushNoBranch`;
  - `aFailedPushStillFilesTheItem`.

  Run it. Expected: FAIL.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run it. Expected: PASS.
- [ ] **Step 4:** Commit: `feat(repo): a stuck task's branch is pushed for the user to look at`.

### Task 7: Milestones know their tasks; on track or behind

**Files:**
- Create: `orange/src/main/java/com/ai/orange/brief/OnTrack.java`, `orange/src/main/java/com/ai/orange/brief/MilestoneTracks.java`
- Modify:
  - `brief/BriefSnapshot.java`:
    - `Milestone` gains `String id, Map<String,Integer> tasks, String track`;
    - `ProjectFacts` gains `TaskFacts tasks`;
    - compact constructors default the new fields, so stored snapshots still parse;
    - `hasNews` counts project task facts;
  - `brief/BriefSnapshots.java`:
    - milestones come from `MilestoneTracks`;
    - task facts are read per project;
    - the top-level `tasks` keeps only tasks of no project;
  - `brief/BriefText.java` and `brief/BriefPrompt.java`: print a milestone's tasks and its label, and each project's tasks under that project;
  - `brief/BriefProperties.java`, with `application.yaml`: `@DefaultValue("0.20") double onTrackMargin`.
- Test:
  - `orange/src/test/java/com/ai/orange/brief/OnTrackTest.java`;
  - `orange/src/test/java/com/ai/orange/brief/MilestoneTracksIT.java`;
  - extend `BriefSnapshotTest`, `BriefTextTest` and `BriefSnapshotIT`.

**Interfaces:**
- **`OnTrack`:**
  - `static final String ON_TRACK = "on_track"`, `BEHIND = "behind"`;
  - `static String label(Integer elapsedPercent, int merged, int total, int stuck, double margin)`:
    - null when `elapsedPercent == null` or `total == 0`;
    - `behind` when `stuck > 0` or when `merged/total < elapsedPercent/100 - margin`;
    - else `on_track`.
- **Task states for a milestone:**
  - `merged` = integrated;
  - `stuck` = needs_human or failed;
  - `running` = running, verifying or verified;
  - `not_started` = pending or ready;
  - cancelled tasks are left out.
- **`MilestoneTracks`:**
  - `Map<UUID, List<Milestone>> read(Collection<UUID> projectIds, OffsetDateTime at, LocalDate today, ZoneId zone, double margin)`. It does what `BriefSnapshots.milestones` does today, plus each milestone's tasks counted over its ancestors' ids. The ancestors come by walking `supersedes_id` from the current item back, in SQL (a recursive CTE) or in Java over the project's items.
  - `Optional<UUID> current(UUID milestoneId)` follows the chain forward to the active item.

- [ ] **Step 1:** Tests:
  - `OnTrackTest`:
    - `noDateNoLabel`;
    - `noTasksNoLabel`;
    - `aStuckTaskIsBehind`;
    - `trailingByMoreThanTheMarginIsBehind` (25% merged at 50% elapsed with margin 0.2);
    - `withinTheMarginIsOnTrack` (40% at 50%);
  - `MilestoneTracksIT`:
    - `countsTasksByState`;
    - `tasksFollowTheSupersedeChain`;
    - `aDroppedMilestonesTasksCountNowhere`;
  - `BriefSnapshotIT`:
    - `projectTasksAppearUnderTheirProject`;
    - `aStoredSnapshotWithoutTheNewFieldsStillParses`;
  - `BriefTextTest`: `printsTheMilestoneLabelAndTasks`.

  Run them. Expected: FAIL.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run `./gradlew test --tests '*Brief*' --tests '*OnTrack*' --tests '*MilestoneTracks*'`. Expected: PASS.
- [ ] **Step 4:** Commit: `feat(brief): milestones know their tasks; on track or behind is a fact`.

### Task 8: Status, and the tab's start context

**Files:**
- Create: `orange/src/main/java/com/ai/orange/status/StatusService.java`, `orange/src/main/java/com/ai/orange/status/StatusReport.java`, `orange/src/main/java/com/ai/orange/status/StatusText.java`, `orange/src/main/java/com/ai/orange/api/SteeringController.java`
- Modify: `config/SecurityConfig.java` (`/steering/**` needs the API token)
- Test: `orange/src/test/java/com/ai/orange/status/StatusServiceIT.java`, `orange/src/test/java/com/ai/orange/status/StatusTextTest.java`, `orange/src/test/java/com/ai/orange/config/SecurityConfigIT.java` (extend)

**Interfaces:**
- **`StatusReport`** has `(String at, Pause pause, List<ProjectStatus> projects, int openAttention, int urgentAttention, int runningRuns, List<TaskLine> unplaced)`.
- **`ProjectStatus`** has `(String id, String name, List<PlanLine> plans, Map<String,Integer> tasksByState, List<TaskLine> tasks, int integratedToday, List<Milestone> milestones, int openAttention)`.
  - `plans` holds the `planning` and `asking` plans;
  - `tasks` holds the tasks that are not finished (at most 10, stuck first), each `(id8, title, status, milestone text)`;
  - `milestones` comes from `MilestoneTracks`.
- **`StatusService.report()`** reads the active projects.
- **`StatusText`:**
  - `static String text(StatusReport)`, the full text for `orange_status`;
  - `static String startContext(StatusReport)`, at most 8 lines:
    - a pause line, if paused;
    - one line per active project, e.g. `orange-sandbox: 2 running, 1 needs you, milestone "beta" behind`;
    - a line for what waits for the user, or "nothing waits for you";
    - the hint `Ask "what's the status" or hand over work: "build X in <project>".`
- **`GET /steering/context`** answers `text/plain` with `startContext`.

- [ ] **Step 1:** Tests:
  - `StatusServiceIT`:
    - `groupsTasksPlansAndMilestonesByProject`;
    - `stuckTasksComeFirst`;
    - `aPausedSchedulerShows`;
  - `StatusTextTest`:
    - `startContextStaysShort` (15 projects, still at most 8 lines);
    - `startContextSaysNothingWaitsWhenNothingDoes`;
  - in `SecurityConfigIT`, `steeringContextNeedsTheToken`.

  Run them. Expected: FAIL.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run them. Expected: PASS.
- [ ] **Step 4:** Commit: `feat(status): what is in flight, by project, and the tab's start context`.

### Task 9: The task tools in `/mcp/steering`

**Files:**
- Create: `orange/src/main/java/com/ai/orange/repo/GithubRepos.java`, which creates a repo through the REST API.
- Modify:
  - `mcp/SteeringTools.java` and `mcp/SteeringMcpConfig.java` (constructor wiring);
  - `repo/RepoProperties.java`: `@DefaultValue("https://api.github.com") String githubApiUrl`;
  - `application.yaml` and `orange/.env.example` (`ORANGE_GITHUB_API_URL`, blank for GitHub).
- Test: `orange/src/test/java/com/ai/orange/dispatch/SteeringMcpIT.java` (extend), `orange/src/test/java/com/ai/orange/repo/GithubReposTest.java`

**Interfaces:** each tool follows the existing `spec(...)` pattern, and the descriptions say when to use the tool in the user's words.
- **`orange_plan {project, request, milestone?}`** calls `PlanService.start`. It returns `{plan_id, status: "planning", hint: "orange plans this in the background; it dispatches on its own or asks you first. orange_status shows where it is."}`.
- **`orange_status {}`** returns `StatusText.text(report)` plus the report as JSON.
- **`orange_task {id}`** takes a task id or prefix and returns:
  - the task, with its project, milestone and plan;
  - its runs (kind, status, reason, cost);
  - its last 15 events;
  - its evidence (check, pass);
  - its open attention items.

  An ambiguous prefix gets the error `"ambiguous id: <n> tasks start with <prefix>"`.
- **`orange_tail {id, limit?}`** returns the latest `limit` events (default 20, max 100) of the task's running run, or of its latest run, as compact lines (`type`, a short text from the payload).
- **`orange_cancel {id}`** calls `TaskCancellation.cancel`.
- **`orange_pause {reason?}`** calls `SchedulerPause.pause("user: <reason>", null)`. **`orange_resume {}`** calls `resume()`. Both return the scheduler state.
- **`orange_project_create {name, repo?, create?, evidence?, summary?}`:**
  - `repo` (`owner/name`) is checked by `Mirrors.ensure` (the clone) before the project is created, so an inaccessible repo creates nothing;
  - with `create: true`, `repo` may be a bare name when exactly one owner has a token. `GithubRepos.create(owner, name)` runs first, then the clone;
  - the project is `active`, with no proposal item;
  - `evidence` (an array of commands) is set as the user's convention;
  - the result is the project as `orange_project` shows it.
- **`GithubRepos`:**
  - `RepoKey create(String owner, String name)` makes a `POST {api}/user/repos` request when `owner` is that login, else `POST {api}/orgs/{owner}/repos`, with body `{"name", "private": true, "auto_init": true}` and header `Authorization: Bearer <token>` (JDK `HttpClient`, 30 s timeout);
  - which endpoint: `GET {api}/user` first, to learn the token's login;
  - errors carry GitHub's status and message, never the token;
  - no token for the owner gives `"orange has no GitHub token for <owner>"`.

- [ ] **Step 1:** Tests:
  - `SteeringMcpIT`, over real MCP:
    - `planToolStartsAPlanAndStatusShowsIt`;
    - `taskToolAcceptsAPrefix`;
    - `anAmbiguousPrefixIsAnError`;
    - `tailShowsTheLatestRunsEvents`;
    - `cancelPauseResume`;
    - `projectCreateWithAnExistingRepoClonesAndActivates`;
    - `projectCreateWithAnInaccessibleRepoCreatesNothing`;
    - `projectCreateWithCreateTrueMakesTheRepo`, with a JDK `HttpServer` as the API that creates a bare repo with one commit in the temp GitHub dir;
    - `projectCreatePutsTheGivenEvidenceInForce`;
  - `GithubReposTest`:
    - `sendsTheTokenOnlyInTheHeader`;
    - `anErrorNeverCarriesTheToken`.

  Run them. Expected: FAIL.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run `./gradlew test --tests '*SteeringMcpIT' --tests '*GithubRepos*'`. Expected: PASS.
- [ ] **Step 4:** Commit: `feat(mcp): the steering tab plans, follows, cancels and creates projects`.

### Task 10: The steering session

**Files:**
- Modify:
  - `plugin/hooks/orange-report.py`;
  - `session/SessionService.java`: steering is sticky, and a steering session gets no project, no judge and no items;
  - `session/NeutralEvent.java`, only if a helper is needed.
- Test: `orange/src/test/java/com/ai/orange/session/SessionReporterIT.java` (extend), `orange/src/test/java/com/ai/orange/session/SessionServiceIT.java` (extend; use the existing session IT class if it is named differently)

**Interfaces:**
- **The reporter:**
  - `steering()` is true when `ORANGE_STEERING == "1"`, or when the hook's cwd is at or under `ORANGE_STEERING_DIR`, which defaults to `~/.orange/steering` (compare real paths);
  - when steering, every event's payload gets `"steering": true`;
  - on `session_started` of a steering session, after the post, it calls `GET {ORANGE_URL}/steering/context` with the token, within what is left of the 2 s budget. With an answer it prints `{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext": <text>}}`; with nothing or an error it prints nothing.
- **`SessionService.apply`:**
  - `steering = s.steering() || payload.steering`;
  - when steering: no `projectService.resolve` (the project stays null), `muted=true` for the reactor, and so no judge and no items. The session is saved with `steering=true`.

- [ ] **Step 1:** Tests:
  - `SessionReporterIT`:
    - `steeringStartPrintsOrangesContext`;
    - `steeringStartWithOrangeDownPrintsNothing`;
    - `aSessionUnderTheSteeringDirIsSteering`;
    - `aNormalSessionPrintsNothingAtStart`;
  - `SessionServiceIT`:
    - `aSteeringSessionsStopIsNeverJudged`;
    - `aSteeringSessionInARepoProposesNoProject`;
    - `aSteeringQuestionFilesNothing`.

  Run them. Expected: FAIL.
- [ ] **Step 2:** Implement.
- [ ] **Step 3:** Run `./gradlew test --tests '*Session*'`. Expected: PASS.
- [ ] **Step 4:** Commit: `feat(plugin): the steering tab is recorded, never judged, and starts with context`.

### Task 11: The shim goes; the plugin and docs follow

**Files:**
- Delete: `orange-mcp/` (the whole directory), the repo's `.mcp.json`, `.claude/commands/orange-{cancel,plan,status,tail}.md`, `plugin/skills/orange-orchestrate/`
- Create: `plugin/skills/orange/SKILL.md`
- Modify:
  - `plugin/.claude-plugin/plugin.json` (version `0.3.0`, and a description in the one-tab words);
  - `plugin/README.md`: the steering tab, the alias, the tools;
  - `CLAUDE.md`: the layout loses `orange-mcp/`; Steering lists the new tools; new short sections for plans, task attention, milestones and on-track, and the steering session; the V24 tables in the data model;
  - `deploy/README.md`, if it mentions the shim.
- Test: none new. The full suite runs.

**Interfaces:**
- **`plugin/skills/orange/SKILL.md`:**
  - the frontmatter `description` triggers on status, "what's new", "where should I focus", "are we on track", "build X in Y", "start a project" and answering orange's questions;
  - the body is the flow: status → hand over work → follow → answer.
  - Each step names its tool:
    - status: `orange_status`, `orange_focus`;
    - hand over work: `orange_project_create`, then `orange_plan`;
    - follow: `orange_task`, `orange_tail`;
    - answer: `orange_attention`, then `orange_resolve` with approve, drop or a note; with an answer for a task, it reopens it;
    - control: `orange_cancel`, `orange_pause`, `orange_resume`;
    - milestones: `orange_add kind=milestone`, then a plan with `milestone`.
  - It says that the tab can be cleared at any time, because nothing lives in it.

- [ ] **Step 1:** Delete and write the files. Then run `grep -rn "orange-mcp\|orange_mcp\|mcp__orange__\(plan\|dispatch\|list_tasks\|tail\|cancel\)" --exclude-dir=.git --exclude-dir=docs .`. Expected: no hits outside `docs/` history.
- [ ] **Step 2:** Run `./gradlew test`. Expected: PASS (the full suite).
- [ ] **Step 3:** Commit: `feat(plugin)!: one steering tab; the orange-mcp shim is gone`.

### After the tasks: review, PR, merge, deploy, live exit

These follow the standing flow and are not plan tasks:
1. The whole-branch review.
2. The PR to `orange-e2e-base`.
3. The merge.
4. `deploy/deploy.sh`.
5. The plugin update on the laptop (`/plugin` update to 0.3.0).
6. The live exit from a steering tab on the VPS-backed orange:
   - create a project;
   - add a dated milestone;
   - `orange_plan` a small goal that dispatches on its own;
   - a risky goal that asks first, then approve it;
   - `orange_status` answers on track or behind.
