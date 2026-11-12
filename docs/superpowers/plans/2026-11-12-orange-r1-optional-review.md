# R1 · Review becomes optional: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Evidence commands are the gate. A diff review runs only when the `review` convention, a task's own flag or a risky diff asks for one, for tasks and for session work alike.

**Architecture:**
- A pure rule, `ReviewPolicy.required(mode, taskAsks, risky)`, decides whether a head needs a review.
- `WorkConventions.review(...)` reads the mode: `never`, `risky` or `always`, with `risky` as the global default.
- `RiskPaths.touches(repo, from, to)` says whether a diff touches `orange.routing.risk.paths`.
- `TaskReactor` and `SessionClosing` ask the policy before queueing a `diff_review`. When no review is needed, verification ends after the evidence commands.

**Tech Stack:** Java 27, Spring Boot 4, jOOQ, JUnit 5, AssertJ, Testcontainers. No migration.

**Spec:** `docs/superpowers/specs/2026-11-12-orange-remote-first-design.md`: §7.3, and R1 in §8.

## Global Constraints

- The convention key is `review`. Its values are `never`, `risky` and `always`; the default is `risky` (spec §7.3, builder's call 10).
- `risky` reviews tasks flagged `risk` and work whose diff touches the risk paths (`orange.routing.risk.paths`) (§7.3).
- A per-task override `review: true` asks for a review whatever the mode says, except under `never` (§7.3).
- When review is off, verify ends after the evidence commands, and a pass goes straight to `verified` (§7.3).
- "No evidence commands, no integration" is unchanged (§7.3).
- Task flags live at the top level of `tasks.metadata`: `risk` (boolean, already read by `RunPreparer` for routing) and `review` (boolean, new). The planner's `plan.json` may set both.
- No Flyway migration. The next free migration number stays V23.

## Review Focus

1. A `review` convention holding an unexpected value (`"Always"`, `true`, `["risky"]`) behaves as `risky`, the default. It neither crashes the reactor nor silently skips a risky review. Covered in Task 1.
2. A diff that can't be read (missing worktree, unknown SHA, git error) counts as risky, so a review runs rather than being skipped. Covered in Task 1 (`RiskPaths`) and Task 2 (an IT with no worktree).
3. A head that was reviewed and then rebased cleanly keeps its review. The existing carry-over must still work for reviewed work, and must not invent a review for unreviewed work. Covered in Task 3 (the existing `aMovedBaseIsRebasedCheckedAgainAndThenIntegrated`, under `always`) and in Task 2 (the E2E's unreviewed task B integrates).
4. Turning review off while a task waits in `verifying` with passed checks marks it verified on the next reaction, without a stale review. Covered by `ReactorDecisionTest` in Task 2.
5. Session work that changes only files outside the risk paths, under the default, closes with only its commands' evidence (no `diff_review` row). Covered in Task 3.

---

### Task 1: The rule, the convention and risk paths

**Files:**
- Create: `orange/src/main/java/com/ai/orange/run/ReviewPolicy.java`
- Create: `orange/src/main/java/com/ai/orange/routing/RiskPaths.java`
- Modify: `orange/src/main/java/com/ai/orange/project/WorkConventions.java`
- Modify: `orange/src/main/java/com/ai/orange/project/ConventionsProperties.java` (new `review` default, in `asJson()`)
- Modify: `orange/src/main/resources/application.yaml` (`orange.conventions.review: risky` with a comment; reword the `done` comment, which says evidence includes "a diff review pass")
- Test: `orange/src/test/java/com/ai/orange/run/ReviewPolicyTest.java`
- Test: `orange/src/test/java/com/ai/orange/routing/RiskPathsTest.java`

**Interfaces:**
- Produces:
  - `enum ReviewPolicy.Mode { NEVER, RISKY, ALWAYS }` with `static Mode parse(JsonNode value)`. A textual `never`/`risky`/`always` maps exactly (lowercase only); anything else, null included, maps to `RISKY`.
  - `static boolean ReviewPolicy.required(Mode mode, boolean taskAsks, boolean risky)`, which is true when:
    - the mode is `ALWAYS`; or
    - the mode is `RISKY` and (`taskAsks` or `risky`).
    
    It is false under `NEVER`.
  - `ReviewPolicy.Mode WorkConventions.review(UUID projectId, UUID sessionId)`: resolves `review` through `Conventions.resolve`, then `Mode.parse`.
  - `public static final String REVIEW = "review"` in `WorkConventions`.
  - `@Component RiskPaths(RoutingTable routing)` with `boolean touches(Path repo, String from, String to)`. It lists `git diff --name-only <from> <to>` (`Git.run`, `Git.QUICK`) and is true if any path matches `RoutingTable.isRiskPath`. It is also true when git fails or a ref is blank, with a warn log naming the repo and refs.

- [ ] **Step 1: Write the failing tests**
  - `ReviewPolicyTest`:
    - `requiredFollowsTheModeTheTaskAndTheRisk`: every combination of the three modes × taskAsks × risky against the rule above (12 asserts, or a `@ParameterizedTest` with a `CsvSource`). Cover `NEVER,true,true → false` and `RISKY,false,false → false`.
    - `parseReadsTheThreeValuesAndDefaultsToRisky`: `"never"` → `NEVER`, `"always"` → `ALWAYS`, `"risky"` → `RISKY`; then `"Always"`, `true`, `["risky"]`, a missing node and `null` → `RISKY`.
  - `RiskPathsTest`, with a temp git repo (`git init`, two commits: the first adds `README.md`, the second adds `src/main/resources/db/migration/V1__x.sql`), and a `RoutingTable` built from `RoutingProperties` whose risk paths are `["**/db/migration/**"]` (follow how existing tests build `RoutingTable`, e.g. `RoutingTableTest`):
    - `aDiffIntoARiskPathTouchesIt`: first..second → true;
    - `aDiffOutsideTheRiskPathsDoesNot`: a third commit changes only `README.md`, second..third → false;
    - `aDiffThatCannotBeReadCountsAsRisky`: an unknown SHA → true; a blank `from` → true.

- [ ] **Step 2: Run them to verify they fail**
  Run: `cd orange && ./gradlew test --tests '*ReviewPolicyTest' --tests '*RiskPathsTest'`
  Expected: compilation fails (`ReviewPolicy`, `RiskPaths` do not exist).

- [ ] **Step 3: Implement**
  - `ReviewPolicy` and `RiskPaths` per the Interfaces block.
  - `WorkConventions.review`.
  - `ConventionsProperties` gains `@DefaultValue("risky") String review`, put into `asJson()` under `review`. A bad value is not refused at start: `Mode.parse` already reads it leniently, as `risky`.
  - `application.yaml`: `review: risky` under `orange.conventions`, with a comment: "never, risky (tasks flagged risk, and work whose diff touches orange.routing.risk.paths) or always. Evidence commands are the gate; a review is optional (remote-first spec §7.3)."

- [ ] **Step 4: Run them to verify they pass**
  Run: `cd orange && ./gradlew test --tests '*ReviewPolicyTest' --tests '*RiskPathsTest' --tests '*ConventionsTest*'`
  Expected: PASS. If a test pins the exact `asJson()` key set (search `asJson` in tests), update it for `review`.

- [ ] **Step 5: Commit**
  `git commit -m "feat(review): the review convention, its rule and risk paths"`

### Task 2: Tasks are reviewed only when the policy says so

**Files:**
- Modify: `orange/src/main/java/com/ai/orange/run/ReactorDecision.java` (`TaskSnapshot` gains `boolean reviewRequired`; the `VERIFYING` branch; the class Javadoc's rule 7)
- Modify: `orange/src/main/java/com/ai/orange/run/TaskReactor.java` (`snapshot` computes `reviewRequired`; constructor takes `WorkConventions`, `RiskPaths`)
- Modify: `orange/src/main/java/com/ai/orange/planner/PlanJson.java` (`PlanTask` gains `Boolean risk`, `Boolean review`)
- Modify: `orange/src/main/java/com/ai/orange/planner/PlanGraphService.java` (writes them as top-level metadata `risk` / `review`, next to `overrides`)
- Modify: `orange/src/main/resources/roles/planner.md`, `orange/src/main/resources/roles/implementer.md`
- Test: `orange/src/test/java/com/ai/orange/run/ReactorDecisionTest.java`
- Test: `orange/src/test/java/com/ai/orange/run/TaskReactorIT.java`
- Test: `orange/src/test/java/com/ai/orange/e2e/RunLoopE2EIT.java`
- Test: the planner's ingest test (find it with `grep -rln "PlanGraphService\|ingest(" orange/src/test`)

**Interfaces:**
- Consumes: `ReviewPolicy.required`, `WorkConventions.review(null, null)` (tasks have no project until R2, so this is the global value), `RiskPaths.touches(worktree, worktrees.baseRef(), head)`.
- Produces: `TaskSnapshot(TaskStatus status, int failures, boolean hasActiveRun, boolean paused, boolean workspaceAvailable, String headSha, LastRun lastRun, boolean verifyPassed, boolean reviewRequired, boolean reviewPassed)`. `reviewRequired` sits before `reviewPassed`.

- [ ] **Step 1: Write the failing tests**
  - `ReactorDecisionTest`:
    - the builder `S` gains `reviewRequired()`;
    - the existing `verifyingRunsTheChecksThenTheReviewThenMarksVerified` sets it, and keeps its assertions;
    - new `verifyingWithoutAReviewMarksVerifiedOnceTheChecksPass`: `task(VERIFYING).head(HEAD).decide()` is `QueueVerify(HEAD)`, and `.verifyPassed().decide()` is `MarkVerified`;
    - new `aReviewTurnedOffWhileWaitingIsNotWaitedFor`: `verifyPassed()`, `reviewRequired` false, `reviewPassed` false → `MarkVerified`.
  - `TaskReactorIT`:
    - `verifyingRunsVerifyThenDiffReview` creates its task with metadata `{"review": true}`, with assertions unchanged;
    - new `aTaskWhoseDiffCannotBeReadIsReviewed`: a task with no metadata and no worktree at the fake `HEAD`, in `verifying` with a succeeded verify run of `HEAD` → the reactor queues `DIFF_REVIEW`. This is the fail-safe of Review Focus 2.
  - `RunLoopE2EIT` (both the process test and `InContainers`):
    - task A gets metadata `{"review": true}` and keeps its reviewed assertions;
    - add, for task B, which has no metadata and whose fake commit touches no risk path: no `DIFF_REVIEW` run in `runs` for B, no `diff_review` evidence row for B, and B still reaches `integrated`.
  - The planner ingest test: a plan task with `"risk": true, "review": true` becomes a task whose metadata has top-level `risk: true` and `review: true`. A plan task without them gets neither key.

- [ ] **Step 2: Run them to verify they fail**
  Run: `cd orange && ./gradlew test --tests '*ReactorDecisionTest' --tests '*TaskReactorIT' --tests '*RunLoopE2EIT*' --tests '*PlanGraph*'`
  Expected: compilation fails on the new `TaskSnapshot` argument, then the B assertions fail (B is reviewed today).

- [ ] **Step 3: Implement**
  - `ReactorDecision`, `VERIFYING`: `if (s.reviewRequired() && !s.reviewPassed()) yield new Action.QueueDiffReview(s.headSha());`
  - `TaskReactor.snapshot`: compute `reviewRequired` only when the status is `VERIFYING`, verify passed and review has not; otherwise false. Its value is `ReviewPolicy.required(mode, meta.review == true, meta.risk == true || riskPaths.touches(...))`. Call `touches` only when the mode is `RISKY` and neither flag decides it, since it runs git inside the reactor's transaction.
  - `PlanGraphService`: metadata becomes an object holding `overrides` (when any), `risk` (when non-null) and `review` (when non-null); null when all three are absent.
  - `planner.md`:
    - replace "verified by the project's tests and an independent review of its diff" with "verified by the project's tests";
    - add to the schema line `"risk": false, "review": false`, with one sentence: set `risk` when a task touches authentication, payments, migrations, CI or secrets, or is irreversible, and `review` when a reader of the diff would catch what tests cannot;
    - add one sentence: state each task's acceptance as tests to add or extend.
  - `implementer.md`, line 9: "and another agent may review the diff against the task" in place of "and another agent reviews…". Add: "Commit the tests that show the task is done with the change: they are what verifies it."

- [ ] **Step 4: Run them to verify they pass**
  Run: the command from Step 2.
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat(review): a task is reviewed only when the review convention, its flags or its diff ask for it"`

### Task 3: Session work is reviewed only when the policy says so

**Files:**
- Modify: `orange/src/main/java/com/ai/orange/session/SessionClosing.java` (`afterVerify`'s `reviewable`; constructor takes `RiskPaths`; the class Javadoc)
- Test: `orange/src/test/java/com/ai/orange/session/SessionCloseIT.java`

**Interfaces:**
- Consumes: `WorkConventions.review(s.projectId(), s.id())`, `ReviewPolicy.required(mode, false, risky)`, `RiskPaths.touches(repo, check.baseSha(), check.headSha())`. `repo` is `s.worktree()` when set, else `s.repoRoot()`, the same choice `RunPreparer.prepareSessionReview` makes.

- [ ] **Step 1: Write the failing tests** in `SessionCloseIT`
  - Every existing test that asserts a review ran gets `convention("review", "\"always\"")` next to its `evidence` convention, with its assertions unchanged. That's the tests whose run counts or kind lists include a diff review: lines 189, 304, 395, 434, 501, 530, 556, and any other the run reveals.
  - New `finishedWorkOutsideTheRiskPathsIsIntegratedWithoutAReview`: the default mode; commit `feature.txt`; evidence `["test -f feature.txt"]`. Then:
    - `runAll()` is 3 (judge, verify, integrate);
    - no `DIFF_REVIEW` run;
    - the evidence kinds are exactly `["tests"]`;
    - the work is closed `integrated`.
  - New `workTouchingARiskPathIsReviewed`: the default mode; commit `db/migration/V1__x.sql` → a `DIFF_REVIEW` run happens and the work is closed `integrated` (4 runs).
  - New `reviewNeverSkipsEvenRiskyWork`: `convention("review", "\"never\"")`; the same risky commit → no `DIFF_REVIEW`, closed `integrated`.

- [ ] **Step 2: Run them to verify they fail**
  Run: `cd orange && ./gradlew test --tests '*SessionCloseIT'`
  Expected: the new default and `never` tests fail (a review runs today). The `always` tests pass already.

- [ ] **Step 3: Implement**
  - `reviewable` = (the existing `done` contains `evidence`, base ≠ head, no passing review) **and** `ReviewPolicy.required(conventions.review(s.projectId(), s.id()), false, risky)`.
  - `risky` comes from `RiskPaths`, and is computed only when the mode is `RISKY`.

- [ ] **Step 4: Run them to verify they pass**
  Run: `cd orange && ./gradlew test --tests '*SessionCloseIT' --tests '*DiffReviewIT' --tests '*ContainerRunIT'`
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git commit -m "feat(review): session work is reviewed only when the review convention or a risky diff asks for it"`

### Task 4: Docs and the whole suite

**Files:**
- Modify: `CLAUDE.md`. Places to change:
  - the run loop's `verifying → verify, then diff review, then verified`;
  - "Verification and integration": the `diff_review` bullet says it runs when the `review` convention asks;
  - "Closing a session's work": its `diff_review` step;
  - the Conventions bullet, which gains `review`;
  - the fake's description, if it changes.
- Modify: `orange/src/test/java/com/ai/orange/e2e/ContainerLiveIT.java`, only if its task goes through the reactor and expects a review. In that case give the task `{"review": true}`.
- Modify: any other file `grep -rn -i "diff review\|diff_review" CLAUDE.md orange/README.md docs/superpowers/plans/README* plugin/` shows describing review as always on (not the specs, which R1 implements).

- [ ] **Step 1: Update the docs** per the list above. Keep CLAUDE.md's voice: plain statements of what the code does.
- [ ] **Step 2: Run the whole suite**
  Run: `cd orange && ./gradlew test`
  Expected: BUILD SUCCESSFUL. Each failure is either a test that assumed a review (give it `review: true` or `convention("review", "\"always\"")`) or a real bug.
- [ ] **Step 3: Commit**
  `git commit -m "docs(review): CLAUDE.md and the role prompts say review is optional"`

## Rulings made while planning

1. **`review: true` on a task does not beat `never`.** `never` is the user's project-wide word; a planner's flag can add a review under `risky` but not override `never`. Under `always`, the flag makes no difference.
2. **An unreadable diff counts as risky.** Skipping a review that may have been due is worse than one review too many.
3. **An unknown `review` value means `risky`.** It's logged nowhere and refused nowhere, like the other conventions, which are read leniently.
4. **Tasks read the global `review` value until R2 gives them a project.**
5. **Task flags sit at the top level of `metadata`,** where `RunPreparer` already reads `risk`, not under `overrides`. Today the planner's `overrides` never reach routing; this fixes that for `risk`.
