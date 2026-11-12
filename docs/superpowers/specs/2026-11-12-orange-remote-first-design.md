# orange: remote first, one steering tab, testing over review

2026-11-12 · Status: draft for the user's approval · Amends: `2026-11-09-orange-project-space-design.md` (the "project-space spec") and, through it, `2026-11-02-orange-factory-v2-design.md` (the "v2 spec")

## 1. Intent

The user wants **one terminal tab** instead of many Claude Code tabs. They open Claude Code with orange connected and steer all of their projects from there. The work itself happens on the VPS by default, not on the laptop.

orange is the thing that is always on:
- it keeps memory across projects;
- it runs the agents;
- it decides what needs the user and pings them when it does;
- it shows progress to any of the user's devices on the tailnet (laptop, iPhone).

The tab can be cleared at any time without losing anything, because nothing lives in the tab. The point of orange is to save the user's time while several projects move at once.

A typical morning, in the user's words: they open the tab and type "what's the status on my projects, what's new, where should I put my attention today", and maybe "are we behind schedule or on track". Then they hand orange work ("build X in project Y"), and orange takes it from there.

**orange is one of its own projects.** It works on its own repo like on any other and improves its own code, gated by its own full test suite. In the end it deploys its own changes too (§7.9).

Two further changes of direction come with this:
- **From reviewing to testing.** The user no longer reviews every line, and neither must orange. Tests that orange ran itself are the gate. An LLM review of the diff is optional, for some projects and some tasks.
- **orange decides when to ask.** Nothing hard-codes "approve the plan first". orange judges from risk and clarity whether the user should look before work starts, and the default leans toward trust. The user wants to try trusting the AI more.

### What the project-space spec got right and what it got wrong

Right, and staying:
- the project space (goals, milestones, decisions, notes, conventions);
- evidence that orange ran itself;
- integration without PRs;
- the attention inbox, pushes and the brief;
- no LLM context that grows over time.

Wrong in emphasis: it centred on many local sessions that orange watches and answers. That stays as a secondary path, for when the user does open a local tab. The centre is now orange's own tasks on the VPS, steered from one tab.

### Success

Over one week of real use:
1. The user works from one steering tab. Code is written by orange's runs on the VPS, not in local tabs.
2. "What's the status / where do I look today / are we on track" is answered from orange's facts. The user does not need to look anywhere else to check it.
3. With the laptop closed, briefs still arrive on schedule and VPS tasks keep running to integrated.
4. The user is asked to approve a plan rarely, and only with a one-line reason that makes sense to them.
5. Nothing orange merges or pushes to a base branch has skipped the project's evidence commands. This includes session work: a session hands work to orange on a side branch, never by pushing the base itself (builder's call 11).

## 2. Goals, non-goals, anti-goals

**Goals**
1. **The coordinator runs on the VPS** (`orange-vps`), and orange is also one of its own projects (§7.9). It holds Postgres, the run loop, attention, the brief, ntfy, the steering MCP and the dashboard, all reachable only over the tailnet.
2. **GitHub is the meeting point between machines.** Every project's repo has a GitHub remote. The VPS clones it, works on it and pushes to it. The VPS never reaches into the laptop.
3. **One steering tab drives everything.** From the tab, the user can:
   - plan and dispatch work;
   - follow progress and answer orange's questions;
   - cancel and pause;
   - ask for the status of everything.
4. **Projects own their repos, and tasks belong to projects.** This lifts v2 decision 13 for tasks as well as for sessions.
5. **Testing is the gate.** Evidence commands decide "done". Diff review is opt-in.
6. **Plan approval by judgement.** orange decides whether a plan needs the user before work starts, and says why in one line.
7. **"On track" is a fact.** Milestones know the tasks that serve them, so orange can compute progress against time.
8. **Visibility from any tailnet device.** A dashboard reachable from the iPhone, plus pushes for what needs the user, stuck tasks included.

**Non-goals**
- A node process on the laptop. There is no node work protocol and no run is ever dispatched to the laptop (this replaces project-space §6.1).
- Working on repos that only exist on the laptop.
- Typing into, resuming or controlling the user's local sessions (unchanged).
- Multiple users (unchanged).
- PR mode (still later, unchanged).

**Anti-goals.** These count as failure even if everything works:
- Approval fatigue: orange asks so often that the user approves without reading.
- A merge that passed because nobody tested it: a project with no evidence commands gets merged.
- The user has to SSH into the VPS to use orange, or to find out what it is doing.
- Something important lives only in the tab's context, so clearing the tab loses it.
- The VPS's GitHub credential or the subscription token reaches an agent's container, a log or the database.

## 3. Constraints

- Everything in the v2 spec §3 and the project-space spec §3 still applies, apart from the topology and node-credential points replaced in §6 here. That covers Java 27, Spring Boot 4, Postgres 18, the subscription-token rules and no API spend.
- **The VPS** (surveyed 2026-11-11, prepared 2026-11-12):
  - `orange-vps` (its addresses and access live in the operator's SSH config, not in this repo);
  - Ubuntu 24.04.5, 4 vCPU, 7.8 GB RAM, 96 GB disk;
  - installed: Docker 29.9.3 with Compose v5.7.1, Temurin JDK 27.0.1, git 2.43, Tailscale 1.104.6;
  - nothing else runs on it. The deploy hardens SSH and the firewall (builder's call 3).
- **The tailnet** holds the user's laptop, their phone and `orange-vps`.
- **Container rules from v2 PR 6 stand.** Everything that executes what an agent wrote runs in a container. orange never runs git in a directory a container wrote. A coordinator running as root is refused.
- **The Max plan is shared.** The user's own Claude Code use and orange's VPS runs draw on one subscription. The existing plan-limit pause is what protects it.

## 4. Decisions

**Made by the user (2026-11-11/12)**
1. The VPS is `orange-vps`. Tailscale joins it to the laptop and the iPhone. I deploy and operate it over SSH; the user does not.
2. ntfy is self-hosted on the VPS, reachable only over the tailnet.
3. The VPS works only on repos it can clone from GitHub. It never needs access to the laptop. Work is either done remotely or pushed immediately.
4. Closing a laptop session's work (verify and merge) may happen on the VPS from what the session pushed.
5. orange may work in every repo the user runs sessions in.
6. One steering tab replaces many tabs. orange steers the work, which runs on the VPS by default. It is always on, keeps memory between projects, and pings the user when needed. Full visibility and progress tracking come from any device on the tailnet.
7. No hard caps on plan approval: orange decides whether the user should approve before implementation, by risk and clarity.
8. Review is not mandatory. The move is from reviewing to testing. Review stays only for some projects and some tasks, as an option.
9. orange-ai itself is a project orange works on: a self-improving orange that changes its own code. Its real test suite (Testcontainers, which needs Docker) is its gate on the VPS, so evidence commands may get a Docker of their own in a sandboxed container (§7.8).
10. There is no fixed project list. Any repo of the user's account is a candidate, and orange works on one when the user asks for it specifically, normally from the steering tab. Repos of the user's organisations are allowed too, again only once the user names them.
11. orange installs what a repo needs, or uses the right Docker image for it. The user never prepares a toolchain by hand (§7.8).

**Builder's calls (flagged so the user can check them)**
1. **orange runs on the host as a systemd service,** as a dedicated non-root user `orange` in the `docker` group, from a jar built on the laptop and copied over. It doesn't run in a container, because orange starts the run containers and a containerised orange would need the Docker socket. Postgres and ntfy run in Docker Compose, published on 127.0.0.1 only.
2. **The tailnet entry is `tailscale serve`.** It gives HTTPS on `orange-vps.<tailnet>.ts.net` with a real certificate, proxying to orange and to ntfy on 127.0.0.1. orange itself listens on 127.0.0.1 and on the run network's gateway address only, so containers reach `/mcp/worker` and nothing else. Every request reaching orange through `serve` comes from 127.0.0.1, so `ORANGE_API_TOKEN` is mandatory on the VPS. `RunNetworkGuard` is checked against real Linux bridge routing as part of the deploy.
3. **Firewall and SSH.** ufw: deny incoming by default, allow everything on `tailscale0`, allow 22/tcp publicly. sshd: key only, `PermitRootLogin prohibit-password`. Public SSH stays as the way back in if Tailscale breaks.
4. **iOS pushes from self-hosted ntfy.** The ntfy server sets `upstream-base-url: https://ntfy.sh`. The iPhone app can only be woken through APNs, which self-hosted servers reach through ntfy.sh. Only a hash of the topic URL and the message id are forwarded, never the content. The builder checks this against ntfy's current docs before relying on it.
5. **A fresh database on the VPS.** The laptop's local orange data (a few dev rows, one proposed project) is not migrated. The laptop's local orange and compose stack are stopped once the VPS is live, and the plugin points at the VPS.
6. **The GitHub credential is a fine-grained personal access token per owner.** The first one is for the user's account. Scope: all repositories; Contents read/write, Metadata read, and Administration write only for creating repos (§7.1). The user creates it, and it goes in the VPS's environment file.
   - The first time the user names a repo of an organisation, orange checks whether it has a token for that owner. If not, it files one attention item with a link to create one (scoped to that org, the same permissions), and the project waits for it.
   - A repo whose base branch rejects orange's push, for example because it's protected, falls back to `integration: branch` for that project. orange says so once in an attention item.
   - orange uses it only for its own git (clone, fetch, push) through a credential helper. It never goes into a container: agent clones have no remote, as in PR 6.
   - It is never logged or stored, like the subscription token.
7. **The VPS's subscription token** is a `claude setup-token` token that the user generates in their own terminal. It is copied to the VPS's environment file over SSH by a script the user runs, so it never appears in a conversation. The laptop's token in `orange/.env` may be reused if the user prefers; either way it is one Max plan.
8. **The steering tab is never judged.** Its stops are the user talking to orange, not work to answer. The plugin marks a session as steering (for example, Claude Code started from the plugin's `orange` command, or in a dedicated directory). The reporter tags it, and orange records it but never queues a judge for it or files attention about it. The mechanism is left to the builder.
9. **Fast-forward first.** When orange integrates a session's branch and the base is already its ancestor, it fast-forwards without rebasing, so the laptop's branch keeps its SHAs. It rebases only when it has to; `git rebase` then drops commits the user's later pull already finds upstream.
10. **The review default is `risky`** (§7.3). If the user would rather start at `never`, that is a one-line change.
11. **Sessions never push the base for orange.** A session that worked on the base branch is asked to push its head to a side branch, `orange/session/<short id>`. orange verifies it there, fast-forwards the base to it and pushes the base, then deletes the side branch. Until the user pulls, their local base is ahead of origin with the same commits; the pull is then a no-op fast-forward. What the user pushes to the base by hand, outside orange, is theirs and is not gated.
12. **The first evidence commands of a project.** In a project with no `evidence` convention in force, a plan always asks first (`ask_first: true`, reason "no tests in force yet"). It carries the planner's proposed evidence commands, usually what the first task sets up. Approving that plan is the user putting those commands in force: the convention becomes active from the user's resolve, not from orange. This keeps the rule that only the user accepts evidence, and lets a new repo's first plan merge.
13. **Task branches stay on the VPS unless a task is stuck.** orange pushes only base branches after verified work, plus the branch of a task that went `needs_human` or `failed`, as `orange/task/<short id>`, so the user can look at it from the laptop without SSH. The task's attention item names it. orange deletes those branches when the task is integrated or cancelled.
14. **orange's guards ask first when orange changes them.** In orange's own project, the risk paths add the packages that hold its safety rules: `harness/`, `config/`, `mcp/`, `roles/`, `run/RunWorkspaces`, the migrations, the worker image and the deploy files. A plan that touches them asks first and its tasks get a diff review, whatever the `review` default says. This stops the self-improving loop from loosening its own containment unnoticed.
15. **orange's own base branch is `orange-e2e-base`** (the project's `base-branch` convention), not `main`, matching how its PRs are merged today.

## 5. Concepts

Unchanged from the project-space spec §5, with these additions:
- **Project repo.** A GitHub repository, identified by its remote URL (`owner/name`). The VPS keeps one mirror clone of it. A laptop checkout of the same repo maps to the same project through its remote URL, not through its path.
- **Steering session.** The user's one tab. orange records it but never judges it (decision 8).
- **Risk flag.** Every planned task and every plan carries `risk: low | high` and a one-line reason. It drives plan approval (§7.2), review (§7.3) and routing (the existing `orange.routing.risk`).

## 6. Topology (replaces project-space §6.1)

```
 laptop (sleeps sometimes)                    tailnet                 orange-vps (always on)
 ┌───────────────────────────────┐                        ┌──────────────────────────────────────┐
 │ steering tab (Claude Code)    │ ─ steering MCP, HTTPS ▶│ tailscale serve → orange (systemd)   │
 │ plugin: reporter hooks        │ ─ session events ─────▶│   run loop, attention, brief, judge  │
 │   + disk queue                │ ◀─ verdict long-poll ──│   integrator, evidence, dashboard    │
 │ occasional local sessions     │                        │ docker: run containers (PR 6)        │
 └───────────────┬───────────────┘                        │ compose: Postgres, ntfy (127.0.0.1)  │
                 │ git push                               └───────────────┬──────────────────────┘
                 ▼                                                        │ clone / fetch / push
             GitHub  ◀────────────────────────────────────────────────────┘
 iPhone ◀── ntfy push (APNs via ntfy.sh, content stays on the VPS) · dashboard over the tailnet
```

- **One machine is orange.** The VPS runs everything stateful and everything that executes. The laptop runs the plugin and nothing of orange: no Java, no Postgres, no orange process.
- **`node_id` stays a label** on sessions and repos: which machine a session reported from. Nothing is dispatched by it.
- **The reporter queues on disk.** When orange is unreachable, the reporter appends the event to a queue under `~/.orange/node/queue/` and returns at once, within its existing 2 s budget. The next hook that gets through sends the queue first, in order.
  - Events keep their reporter event id, so replay is idempotent.
  - A `stopped` event older than `hook-wait` is applied for attention and the brief only. No verdict is ever delivered for it (unchanged rule, now actually exercised).
  - The queue is bounded (builder's call; drop the oldest beyond, say, 1,000 events or 7 days).
- **Repos meet on GitHub.**
  - A laptop session pushes its branch.
  - The VPS fetches it, checks it and merges it into the base, then pushes the base.
  - The user's laptop checkout is never touched by orange. After a merge, the user pulls, or the next session does.

## 7. What changes

### 7.1 Projects own GitHub repos; tasks belong to projects

- `project_repos` is keyed by the remote, normalised to `owner/name` on GitHub, instead of by `(node_id, repo_root)`. A laptop checkout's path is kept as information.
  - A repo without a GitHub remote can still be watched as a session repo. orange can neither do task work in it nor close its sessions, and it files one attention item saying why.
- **The VPS keeps one mirror clone per repo** under its data directory. It fetches before every piece of work.
  - Task worktrees are made from the mirror, per repo; `WorktreeService` loses its single `base-repo`.
  - Container runs clone from the task's worktree, as today.
  - The integrator works in a checkout of the mirror and pushes to GitHub with the PAT.
- **`tasks` gains `project_id` and `repo_id`.** Plans and dispatch name a project; a project with one repo needs no repo argument.
- **Creating a project from the tab**: `orange_project_create(name, repo?)`.
  - With an existing `owner/name`, orange checks access and clones it.
  - With `create: true`, orange creates a private repo under the user's account with a first commit (needs the PAT's Administration permission).
  - Either way the project is `active` at once: the user asked for it, so no confirmation item is filed.
  - `evidence` may be passed here too; otherwise builder's call 12 applies.
- **Evidence for a new project**: orange proposes evidence commands from what it finds in the repo (`EvidenceDetector`, unchanged). The user must still accept them, as today; until then orange can implement but not integrate.

### 7.2 Plan approval by judgement

- The plan run gains, per plan, `ask_first: boolean` plus a one-line `reason`, and, per task, `risk: low | high` plus a reason.
- The planner sets `ask_first` when any of these holds:
  - the request is ambiguous enough that a wrong reading builds the wrong thing;
  - a task touches the risk paths (`orange.routing.risk.paths`);
  - the plan is large (builder's threshold, in config);
  - it includes something irreversible or outward-facing (project-space decision 4's list).
- **`ask_first: false`**: the plan dispatches at once. It shows in the next status, brief and dashboard.
- **`ask_first: true`**: one attention item, urgency `urgent`, holding the plan and the reason; the tasks wait. The user answers from the tab (`orange_resolve`: approve, revise with a note, or drop) or from the phone's link to the dashboard.
- **A convention overrides the judgement** in either direction: `plan-approval: auto | always | never`, default `auto`.

### 7.3 Testing over review

This supersedes the project-space spec's goal 5 ("It also does an LLM read of the diff"), §7 `verify` step 2, and §10's "`diff_review`, which is part of verify". It also supersedes the v2 spec's "a review run after the PR opens".

- **Evidence commands are the gate**, for tasks and sessions alike.
- **Diff review runs only when asked for:**
  - a `review` convention: `never | risky | always`, default `risky`;
  - `risky` reviews tasks the planner flagged `risk: high` and session work whose diff touches the risk paths;
  - a per-task override from the plan (`review: true`).
- **When review is off, verify ends after the evidence commands**; a pass goes straight to `verified`.
- **Acceptance moves into tests.** The planner states each task's acceptance as tests to add or extend. The implement role prompt requires those tests to be committed with the change. This isn't checked mechanically; the evidence commands running the tests is the check.
- **What does not change:** "no evidence commands, no integration". A project without an `evidence` convention in force is never merged by orange.

### 7.4 Closing a session's work from GitHub (amends project-space Phase C)

- **At a stop, the reporter adds two facts to the `stopped` event:** whether the checkout has uncommitted or untracked files, and the head SHA. It already reads git at every hook.
- **When the judge says `verify`, the VPS:**
  1. treats uncommitted files as a gap ("commit your changes and stop again");
  2. fetches the session's branch from GitHub. If the head isn't there, that's a gap: "push `<branch>` and stop again";
  3. runs the evidence commands at that head, in a scratch checkout of the mirror, each command in a container (as for tasks in container mode);
  4. runs a diff review only if the `review` convention asks for one (§7.3);
  5. integrates per the `integration` convention in a checkout of the mirror: fast-forward if possible, otherwise rebase (builder's call 9). Then it pushes the base. A session that worked on the base branch itself pushes to a side branch first (builder's call 11); orange fast-forwards the base to it.
- **Gaps go back into the session as a `continue`**, exactly as today.
- **Removed:** the scratch worktree of the user's checkout, moving the user's local base branch, and the rule about a dirty main checkout. orange no longer touches the laptop's files.

### 7.5 The steering tab drives tasks (v2 PR 5, reshaped)

- **`/mcp/steering` gains the task tools:**
  - `orange_plan(project, request)`: plan, then dispatch or ask per §7.2;
  - `orange_status`: everything in flight, by project;
  - `orange_task(id)`, `orange_tail(id)`;
  - `orange_cancel(id)`, `orange_pause`, `orange_resume`;
  - `orange_project_create` (§7.1).
- The Python `orange-mcp` shim and its tools are deleted. The repo's `.mcp.json` entry goes, and the plugin's skills are rewritten around the one-tab flow:

  status → hand over work → follow → answer.
- **A stuck task becomes attention.** A task's `needs_human`, a `request_human` from a run, and a failed task each file an attention item with the task's context and orange's proposed answer if any. Urgency: `urgent` for `needs_human` and `request_human`, `normal` for `failed`. The user answers with `orange_resolve`, which reopens the task with the answer as its note.
- **The tab starts with context.** The plugin's SessionStart hook, in a steering session only, adds a short summary from orange: active projects, what's running, what waits for the user. "What's the status" then starts from facts even before the first tool call. Builder's call on the exact text; it stays a few lines.

### 7.6 On track or behind (amends project-space Phase D)

- **Planned tasks remember the milestone they serve** (`tasks.milestone_id`), when the request names one or the planner matches one. The link follows the milestone's supersede chain, so editing the milestone keeps its tasks.
- **The brief's facts gain, per dated milestone,** the share of time gone and its tasks by state (merged / running / stuck / not started). orange's own rule code (like `Quiet`), not the model, labels it **on track** or **behind**:
  - behind = the share of merged tasks trails the share of time gone by more than a configured margin;
  - or any of its tasks is stuck.
- **Milestones without a date** show their tasks by state, with no on-track label.
- **VPS tasks appear under their projects** in the brief and in `orange_focus`, not as "orange's own tasks".

### 7.7 Dashboard over the tailnet (v2 PR 7, reshaped)

- **Server-rendered pages** (no SPA), readable on the iPhone, behind `ORANGE_API_TOKEN`:
  - per project: goals, milestones with on-track state, tasks by state, recent merges;
  - live runs with their latest events;
  - the attention inbox with resolve buttons;
  - the latest brief;
  - spend today against the plan;
  - the scheduler's pause state.
- **Auth from the phone:** the token is given once, then kept as a cookie for the tailnet origin; the builder decides the mechanism.
- **Pushes link to the relevant page.**

### 7.8 The right image per project, and Docker for evidence that needs it

This replaces PR 6's "one worker image per deployment" and its "orange never builds or pulls the image itself".

- **Each project has a worker image**, held by a `worker-image` convention. When it's unset, orange works one out from the repo, records it as the project's convention (no acceptance needed: an image is not a command that runs on the host) and builds it:
  - a base image: the repo's `.devcontainer` image or Dockerfile `FROM` when there is one, else an official image for the language and version the build files name (`eclipse-temurin:27-jdk`, `node:22`, `python:3.13`, `rust:1`…);
  - orange's layer on top: Claude Code at the pinned version, git, ripgrep, the agent user with orange's uid. That's today's `worker/build.sh --base`, run by orange.
  - Images are tagged by project and by a hash of their inputs, built once and rebuilt when the inputs change. A failed build is an attention item, not a silent fallback.
- **Agents may install more** inside their container, as today (they have the network and `bypassPermissions`). If a run finds it needs a system package, it proposes an `.orange/worker.Dockerfile` in the repo. That file is extra layers orange adds to the project's image at its next build, and it travels with the repo like any committed change: tested, and merged only through evidence.
- **Docker for evidence** is per project: `evidence-docker: true`. That project's evidence commands then run in a container under the sysbox runtime (`--runtime=sysbox-runc`), which gives the container its own Docker daemon without the host's socket and without `--privileged`. The deploy installs sysbox on the VPS. orange-ai sets it, so its Testcontainers suite is its gate.
  - The rule that no container gets the host's Docker socket stands.
  - Agent runs (implement, ci_fix) don't get a Docker daemon; only evidence does. An agent that needs one to test locally gets that through evidence, as `ci_fix` feedback.
  - The builder checks sysbox's support for the VPS's kernel (6.8, Ubuntu 24.04) and for the Docker version at deploy time.

### 7.9 orange deploys itself (the self-improving loop)

- **The loop:** a change to orange's own repo goes through the same path as any task: plan, implement, its full suite as evidence under sysbox, then integrate to `orange-e2e-base` and push. Deploying the change is the extra step.
- **A supervisor outside orange deploys it.** It is a small script with a systemd unit, not part of orange's jar, so a broken orange can't break its own rollback. It runs:
  1. build the jar from the integrated head;
  2. `pg_dump` when the change adds a migration;
  3. stop orange (its runs are reaped as `coordinator_restart`, which doesn't count against them), start the new jar;
  4. health-check it.
  
  If the health check fails, it puts the previous jar back. A change with a migration can't be undone by swapping the jar back, so it restores the dump too, and files a critical attention item when that's needed.
- **When orange may deploy itself:**
  - after any integrated change of its own repo that adds no migration and touches none of the guard paths (builder's call 14);
  - only with the user's approval for changes that add a migration or touch the guards;
  - never while a run of another project is mid-integration. The supervisor waits for the integrate slot to be free.
- **Every deploy is recorded** (head, result, rollback if any) and shown in the brief and on the dashboard. A failed deploy and its rollback push a notification.

## 8. Phasing (what gets built next, one PR each)

The order is chosen so each PR is useful on its own and the VPS is deployed once, in its final shape.

1. **R1 · Review becomes optional (§7.3).** Small.
   - The `review` convention.
   - verify ends after evidence when review is off.
   - Task `risk` and `review` fields; until R4's planner sets them, they come from the existing risk paths.
   - CLAUDE.md, the role prompts and the tests follow.
   - Exit: ITs where a task and a session merge with no diff review, and where `always` and a risky task still get one.
2. **R2 · GitHub is the meeting point (§7.1, §7.4).**
   - Repos by remote; mirror clones; `tasks.project_id`/`repo_id`; multi-repo worktrees and integration.
   - The PAT credential helper.
   - Session close from GitHub, with the reporter's dirty and head facts.
   - Tested on local bare repos standing in for GitHub.
   - Exit: two projects in two repos get task work integrated and pushed in one orange. A session's pushed branch is verified and merged with no access to its checkout, and an unpushed one is sent back with "push".
3. **R3 · orange on the VPS (Phase V).**
   - A deploy script run from the laptop: build the jar and the worker image, `orange` user, systemd unit, compose for Postgres and ntfy, `tailscale serve`, ufw and sshd hardening, environment file, health check.
   - The reporter's disk queue and replay.
   - The plugin pointed at the VPS; the laptop's local orange retired.
   - Exit, the original Phase V one: close the laptop, and the brief arrives on schedule and a VPS task runs to integrated on the sandbox repo. On wake, the queued events replay and no stale verdict is delivered. Also: the run network guard holds on Linux (a container gets 403 off `/mcp/worker`), and an iPhone push arrives.
4. **R4 · The steering tab drives tasks (§7.2, §7.5, §7.6).**
   - Task tools in `/mcp/steering`.
   - Plan approval by judgement.
   - Task attention.
   - The steering session tag and its start context.
   - Milestone links and the on-track facts.
   - The `orange-mcp` shim deleted and the plugin rewritten.
   - Exit: from one tab, the user creates a project, hands it a goal, and gets status and on-track answers. One plan dispatches on its own and a risky one asks first, live on the VPS.
5. **R5 · Images per project and Docker for evidence (§7.8).**
   - Image detection, orange-built images, `.orange/worker.Dockerfile`.
   - sysbox on the VPS and `evidence-docker`.
   - orange-ai registered as a project with `evidence-docker: true` and its guard paths.
   - Exit:
     - a Node repo and a Java repo each get the right image without the user preparing anything;
     - orange's own `./gradlew test` passes as evidence on the VPS.
6. **R6 · Dashboard (§7.7).** Exit: the pages render against IT data, and the user follows a running task from the iPhone.
7. **R7 · orange deploys itself (§7.9).** The supervisor, the deploy record and the approval rule. Exit:
   - orange improves itself on a small task the user names, merges it and deploys it with no SSH;
   - a deliberately broken build is rolled back on its own.

After these, the remaining v2 work follows, re-scoped by this spec: scope claims (v2 PR 4), reactions for PR mode (v2 PR 3, only if a project wants `integration: pr`), UI QA (PR 8), OpenCode (PR 9 and project-space Phase E).

## 9. Testing

- Unit:
  - remote URL normalisation;
  - the plan-approval rule and its convention;
  - the `review` convention;
  - on-track labelling;
  - the reporter's queue (bounded, ordered, idempotent replay).
- Integration (Testcontainers Postgres, local bare repos as GitHub):
  - multi-repo tasks;
  - session close from a remote (pushed, unpushed, dirty, fast-forward, rebase);
  - review on and off;
  - task attention and resolve;
  - steering task tools.
- The fake `claude` gains:
  - plan scenarios with `ask_first` true and false;
  - per-task risk;
  - a `[[fake:no-tests]]` implement that commits code without tests, to show that evidence, not review, catches it.
- The deploy is checked live on the VPS (R3's exit). It is not unit-tested; the deploy script is idempotent and safe to rerun.

## 10. Left to the builder

- The deploy script's shape and where the jar is built.
- The ntfy and `tailscale serve` layout (ports, paths).
- The reporter queue's bounds.
- The plan-size threshold for `ask_first`, and the on-track margin.
- The steering-session marker.
- The dashboard's pages and look, within §7.7.
- The start-context text.
- Prompt wording for the planner's `ask_first` and `risk`.
- Whether `orange_status` and `orange_focus` share code.

## 11. Known limits carried forward

- **Internet egress from run containers is open** (no proxy; PR 6). On the VPS that matters less than on the laptop, because nothing private sits on its loopback address apart from Postgres and ntfy, which use passwords and tokens anyway.
- **Until R5 lands, the default worker image is the only one.** It carries JDK 27, Node LTS and Python 3, and evidence that needs Docker can't run. So orange-ai's own work on the VPS starts with R5.
- **The Max plan is shared** between the user and orange (§3).
