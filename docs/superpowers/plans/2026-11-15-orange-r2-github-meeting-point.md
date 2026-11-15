# R2 · GitHub is the meeting point — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** orange works from GitHub, not from the laptop. Projects own GitHub repos, which orange keeps as mirrors. Tasks belong to a project and a repo. A session's work is verified and merged from what it pushed.

**Architecture:**
- Repos are identified by `owner/name` (`RepoKey`), parsed from a remote URL.
- orange keeps one bare mirror per repo under `orange.repos.root` and runs all of its own git there: the mirror, task worktrees added from it, and scratch checkouts of it. Network git (fetch, push) goes through `Mirrors`, which holds a lock per repo and supplies the owner's token through a credential helper that reads it from git's environment.
- A task's worktree, base branch and remote come from its repo (`TaskBases`). Tasks without a repo keep the old single `orange.worktrees.base-repo` as a fallback for development.
- A session is closed from the mirror:
  - the reporter sends the head and the uncommitted files;
  - orange fetches, checks that the head was pushed, runs the evidence commands in a clone of it, and integrates in a scratch worktree of the mirror;
  - it never reads or writes the user's checkout.

**Tech Stack:** Java 27, Spring Boot 4, jOOQ + Flyway (Postgres 18), git CLI, Python 3 (reporter), JUnit 5 + Testcontainers. Local bare repos under a temp "GitHub" root stand in for GitHub.

**Spec:** `docs/superpowers/specs/2026-11-12-orange-remote-first-design.md`, §7.1, §7.4, §8 (R2), builder's calls 6, 9 and 11.

## Global Constraints

- Migrations are immutable once merged: add `V23__github_repos.sql`. Follow the table patterns: `uuidv7()` PKs, `created_at`, indexes on lookup columns.
- **The PAT** is "used only for orange's own git (clone, fetch, push) through a credential helper. It never goes into a container… It is never logged or stored, like the subscription token."
  - It never appears in an argv, a remote URL, a git config file, a log line, an exception message or a DB row.
- **Container rules from v2 PR 6 stand:**
  - orange never runs git in a directory a container wrote;
  - no container gets write access to a directory in which orange runs git.
- **"orange no longer touches the laptop's files."** Session close reads nothing from `sessions.repo_root` or `sessions.worktree` on disk, and writes nothing there.
- **Builder's call 9, fast-forward first:** a branch that already contains the base is pushed with its SHAs unchanged.
- **Builder's call 11:** a session on the base branch pushes to `orange/session/<short id>`. orange verifies it there, fast-forwards the base to it, pushes the base and deletes the side branch.
- The repo is public: no IPs, hostnames, tokens or device names in code, tests or docs.
- Tests never call a model (FakeClaude) and never touch the network. "GitHub" is a temp directory of bare repos, set through `orange.repos.github-url=file://<dir>/`.

## Review Focus

1. **Two runs fetch the same mirror at once** (two tasks of one repo starting together). Both must succeed: `Mirrors` serialises network git per repo (`MirrorsIT.parallelFetchesOfOneMirrorBothSucceed`).
2. **The token leaks** into the mirror's config, a remote URL, or the message of a failed fetch. None of them contain it (`GitAuthTest`, `MirrorsIT.theTokenIsNowhereOnDisk`).
3. **Case and URL shape:**
   - `https://github.com/Owner/Repo.git`, `git@github.com:owner/repo`, `ssh://git@github.com/owner/repo/` and `https://github.com/owner/repo` are one repo, and so one project;
   - a non-GitHub remote is no repo.
   
   Tests: `RepoKeyTest`, `ProjectReposIT.twoCheckoutsOfOneRepoAreOneProject`.
4. **The session pushed more after it stopped**, so `origin/<branch>` is ahead of the stop's head. The head is reachable from what was pushed, so it is verified, not sent back (`SessionCloseIT.aBranchPushedFurtherThanTheStopStillVerifiesTheStoppedHead`).
5. **A half-made mirror left by a crash.** The clone happens in a temp directory that is renamed into place, so a later `ensure` never uses a broken mirror (`MirrorsIT.aMirrorDirectoryWithoutAGitRepoIsMadeAgain`).

---

### Task 1: Repo identity and authenticated git

**Files:**
- Create: `orange/src/main/java/com/ai/orange/repo/RepoKey.java`, `orange/src/main/java/com/ai/orange/repo/RepoProperties.java`, `orange/src/main/java/com/ai/orange/worktree/GitAuth.java`
- Modify: `orange/src/main/java/com/ai/orange/worktree/Git.java` (auth overloads; scrub `ORANGE_GITHUB_TOKENS`), `orange/src/main/resources/application.yaml` (`orange.repos`), `orange/.env.example`
- Test: `orange/src/test/java/com/ai/orange/repo/RepoKeyTest.java`, `orange/src/test/java/com/ai/orange/worktree/GitAuthTest.java`

**Interfaces:**
- Produces:
  - `record RepoKey(String owner, String name)`:
    - `static Optional<RepoKey> parse(String remoteUrl, String githubUrl)`;
    - `static RepoKey of(String ownerSlashName)`, which throws `IllegalArgumentException`;
    - `String toString()` returns `owner/name`, lowercased;
    - `String cloneUrl(String githubUrl)`.
  - `@ConfigurationProperties("orange.repos") record RepoProperties(String root, @DefaultValue("https://github.com/") String githubUrl, @DefaultValue("") String tokens)`:
    - `root` defaults to `~/.orange/repos`;
    - `Optional<String> tokenFor(String owner)`, case-insensitive;
    - `toString()` never prints tokens.
  - `final class GitAuth`:
    - `static GitAuth NONE` (the host's own credentials, for the legacy path);
    - `static GitAuth anonymous()` (host helpers cleared, no prompt);
    - `static GitAuth token(String secret)`;
    - `List<String> configArgs()` and `Map<String,String> env()`;
    - `toString()` is redacted.
  - `Git.run(Path, Duration, GitAuth, String...)` and `Git.exitCode(Path, Duration, GitAuth, String...)`.

Rules:
- **Accepted URL shapes:** `https://github.com/o/n[.git][/]`, `http://…`, `git@github.com:o/n[.git]`, `ssh://git@github.com[:port]/o/n[.git]`, and any URL starting with `githubUrl`. For a `file://` `githubUrl`, also the bare path after `file://`.
- **Owner and name** match `[A-Za-z0-9._-]+`, are not `.` or `..`, and don't start with `-`. Both are lowercased.
- **Tokens format:** `owner=token[,owner=token]`, from env `ORANGE_GITHUB_TOKENS`.
- **GitAuth.token** adds `-c credential.helper= -c credential.helper=!f() { test "$1" = get || exit 0; echo username=x-access-token; echo "password=$ORANGE_GIT_SECRET"; }; f`. Its env is `ORANGE_GIT_SECRET=<secret>` and `GIT_TERMINAL_PROMPT=0`.
- **anonymous()** adds only `-c credential.helper=` and `GIT_TERMINAL_PROMPT=0`.
- The auth env is added after the secrets scrub.

- [ ] **Step 1: Write the failing tests**

`RepoKeyTest`:
- `githubUrlShapesAreOneKey`: the four Review Focus #3 URLs → `RepoKey.of("owner/repo")`, `toString()` `"owner/repo"`.
- `aNonGithubRemoteIsNoRepo`: `https://gitlab.com/o/n.git` → empty.
- `aFileGithubUrlStandsInForGithub`: with `githubUrl = "file:///tmp/gh/"`, both `file:///tmp/gh/a/b.git` and `/tmp/gh/a/b.git` → `a/b`.
- `badNamesAreRefused`: `of("../x")`, `of("-o/x")`, `of("o/x/y")` and `of("o")` throw.
- `cloneUrlJoinsTheBase`: `of("a/b").cloneUrl("https://github.com/")` → `"https://github.com/a/b.git"`.
- `tokensAreFoundByOwnerWithoutCaseAndNeverPrinted`: `new RepoProperties(null, "https://github.com/", "Alice=t1,acme=t2").tokenFor("alice")` → `t1`; `toString()` does not contain `t1`.

`GitAuthTest`:
- `theHelperHandsGitTheTokenFromItsEnvironment`: in a temp repo, `Git.run(dir, QUICK, GitAuth.token("s3cr3t-x"), "credential", "fill")` with stdin… `Git` closes stdin, so use `git credential fill` through a test-local `ProcessBuilder` with `GitAuth.configArgs()`/`env()` and input `protocol=https\nhost=github.com\n\n`. The output contains `password=s3cr3t-x`.
- `theTokenIsInNoArgv`: `GitAuth.token("s3cr3t-x").configArgs()` has no element containing `s3cr3t-x`, and `toString()` doesn't contain it.
- `gitNeverSeesTheTokensVariable`: `Git.environment(Map.of("ORANGE_GITHUB_TOKENS","a=b"))` is empty of it.

- [ ] **Step 2: Run them, expect compile failure**

Run: `cd orange && ./gradlew test --tests '*RepoKeyTest' --tests '*GitAuthTest'`
Expected: FAIL. The classes don't exist yet.

- [ ] **Step 3: Implement `RepoKey`, `RepoProperties`, `GitAuth` and the `Git` overloads**

Register `RepoProperties` wherever the other `@ConfigurationProperties` are enabled. In `application.yaml`:
```yaml
orange:
  repos:
    root: ${ORANGE_REPOS_ROOT:}
    github-url: ${ORANGE_GITHUB_URL:https://github.com/}
    tokens: ${ORANGE_GITHUB_TOKENS:}
```
In `.env.example`, add `ORANGE_GITHUB_TOKENS=` with a comment: fine-grained PAT per owner; Contents read/write and Metadata read.

- [ ] **Step 4: Run the tests**

Run: the same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**: `feat(repo): owner/name repo keys and token git through a credential helper`

---

### Task 2: Repos table, projects by remote

**Files:**
- Create: `orange/src/main/resources/db/migration/V23__github_repos.sql`, `orange/src/main/java/com/ai/orange/repo/Repo.java`, `orange/src/main/java/com/ai/orange/repo/RepoRepository.java`, `orange/src/main/java/com/ai/orange/project/TaskPlacement.java`
- Modify:
  - `orange/src/main/java/com/ai/orange/project/ProjectService.java`: `resolve` by key, `addGithubRepo`, `placeTask`;
  - `orange/src/main/java/com/ai/orange/project/ProjectRepo.java` and `ProjectRepository.java`: `repoId`;
  - `orange/src/main/java/com/ai/orange/mcp/SteeringTools.java`: `add_repo` takes `owner/name` too;
  - `orange/src/main/java/com/ai/orange/api/SpaceViews.java`: repo views.
- Test: `orange/src/test/java/com/ai/orange/project/ProjectReposIT.java`

**Interfaces:**
- Consumes: `RepoKey.parse`, `RepoProperties.githubUrl()` (Task 1).
- Produces:
  - `record Repo(UUID id, UUID projectId, RepoKey key, String defaultBranch)`.
  - `RepoRepository`: `Optional<Repo> findById(UUID)`, `Optional<Repo> findByKey(RepoKey)`, `List<Repo> ofProject(UUID)`, `Repo insert(UUID projectId, RepoKey, String defaultBranch)`, `void moveToProject(UUID repoId, UUID projectId)`, `void setDefaultBranchIfNone(UUID repoId, String)`.
  - `ProjectRepository.findRepo(...)` returns `ProjectRepo` with a new last component `UUID repoId`; `void linkRepo(UUID checkoutId, UUID repoId)`.
  - `ProjectService.addGithubRepo(UUID projectId, RepoKey key) -> Repo`.
  - `ProjectService.placeTask(String projectRef, String repoKey) -> TaskPlacement`.
  - `record TaskPlacement(UUID projectId, UUID repoId)` with `static TaskPlacement NONE`.
  - `Optional<Repo> ProjectService.repoOfCheckout(String nodeId, String repoRoot)`.

Migration:
```sql
CREATE TABLE repos (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id     UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    key            TEXT NOT NULL UNIQUE,          -- owner/name on GitHub, lowercased
    default_branch TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_repos_project ON repos (project_id);
ALTER TABLE project_repos ADD COLUMN repo_id UUID REFERENCES repos(id) ON DELETE SET NULL;
ALTER TABLE tasks ADD COLUMN project_id UUID REFERENCES projects(id) ON DELETE SET NULL,
                  ADD COLUMN repo_id    UUID REFERENCES repos(id) ON DELETE RESTRICT;
CREATE INDEX idx_tasks_project ON tasks (project_id) WHERE project_id IS NOT NULL;
ALTER TABLE sessions ADD COLUMN head_sha TEXT,      -- the head the last stop reported
                     ADD COLUMN dirty_files JSONB;  -- its uncommitted paths; null = not reported
```

The tasks and sessions columns are used in Tasks 4 and 6. They go in one migration so there is one schema change.

Rules:
- **`resolve(node, root, remoteUrl, defaultBranch)` for a checkout orange knows:** it keeps the checkout's project. When the remote parses and the checkout has no `repo_id`, it links the checkout: to the existing repo of that key, or to a new repo in the checkout's project.
- **For an unknown checkout whose key is a known repo:** the checkout goes to that repo's project, linked, with no proposal item. This is how two checkouts of one repo become one project.
- **For an unknown key or no key:** a proposed project as today, plus a repo row when there is a key.
- **The default branch**, when reported, fills `repos.default_branch` if it is empty.
- **`addGithubRepo`:** inserts the repo, or moves it from another project. It moves its linked checkouts (`project_repos.repo_id`) and their sessions along (`sessions.moveToProject`).
- **`placeTask(project, repo)`:**
  - a blank project gives `NONE`;
  - an unknown project gives `IllegalArgumentException("no such project: …")`;
  - with `repo` given, it must be one of the project's repos;
  - without one, the project must have exactly one repo. Zero gives `"project X has no GitHub repo; attach one with orange_project_update add_repo owner/name"`; more than one gives `"project X has N repos; name one with repo"`.
- **Steering `add_repo`:** a value that doesn't start with `/` and parses as `RepoKey.of` calls `addGithubRepo`. Otherwise it's a checkout path, as today. `orange_project` lists `github_repos: [{key, default_branch}]`.

- [ ] **Step 1: Write the failing IT `ProjectReposIT`** (SpringBootTest + Testcontainers, like `SpaceIT`):
  - `twoCheckoutsOfOneRepoAreOneProject`: `resolve("laptop", "/a/x", "git@github.com:Me/Thing.git", "main")`, then `resolve("vps", "/srv/x", "https://github.com/me/thing", null)`. Both return the same project id. There is one `PROJECT_PROPOSED` item, and `repoRepository.findByKey(of("me/thing")).get().defaultBranch()` is `"main"`.
  - `aRepoWithoutAGithubRemoteHasNoRepoRow`: `resolve("n", "/b", "https://gitlab.com/o/n.git", null)` gives a proposed project, and `ofProject(id)` is empty.
  - `aKnownCheckoutIsLinkedWhenItsRemoteAppears`: resolve with a null remote, then the same checkout with a GitHub remote. Same project; `repoOfCheckout("n", "/c")` is present.
  - `addingAGithubRepoMovesItsCheckoutsAndSessions`: a checkout and a session in proposed project P1; `addGithubRepo(P2, key)`. The repo, the checkout and the session are all in P2.
  - `placementNeedsOneRepoOrAName`: zero repos throws with "has no GitHub repo"; one repo gives that repo; two repos without a name throw "has 2 repos"; with a name, that repo; a blank project gives `NONE`.

- [ ] **Step 2: Run it**: `./gradlew test --tests '*ProjectReposIT'`. Expected: FAIL (compile).
- [ ] **Step 3: Write the migration, `Repo`, `RepoRepository`, the `ProjectService` changes and the steering `add_repo`.** Then run `./gradlew generateJooq`.
- [ ] **Step 4: Run** `./gradlew test --tests '*ProjectReposIT' --tests '*SpaceIT' --tests '*SteeringMcpIT' --tests '*FlywayMigrationsIT'`. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(repo): repos keyed by owner/name; checkouts of one repo are one project`

---

### Task 3: Mirrors

**Files:**
- Create: `orange/src/main/java/com/ai/orange/repo/Mirrors.java`, `orange/src/main/java/com/ai/orange/worktree/Remote.java`
- Test: `orange/src/test/java/com/ai/orange/repo/MirrorsIT.java` (plain JUnit, no Spring: construct `Mirrors` with `RepoProperties` on temp dirs), `orange/src/test/java/com/ai/orange/repo/FakeGithub.java` (a test helper)

**Interfaces:**
- Consumes: `RepoKey`, `RepoProperties`, `GitAuth`, `Git` overloads (Task 1).
- Produces:
  - `interface Remote { void fetch(); void push(Path dir, String... refspecs); }`. It lives in `worktree` and throws `GitException`.
  - `@Component Mirrors(RepoProperties)`:
    - `Path path(RepoKey)` returns `<root>/<owner>/<name>.git`;
    - `Path ensure(RepoKey)` clones when missing, without fetching an existing mirror;
    - `void fetch(RepoKey)`;
    - `Remote remote(RepoKey)`;
    - `GitAuth auth(RepoKey)`: a token when the clone URL is `https://` and the owner has one, else `anonymous()`;
    - `Optional<String> defaultBranch(RepoKey)` from `refs/remotes/origin/HEAD`.
  - Test helper `FakeGithub(Path root)`:
    - `RepoKey create(String ownerSlashName)` makes a bare repo with one commit `README.md` on `main` and a `HEAD` → `main`;
    - `String url()` returns `file://<root>/`;
    - `Path bare(RepoKey)`;
    - `String commit(RepoKey, String branch, String file, String content)` makes a commit through a temp clone and returns its sha;
    - `String head(RepoKey, String branch)`.

Rules:
- **Cloning:** `git init --bare -q <tmp>`, then `remote add origin <cloneUrl>`, `fetch --quiet --prune origin` and `remote set-head origin --auto` (failure ignored). Then an atomic `Files.move` to `path(key)`.
- **A directory at `path(key)` that isn't a git dir** (`git rev-parse --git-dir` fails) is deleted and cloned again.
- **The tmp directory** is a sibling of the target, `<name>.git.tmp-<uuid>`.
- **Fetch:** `fetch --quiet --prune origin` with `auth(key)`.
- **Push:** `push --quiet origin <refspecs…>`, run in `dir`, which is the mirror or a worktree of it.
- **Locking:** a `ReentrantLock` per key, held for `ensure`'s clone, every fetch and every push.
- **Remote URL:** the clone URL never contains a token.

- [ ] **Step 1: Write the failing `MirrorsIT`**:
  - `ensureClonesOnceAndFetchSeesNewCommits`: `ensure` gives a bare dir where `rev-parse origin/main` equals `FakeGithub.head`. After a new commit, `ensure` again doesn't move `origin/main`; `fetch` does.
  - `theDefaultBranchComesFromOriginHead`: a repo whose HEAD is `trunk` → `defaultBranch` is `trunk`.
  - `pushGoesToGithub`: in a worktree of the mirror, commit, then `remote(key).push(wt, sha + ":refs/heads/main")`; `FakeGithub.head(key, "main")` equals sha.
  - `parallelFetchesOfOneMirrorBothSucceed`: 8 virtual threads call `fetch(key)` at once; none throws.
  - `aMirrorDirectoryWithoutAGitRepoIsMadeAgain`: create an empty `path(key)` dir with a stray file; `ensure` gives a working mirror.
  - `theTokenIsNowhereOnDisk`: with `tokens = "<owner>=tok-zz9"` and an `https://example.invalid/` githubUrl, `ensure` throws `GitException` (no network) whose message doesn't contain `tok-zz9`. No file under the mirrors root contains `tok-zz9`.
  - `anUnknownRepoFailsWithGitException`: `ensure(of("nobody/none"))` throws `GitException`, and no directory is left at `path`.

- [ ] **Step 2: Run** `./gradlew test --tests '*MirrorsIT'`. Expected: FAIL (compile).
- [ ] **Step 3: Implement `Remote`, `Mirrors` and `FakeGithub`.**
- [ ] **Step 4: Run it**. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(repo): one bare mirror per repo, fetched and pushed one at a time`

---

### Task 4: Tasks belong to a project and a repo; worktrees per repo

**Files:**
- Create:
  - `orange/src/main/java/com/ai/orange/worktree/TaskBase.java` and `orange/src/main/java/com/ai/orange/worktree/TaskBases.java`;
  - `orange/src/main/java/com/ai/orange/repo/RepoTaskBases.java`;
  - `orange/src/main/java/com/ai/orange/evidence/EvidenceAsk.java`, extracted from `SessionClosing.askForEvidence`;
  - `orange/src/test/java/com/ai/orange/e2e/MultiRepoE2EIT.java`.
- Modify:
  - `task/Task.java` and `TaskDef.java`: `projectId` and `repoId` as last components, keeping the 6-arg `TaskDef` constructor;
  - `TaskRepository.java`: insert and toDomain;
  - `worktree/WorktreeService.java`: everything per task;
  - `run/RunPreparer.java`, `PostRunSteps.java`, `TaskReactor.java`, `RunWorkspaces.java`, `EvidenceRunner.java`, `Integrator.java` (task target);
  - `postmerge/WorktreeDisposer.java`: runs without `base-repo`;
  - `ReactorDecision.java`: `evidenceMissing` and `WaitForEvidence`;
  - `planner/PlanJson.java`: top-level `project` and `repo`, `PlanTask.repo`;
  - `planner/PlanGraphService.java`: placement;
  - `api/TaskCreateRequest.java`, `DispatchRequest.java`, `TaskController.java`, `DispatchController.java`, `DispatchService.java`: `project` and `repo`;
  - `api/TaskResponse.java`: `project_id` and `repo_id`;
  - `session/SessionClosing.java`: uses `EvidenceAsk`.

**Interfaces:**
- Consumes:
  - from Task 2: `TaskPlacement`, `ProjectService.placeTask`, `RepoRepository.findById`;
  - from Task 3: `Mirrors.ensure`, `Mirrors.remote`, `Mirrors.defaultBranch`, `Remote`.
- Produces:
  - `record TaskBase(Path repo, String branch, Remote remote)`, with `boolean hasOrigin()` (remote != null) and `String ref()` (`origin/<branch>` or `<branch>`).
  - `interface TaskBases { Optional<TaskBase> of(Task task); }`: empty means the legacy base repo.
  - `WorktreeService`:
    - `TaskBase base(UUID taskId)`: the task's repo, else the legacy `TaskBase(baseRepo, baseBranch, hasOrigin ? LocalRemote : null)`;
    - `Path ensure(UUID)`, `Path create(UUID)`, `void remove(UUID)`, `Path resolve(UUID)`, `void fetchBase(UUID)`, `boolean hasCapacity()`;
    - removed: the no-arg `baseRef()`, `hasOrigin()`, `baseRepo()`, `baseBranch()`.
  - `EvidenceAsk.ask(Project project, List<String> detected)`: the item is keyed `project:<id>:evidence_missing`, as today.
  - `ReactorDecision.TaskSnapshot` gets a new last component `boolean evidenceMissing`, and there's a new `Action.WaitForEvidence()`.
  - `EvidenceRunner` reads a task's commands from `TaskEvidence`: the project's `evidence` convention when the task has a project, else `orange.evidence.commands`. That's a private method, `commandsFor(Task)`.

Rules:
- **The base of a task with a repo** (`RepoTaskBases.of`):
  - the repo is `mirrors.ensure(key)`;
  - the branch is the project's `base-branch` convention, else `repos.default_branch`, else `mirrors.defaultBranch(key)`, else `main`;
  - the remote is `mirrors.remote(key)`.
- **`create`** fetches through `base.remote()` when present, then does `worktree add -b orange/<id> <root>/<id> <base.ref()>` in `base.repo()`.
- **Project conventions for project tasks:** review mode (`TaskReactor.reviewRequired`), evidence, remote-push (`Integrator`: `props.remotePush() && conventions.remotePush(projectId, null)`) and base-branch. Legacy tasks read the global values as today.
- **Evidence missing:** a VERIFYING project task whose `done` convention contains `evidence` and that has no commands is `evidenceMissing`. The decision is `WaitForEvidence`, and `TaskReactor` applies it with `evidenceAsk.ask(project, EvidenceDetector.detect(worktree))`. The task stays `verifying`, and the reactor sweep queues the verify once the user puts commands in force. "orange can implement but not integrate."
- **The `Integrator` task target** uses `worktrees.base(id)`. A push goes through `base.remote().push(wt, head + ":refs/heads/" + branch)`, and a fetch through `remote.fetch()`. `updateLocalBase` stays, and is `skipped` on a bare mirror because there is no local branch.
- **API:**
  - `POST /tasks` takes `project` and `repo`;
  - `POST /dispatch` takes `project` and `repo` (they override the plan's);
  - `PlanJson.project`, `PlanJson.repo` and `PlanTask.repo` are optional;
  - a `PlanTask.repo` without a project is a 422 (`IllegalArgumentException`).

- [ ] **Step 1: Write the failing tests**
  - `ReactorDecisionTest.aProjectTaskWithoutEvidenceWaits`: VERIFYING, head set, `evidenceMissing = true` → `WaitForEvidence`. Fix up the existing snapshots with `false`.
  - **`MultiRepoE2EIT`**, the exit criterion, with the loop on like `RunLoopE2EIT`:
    - `FakeGithub` holds `me/alpha` and `me/beta`;
    - two projects, each with `addGithubRepo` and an `evidence` convention `["test -n \"$(ls)\""]` set by the user;
    - one task each through `taskService.createGraph` with `TaskPlacement`;
    - wait for both tasks to be `INTEGRATED`;
    - assert:
      - each `FakeGithub.head(key, "main")` is the task's `head_sha`;
      - `alpha`'s head commit isn't in `beta`'s history;
      - each worktree's `git rev-parse --git-common-dir` is that repo's mirror.
  - `MultiRepoE2EIT.aProjectTaskWithoutEvidenceWaitsAndSaysSo`: a project without `evidence` → the task stays `VERIFYING`, and one `evidence_missing` item is open. After the user sets evidence and the reactor sweeps, it is `INTEGRATED`.
  - `DispatchServiceIT.aPlanForAProjectPlacesItsTasks`: dispatching with project P (one repo) gives tasks with P's `project_id`/`repo_id`. A `PlanTask.repo` without a project throws.
  - `WorktreeServiceIT.aTaskOfARepoBranchesFromItsMirror`: a task with a repo → `ensure` gives a worktree whose `HEAD` is the `FakeGithub` main head, and `base(id).ref()` is `"origin/main"`.
- [ ] **Step 2: Run them**: `./gradlew test --tests '*ReactorDecisionTest' --tests '*MultiRepoE2EIT' --tests '*DispatchServiceIT' --tests '*WorktreeServiceIT'`. Expected: FAIL (compile).
- [ ] **Step 3: Implement.**
  - Start with `Task`/`TaskDef`/`TaskRepository`, then `TaskBase`/`TaskBases`/`RepoTaskBases`, then the `WorktreeService` per-task API.
  - Then move every caller, so that `grep -rn 'worktrees\.\(baseRef\|hasOrigin\|baseRepo\|baseBranch\)()' src/main` finds nothing.
  - Then the conventions, `EvidenceAsk`, the decision, and last the API.
  - `RunTestSupport` keeps its legacy base repo, so existing ITs run unchanged.
- [ ] **Step 4: Run** the Step 2 tests, then the whole suite: `./gradlew test > <ledger dir>/t4.log 2>&1; tail -5`. Expected: all pass.
- [ ] **Step 5: Commit**: `feat(tasks): tasks belong to a project and a repo; worktrees and integration per repo`

---

### Task 5: The reporter sends the head and the uncommitted files

**Files:**
- Modify: `plugin/hooks/orange-report.py`, `orange/src/main/java/com/ai/orange/session/Session.java` (`headSha`, `dirtyFiles` as last components), `SessionRepository.java`, `SessionService.java`, `NeutralEvent.java` (Javadoc), `plugin/.claude-plugin/plugin.json` (0.2.2)
- Test: `orange/src/test/java/com/ai/orange/session/SessionReporterIT.java`, `orange/src/test/java/com/ai/orange/session/SessionServiceIT.java` (or the IT that owns `apply`; use the existing one)

**Interfaces:**
- Produces:
  - `Session.headSha()` (String, null when not reported);
  - `Session.dirtyFiles()` (`List<String>`: null when not reported, empty when clean);
  - the stop payload fields `head_sha` and `dirty_files`.

Rules:
- **At `Stop` only,** the reporter adds `payload.head_sha` (`git rev-parse HEAD`) and `payload.dirty_files` (the first 50 lines of `git status --porcelain`). Both use a 5 s git timeout.
- **When a value can't be read,** it is left out. It is never sent as an empty value.
- **`SessionService.apply` on `stopped`** stores both: `dirty_files` as a JSONB array. A stop without the fields stores nulls, so a stale head is never reused. Other events keep them.

- [ ] **Step 1: Write the failing tests.**
  - In `SessionReporterIT`: a captured Stop hook run in a temp git repo with one untracked file posts `payload.head_sha` = the repo's HEAD and `payload.dirty_files` = `["?? new.txt"]`. A clean repo posts `dirty_files: []`.
  - In the session apply IT: a stop with `head_sha` `abc` and `dirty_files` `[" M a"]` → `session().headSha()` is `"abc"` and `dirtyFiles` is `[" M a"]`. A later stop without them → both null.
- [ ] **Step 2: Run** `./gradlew test --tests '*SessionReporterIT' --tests '<apply IT>'`. Expected: FAIL.
- [ ] **Step 3: Implement** the reporter, the columns (from V23) and `Session`. Bump the plugin to `0.2.2`.
- [ ] **Step 4: Run them**. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(reporter): a stop carries its head and its uncommitted files`

---

### Task 6: Closing a session's work from GitHub

**Files:**
- Modify:
  - `run/SessionChecks.java` (rewritten around the mirror);
  - `run/EvidenceRunner.java`: a session's commands go in containers in container mode;
  - `run/RunWorkspaces.java`: `cloneRef`;
  - `run/Integrator.java`: the session target;
  - `run/RunPreparer.java`: the session review reads the mirror;
  - `run/RunOutcome.java`: the `NO_REMOTE` status;
  - `session/SessionClosing.java`: no-remote item, `remote-push` off, rebased input, the mirror for risk paths.
- Test: `orange/src/test/java/com/ai/orange/session/SessionCloseIT.java`, rewritten on `FakeGithub`.

**Interfaces:**
- Consumes:
  - from Tasks 2, 3 and 5: `ProjectService.repoOfCheckout`, `Mirrors.ensure`, `Mirrors.fetch`, `Mirrors.remote`, `Mirrors.defaultBranch`, `Session.headSha()`, `Session.dirtyFiles()`;
  - from Task 4: `EvidenceAsk`.
- Produces:
  - `SessionChecks.Place place(Session s)`: a `record Place(RepoKey key, Path mirror, String branch, String baseBranch, Remote remote)`, or `Located` with a problem;
  - `static String sideBranch(UUID sessionId)` returns `"orange/session/" + <the last 8 hex digits of the id>`;
  - `static String checkRef(UUID sessionId)` returns `"refs/orange/sessions/" + id`;
  - `RunWorkspaces.cloneRef(Path repo, String ref, Path runDir) -> Path`: `<runDir>/workspace`, made with `init` plus `fetch <repo> +<ref>:refs/heads/work` plus `checkout work`, with no remote;
  - `RunOutcome.SessionCheck.NO_REMOTE`;
  - `SessionChecks.PUSH_COMMAND = "git push"`, the gap command for unpushed work.

Rules (spec §7.4, in order):
1. The session has no `repo_id` through its checkout, or its remote isn't GitHub → `NO_REMOTE`. `SessionClosing` files one `repo_not_on_github` item per checkout (`checkout:<node>:<repo_root>:not_on_github`), saying orange can't close sessions there, and lets the session stop.
2. There's no branch (a detached head), or no `headSha` → `unavailable`, and the session stops as today. The note for a missing head says to update the orange plugin.
3. `done` is empty → `PASSED`, as today. A non-empty `dirtyFiles` is a gap with no fetch: "commit your changes and stop again", plus the list. A null `dirtyFiles` is not a gap.
4. `mirrors.ensure` and `fetch`.
5. **The base** is the `base-branch` convention, else the repo's `default_branch`, else `mirrors.defaultBranch`, else `main`/`master` among `origin/*`. Both branch names pass `check-ref-format --branch` and don't start with `-`.
6. **Unless the run input has `head_sha` and `rebased: true`:**
   - `onBase = branch.equals(base)`;
   - `pushedRef` is `origin/<sideBranch>` when on the base, else `origin/<branch>`;
   - `pushed` means the head object exists and `merge-base --is-ancestor head pushedRef`;
   - `nothingNew` means the head object exists and is an ancestor of `origin/<base>`;
   - neither → a gap with `PUSH_COMMAND`. The message names the exact command: `git push origin <branch>`, or `git push origin HEAD:<sideBranch>` with a line on why a side branch is used.
7. `update-ref <checkRef> <head>`; `baseSha` = `merge-base origin/<base> head`.
8. **With evidence:**
   - no commands in force → `NO_EVIDENCE`, with `EvidenceDetector.detect` run on `cloneRef(mirror, checkRef)`;
   - otherwise each command runs in `cloneRef(...)` through `Commands.run(dir, n, command, budget)`, and `n` names the container `orange-run-<id>-c<n>` in container mode;
   - the clone goes through `workspaces.discard`.
9. **`SessionClosing.integrateOrClose`:** `remote-push` off → close as `verified` with "remote-push is off, so orange left the work on its branch". The integrate input gets `on_base`.
10. **`Integrator` session target:**
    - a detached scratch worktree of the mirror at head, `<runRoot>/<run>/integrate`, removed in `finally`;
    - rebased as orange's bot;
    - `rebased` → `update-ref <checkRef> <rebased>`, and `SessionClosing` queues verify with `{stop_event_id, head_sha: rebased, rebased: true}`;
    - on the base → if `origin/<base>` is an ancestor of head, push `head:refs/heads/<base>`, then push `:refs/heads/<sideBranch>` (best effort); otherwise `behind`;
    - removed: the user's checkout, `dirty`, and the local base move for sessions.
11. **`RunPreparer.prepareSessionReview`** and **`SessionClosing.reviewRequired`** read the diff from the mirror.

- [ ] **Step 1: Rewrite `SessionCloseIT` on `FakeGithub`.**
  - The session's checkout is a clone of `FakeGithub`'s `me/<random>` repo, with `origin` = its `file://` URL.
  - The `event()` helper adds `payload.head_sha` and `payload.dirty_files` from the checkout, as the reporter would.
  - Keep every existing test's intent. Adjust the ones whose behaviour changed:
    - `uncommittedWorkIsSentBackBeforeAnythingRuns`: the gap comes from `dirty_files`;
    - `workOnTheBaseBranchIsOnlyPushed` → `workOnTheBaseBranchGoesThroughASideBranch`: first stop → push gap naming `orange/session/<id>`; push it, stop → `FakeGithub` `main` = head, and the side branch is gone;
    - `theChecksRunInAScratchCheckoutNotInTheUsersTree` → `theWorkIsVerifiedAndMergedWithoutItsCheckout`: after the stop event, delete the checkout directory, then `runAll()` → `INTEGRATED`, and `FakeGithub` `main` = head;
    - `aMovedBaseIsRebasedCheckedAgainAndThenIntegrated`: also assert that `FakeGithub` `feat` is unchanged (orange doesn't rewrite the user's branch) and `main` contains the rebased head.
  - New tests:
    - `unpushedWorkIsSentBackWithPush`: commit without pushing, stop → the verdict message contains `git push origin feat`, with no verify command evidence;
    - `aBranchThatAlreadyHasTheBaseIsFastForwardedWithItsShas`: `FakeGithub` `main` == the session head after integration (call 9);
    - `aBranchPushedFurtherThanTheStopStillVerifiesTheStoppedHead` (Review Focus #4);
    - `aRepoNotOnGithubCannotBeClosedAndSaysSoOnce`: origin is a plain bare path outside `FakeGithub` → the session stops, with one `repo_not_on_github` item over two stops;
    - `remotePushOffClosesAsVerified`.
- [ ] **Step 2: Run** `./gradlew test --tests '*SessionCloseIT'`. Expected: FAIL. The changed and new tests fail; for example, the session checkout is still read.
- [ ] **Step 3: Implement** rules 1–11.
- [ ] **Step 4: Run** `./gradlew test --tests '*SessionCloseIT' --tests '*DiffReviewIT' --tests '*EvidenceRunnerIT' --tests '*IntegratorIT'`, then the full suite into the ledger dir. Expected: all pass.
- [ ] **Step 5: Commit**: `feat(sessions): close a session's work from GitHub, never from its checkout`

---

### Task 7: Docs

**Files:** `CLAUDE.md`, `orange/.env.example`, `plugin/skills/orange-orchestrate/SKILL.md` (if it describes session close), `orange/src/main/resources/application.yaml` comments

- [ ] **Step 1: Update `CLAUDE.md`:**
  - the layout gets a `repo/` package line;
  - "Closing a session's work" follows §7.4: GitHub, the push gap, the side branch, the mirror;
  - "Verification and integration": the per-repo base, the project conventions for tasks, waiting for evidence;
  - the data model gets `repos`, `tasks.project_id`/`repo_id` and `sessions.head_sha`/`dirty_files`;
  - Environment gets `ORANGE_GITHUB_TOKENS`, `ORANGE_REPOS_ROOT` and `ORANGE_GITHUB_URL`;
  - `orange.worktrees.base-repo` is documented as the fallback for tasks without a repo;
  - Known limits gets: in process mode an agent can write the mirror's config through its worktree, so on the VPS run in container mode.
- [ ] **Step 2: Run the full suite one last time** (`./gradlew test`). Expected: all pass.
- [ ] **Step 3: Commit**: `docs: R2, GitHub is the meeting point`

---

## Not in R2 (later phases, per spec §8)

- `orange_project_create`, the attention item for a missing token, task tools in steering and the planner working in a project's repo: R4.
- Pushing stuck task branches as `orange/task/<id>` (call 13): R4, with task attention.
- Falling back to `integration: branch` on a protected base (call 6): R4, with its attention item.
- The reporter's disk queue: R3.
