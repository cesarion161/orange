# orange: project space, session attention and evidence-gated integration

2026-11-09 · Status: approved by the user 2026-11-09; Phases A, B, C and D built 2026-11-10 · Amends: `2026-11-02-orange-factory-v2-design.md` (the "v2 spec")

> **Amended 2026-11-12 by `2026-11-12-orange-remote-first-design.md`.** Diff review is optional (testing is the gate); §6.1's laptop node is replaced by GitHub as the meeting point, with everything on the VPS; Phase C's session close runs on the VPS from what the session pushed; Phase V is re-planned there. Read that document first where they disagree.

## 1. Intent

The user works on several things at once: several interactive Claude Code sessions, sometimes OpenCode, across more than one project. They either get distracted or go into full focus on one session. Either way, the other sessions finish or stall and then sit idle until the user happens to look. By then the user has lost the context of what each one was doing. Idle time is too long, and switching back costs too much.

orange becomes the orchestrator of the user's work, not only of the tasks it runs itself:

- **It watches every session,** whether the user started it or orange did, and whichever harness it uses.
- **It handles what it can.** When a session stops, orange decides what happens next:
  - It answers routine questions on the user's behalf. For example, "should I proceed with this design?" gets a "yes" when the design fits the project's goals and recorded decisions.
  - It verifies finished work, then closes it and integrates it.
- **It pushes the rest to the user,** with enough context to act without rereading the transcript. "The rest" means critical decisions, work it can't verify, and real conflicts.
- **It reports on all projects:**
  - progress against each project's goals, and against its timetable where one exists;
  - what deserves the user's focus right now;
  - which projects have gone quiet.
- **It runs indefinitely.** No LLM session in orange grows over time. All memory lives in Postgres. Every judgement is a fresh, short run that is given the slice of state it needs.

The second change: **the PR-centred core of v2 is removed.** A pull request says nothing about whether work is correct. In v2, PRs did two jobs: integrating work and syncing parallel agents. orange now does both itself, with branches and worktrees. Correctness is gated on **evidence**, meaning checks orange ran itself. A PR becomes one optional way to publish, chosen by project convention.

### Success

These are measured over one week of real use on the user's actual projects:
1. A stopped session waits for someone to act for a few minutes at most. The idle time is measured from the hook events.
2. At least half of all session stops are handled by orange without the user.
3. No critical decision is taken without the user. The audit is in `space_items` and `attention_items`.
4. Pushes are rare enough that the user acts on almost every one, so none are ignored as noise.
5. The user goes to the focus brief instead of checking sessions by hand.

The numbers are the builder's call and are recorded as config, so they can be tuned.

## 2. Goals, non-goals, anti-goals

**Goals**
1. **A project space** per project. It holds goals, optional milestones (with or without dates), conventions, decisions, notes, and proposals. Three kinds of author write to it: the user, orange, and agents. Agents can only propose.
2. **Many projects in one orange.** A project has zero or more repos. This lifts v2 decision 13 ("one target repo per deployment").
3. **Session reporters**, one per harness. They turn session lifecycle events into one neutral event format. The first is for Claude Code, through hooks in the orange plugin. OpenCode follows, through its plugin events.
4. **The session judge.** On every stop, orange decides one of: continue (with a message fed back into the session), verify-and-close, or escalate. For the user's interactive sessions, the verdict reaches the session through the stop hook.
5. **Evidence.** orange runs the project's checks itself (tests, build, lint, and whatever else the conventions list) and records the results. It also does an LLM read of the diff against the stated intent. Work is "done" only when its convention's evidence is present.
6. **Integration without PRs.** By default, orange rebases verified work onto the base branch and merges it. Conventions can choose PR, leave-on-branch, or ask-first instead.
7. **Attention.** One `attention_items` inbox collects everything that wants the user. Urgent items are pushed right away and the rest are ranked into the focus brief.
8. **The focus brief.** A short report across all active projects: progress against goals, timetable status where one exists, what's quiet, what's waiting on the user, and a ranked "focus on this next". It is sent at scheduled times and is available on demand.
9. **Conventions instead of hardcoded behaviour.** Definition of done, integration mode, required evidence, what orange may decide alone, base branch, timetable, brief schedule, and quiet hours are all conventions. Each has a global default, a per-project value, and a per-session override.

**Non-goals**
- Controlling the user's terminal. orange never types into a TUI, opens or closes windows, or kills a session the user started. It acts only through the hook verdict and through its own runs.
- Taking over the user's session headlessly (`--resume` from orange). This can be reconsidered later.
- Multiple users.
- Two-way state sync between machines. There is one coordinator and one database; the other machines are nodes.
- A web UI beyond the v2 thin dashboard.
- Calendar integration.
- Mandatory deadlines, milestones, or PRs for any project.
- Learning conventions automatically. Agents may propose conventions; they don't become active by inference.

**Anti-goals.** These count as failure even if everything works:
- orange takes a critical decision silently.
- Pushes become noise the user learns to ignore.
- Some part of orange keeps a growing LLM context.
- "Done" or "passed" is believed because a session said so. Evidence comes from commands orange ran.
- A project is forced into deadlines, PRs, or any one workflow by design.
- The platform becomes the project again. The first phase must be useful on the user's own sessions within days.

## 3. Constraints

- Everything in v2 §3 still applies: Java 27, Spring Boot 4, Postgres 18, Flyway, jOOQ, `uuidv7()`, `touch_updated_at`, `pg_notify`, subscription-token rules, and no API spend.
- **Subscription billing.** Every judge, verify-review and brief run is a `claude -p` run on the Max plan. They go through `AgentRunService` and its routing, budget and plan-limit pause, like any other run. These runs are frequent, one per session stop, so they route to the small or medium tier at low effort by default.
- **Claude Code hook mechanics** that the design relies on. The builder checks each against the installed version and records the result, as v2 §3 did for flags:
  - a `Stop` hook may return `{"decision":"block","reason":"…"}`, which stops Claude from stopping and gives it `reason` as its next instruction;
  - the hook input carries `session_id`, `transcript_path`, `cwd` and `stop_hook_active`;
  - a hook's `timeout` is configurable per hook;
  - `SessionStart`, `UserPromptSubmit`, `Notification` and `SessionEnd` hooks exist and receive `session_id` and `cwd`.
- **Checked for Phase A (2026-11-10, Claude Code 2.1.321, against the hooks, plugins and MCP references):**
  - a plugin declares hooks in `hooks/hooks.json` under a top-level `"hooks"` key; `${CLAUDE_PLUGIN_ROOT}` works in `command`; `timeout` is per hook, in seconds;
  - every hook's stdin carries `session_id`, `transcript_path`, `cwd` and `hook_event_name`; the hook process also gets `CLAUDE_CODE_SESSION_ID`;
  - `Stop` carries `last_assistant_message` and `stop_hook_active`, so the reporter never reads the transcript;
  - `Notification` carries `message` and `notification_type` (`permission_prompt`, `idle_prompt`, `elicitation_dialog`, …);
  - `UserPromptSubmit` carries `prompt`; `SessionStart` a `source`; `SessionEnd` a `reason`;
  - SessionEnd hooks share a 1.5 s budget that a plugin hook cannot raise, so the reporter does no git there and waits 1 s at most;
  - an `http` hook type exists, but the reporter stays a command hook: it has to resolve git on the node and translate to the neutral format;
  - Claude Code caps consecutive stop-hook continuations at 8 (`CLAUDE_CODE_STOP_HOOK_BLOCK_CAP`), above the loop guard's default of 5;
  - still to check in Phase B: whether the TUI accepts input while a Stop hook runs (not documented).
- **OpenCode mechanics** (to check in its phase): plugins can subscribe to session events (for example `session.idle`), and the SDK client can send a message into a session.
- **Topology** (§6.1). The coordinator must keep working while the laptop sleeps. Everything that reads or changes files runs on the machine where those files live. Nodes reach the coordinator over Tailscale, using a per-node token. The coordinator never needs to reach into the laptop. Transcript content reaches the coordinator only as the excerpts a node sends with an event; full transcripts stay on the node.

## 4. Decisions

**Made by the user in this conversation (2026-11-09)**
- The project space holds whatever the user, orange, or a subagent puts there. Subagents propose to orange. No field is mandatory, deadlines included.
- orange handles a session stop itself when it can, and pushes to the user when it can't. Two examples:
  - Answer "should I proceed with this design?" with yes, if the design is good and fits the long-term goal.
  - When the feature is done, check the work and close the session.
- Critical decisions go to the user.
- What "done" requires depends on project conventions or on the user's preference for that session. It is not hardcoded.
- Correctness comes from evidence such as tests that actually ran, not from a PR.
- Remove the PR-centred core. Projects may use plain branches and worktrees with no PRs. orange takes over syncing and conflict resolution.
- orange merges verified work itself by default, subject to project conventions.
- The brief compares progress to goals, tracks the timetable if there is one, and reports briefly on all current projects. It says what deserves focus while orange handles subagents in the background, and it pings the user occasionally when something urgent needs them.
- Harness-agnostic: Claude Code now, OpenCode sometimes.
- Pushing the base branch to the remote after a verified integration is routine, not critical. orange pushes freely whenever a remote exists.
- Subagents must be able to run on a VPS without the laptop being awake. The user doesn't want to operate orange through a terminal over SSH.
- Phase A may run in parallel with the in-flight PR 2, with conflicts resolved at merge time.

**Builder's calls (flagged so the user can check them)**
1. **Agents only propose.** Agent-written space items start as `proposed`. The judge or the user accepts or drops them. Conventions say whether orange may accept a given kind on its own. By default, orange may accept notes and decisions within the current goals, but not new goals or convention changes.
2. **Verdicts reach interactive sessions through the stop hook.** The hook waits for orange's verdict, up to `orange.attention.hook-wait` (default 10 minutes, so verification can run). It then blocks with a `continue` message, or lets the stop happen. The session is idle anyway while it waits.
   - If the user starts typing (a `UserPromptSubmit` from that session), the user wins: the pending verdict is cancelled.
   - If orange is down, the hook fails open: the session stops normally.
3. **Loop guard.** orange auto-continues a session at most `max-auto-continues` times in a row (default 5) without a user prompt in between. After that it escalates. `stop_hook_active` is passed to the judge.
4. **What counts as critical,** unless conventions override it:
   - irreversible or outward-facing actions: deleting data, publishing releases or packages, sending messages to other people, spending money, changing credentials or security settings, force-pushing or rewriting shared branches. A normal push of the base after a verified integration is not on this list (user decision);
   - anything that adds, removes or contradicts a goal or an active decision;
   - anything the judge rates below `auto-answer-confidence`;
   - anything on the project's `always-ask` list.
5. **Push discipline.** Only `urgent` attention items are pushed immediately. Urgent pushes are batched into one push per 10 minutes, and held during quiet hours unless `critical`. Everything else waits for the brief.
6. **Brief schedule.** By default at 09:00 and 14:00 local, plus `orange_focus` on demand. Delivered as an ntfy push with the full brief at a dashboard URL.
7. **Integration default:** rebase the branch onto the base, re-run the required evidence if the rebase changed anything, then fast-forward the base. Push to the remote whenever one exists (user decision). A project can turn this off with `remote-push: false`.
8. **Orange doesn't rebase under a live interactive session.** A worktree that belongs to an active attended session is rebased only at a stop that orange is handling, or at integration. Orange-run worktrees are rebased whenever the base moves.
9. **Session-to-project mapping.** A session's `cwd` is resolved to a repo root, through the worktree's common git dir, and then to a project. An unknown repo creates a `proposed` project and a low-urgency attention item asking the user to confirm or ignore it.
10. **The unit of attended work is an intent, not a session.**
    - A session holds a sequence of `session_work` items. The first prompt opens one.
    - A close ends that item. If the user prompts again after a close, a new item opens with the new prompt as its intent.
    - orange never ends the session itself.
11. **Sessions on the base branch.** When a session works directly on the base branch in the main checkout, orange never moves refs under the user's working tree. "Integrate" then reduces to:
    - check that the work is committed (if uncommitted, a `continue` asks the session to commit);
    - push, per the `remote-push` convention.

    For sessions on their own branch or worktree, the integrator runs in its own checkout: a dedicated orange-owned worktree of the base branch per repo, under `~/.orange/integration/<repo>`. If the user's main checkout has the base branch checked out and is clean, orange moves it with `git merge --ff-only` there. If it is dirty, orange leaves it alone and says so in the close note.
12. **Late verification results.** The stop hook waits up to `hook-wait`. If verify finishes later:
    - gaps are delivered on the session's next `UserPromptSubmit` as added context ("orange verified your last work: tests X fail");
    - if the session stays idle past `waiting-escalate-after`, the gaps also become a `normal` attention item;
    - a passing late verify closes and integrates as usual.

    If the installed Claude Code turns out to block input while a Stop hook runs, `hook-wait` drops to 60 seconds and the late path becomes the normal one. The builder checks this in Phase B and records the result in §3.
13. **Isolation for the new runs in process mode.** This is an exception to the v2 rule that live runs happen only in containers.
    - `judge`, `diff_review` and `brief` get Read, Glob, Grep and orange MCP tools only: no Bash, no Write or Edit, and no network tools. That is the same exposure as the user reading their own repo.
    - Evidence commands run on the host as the user, and only from an `evidence` convention the user set or accepted. An agent's proposal of evidence commands can never be accepted by orange itself.
    - `implement` and fix runs keep the v2 rule: containers before live runs.
14. **The plugin becomes the reporter as well as the steering client.** It ships the hooks and the slash commands, and installs once at user scope. Orange's own runs keep using a per-run `CLAUDE_CONFIG_DIR`, so the reporter hooks never fire inside orange runs and orange never judges itself.
15. **Coordinator on the VPS, a thin node on the laptop** (§6.1). The user asked which placement is better, and this is that call.
16. **Migration numbering with PR 2 in parallel.** Phase A branches from the current base. Whichever of Phase A and PR 2 merges second renumbers its Flyway files to follow the other's on rebase. No database outside dev and test exists yet, so renumbering is safe until the first real deployment.

## 5. Concepts

- **Project.** A named area of work. It has a status (`active`, `paused` or `archived`), zero or more repos, and a space. Archived projects are ignored by the brief.
- **Space item.** One entry in a project's space. Kinds:
  - `goal` (a long-term outcome);
  - `milestone` (an optional `due_at`, optionally linked to goals);
  - `convention` (a key and value, see §8);
  - `decision`;
  - `note`;
  - `question` (an open question the user owes an answer to);
  - `proposal` (any kind, still awaiting acceptance).

  Each item records its author (`user`, `orange`, or `agent:<run or session id>`), its status (`proposed`, `active`, `done`, `dropped` or `superseded`) and what it supersedes. Items are never edited in place: a change supersedes the old item, so history is kept.
- **Work.** Anything that changes a project, of two kinds:
  - **Sessions.** Interactive sessions the user runs in some harness. orange observes them and acts only through hook verdicts.
  - **Tasks.** v2 tasks that orange plans and runs headlessly.

  Both point at a project and optionally at a branch and worktree, and both end in a close that carries evidence.
- **Evidence.** A recorded check against a specific `head_sha`. It has a kind, the command run, the exit code, an output tail, a pass/fail result and a timestamp. Kinds: `tests`, `build`, `lint`, `custom`, `diff_review` (an LLM read of the diff against the intent), `screenshot`, `manual` (the user said so).
- **Attention item.** Something that wants the user. It records:
  - the project and the work it is about;
  - urgency: `critical`, `urgent` or `normal`;
  - a title and a body that says what was being done, what is needed, and orange's proposed answer if it has one;
  - status (`open`, `acked` or `resolved`) and how it was resolved.

## 6. Architecture

```
your Claude Code sessions ─hooks─┐   your OpenCode sessions ─plugin─┐
                                 ▼                                   ▼
                     POST /sessions/events  (neutral session events, ORANGE_API_TOKEN)
                                 │
                                 ▼
   ┌──────────────────── coordinator (Spring Boot) ─────────────────────────┐
   │ events inbox → session reactor ─┐        task reactor (v2) ─┐          │
   │                                 ▼                           ▼          │
   │            run queue: judge · verify · brief · implement · fix · plan  │
   │            evidence runner (orange runs the commands itself)           │
   │            integrator (rebase, re-verify, merge / PR / leave)          │
   │            attention inbox → notifier (ntfy) · brief · steering MCP    │
   └────────────────────────────────────────────────────────────────────────┘
                                 │ verdict (long-poll)
                                 ▼
                     stop hook → {"decision":"block","reason":…} or allow stop
```

- **Reporter → orange.** Each hook posts a neutral event: `session_started`, `user_prompt`, `stopped` (with the last assistant message and the transcript path), `waiting` (a Notification hook: permission prompt or idle), `session_ended`. The `stopped` call then long-polls `GET /sessions/{id}/verdict` until the hook-wait timeout.
- **Session reactor.** This is the session counterpart of the v2 task reactor, and like it a pure decision function over state. On `stopped` it queues a `judge` run. On `waiting` it creates an attention item at `normal` urgency, or `urgent` if the session has been waiting longer than `waiting-escalate-after`, default 15 minutes. On `user_prompt` it marks the session `active`, cancels any pending verdict, and records that the user is focusing here. The brief uses that last part.
- **One run model.** `judge`, `verify` and `brief` are new run kinds in the v2 `runs` table. They go through the scheduler, the slots, routing and budgets, like `implement`. They use the worker MCP with per-run tokens, and their verdicts arrive through MCP tools, never parsed from transcripts (a v2 anti-goal that still applies).

### 6.1 Deployment topology

```
 laptop (sleeps sometimes)                            VPS (always on)
 ┌─────────────────────────────┐   Tailscale     ┌──────────────────────────────────┐
 │ your sessions ─hooks─▶ node │ ──────────────▶ │ coordinator + Postgres + ntfy    │
 │ node: event queue,          │ ◀── run/merge ─ │ node (in-process): orange's own  │
 │       run host for local    │      requests   │   tasks, containers (v2 PR 6),   │
 │       judge/verify/merge    │   (long-poll)   │   your VPS sessions if any       │
 │ Claude Code steering ─MCP──▶│ ──────────────▶ │ steering MCP, dashboard          │
 └─────────────────────────────┘                 └──────────────────────────────────┘
       phone ◀─── ntfy push / action buttons ─── coordinator
```

- **One coordinator, many nodes.** The coordinator is the one stateful process: Postgres, reactors, scheduler, attention, brief, notifier. It runs where it is always awake, which in the end is the VPS. A **node** is a small process that does two things:
  - it forwards hook events from the sessions on its machine, queuing them on disk while the coordinator is unreachable;
  - it hosts runs and git operations that need that machine's files: judge, verify and evidence commands, and integration of local repos.

  A node pulls its work from the coordinator over a long-poll, so the coordinator never connects into the laptop.
- **Every run has a host.** `runs` and `sessions` gain `node_id`. A session's runs go to the node the session reported from. orange's own tasks go to the VPS node.
- **Brief and orange's own tasks don't need the laptop.** The brief is SQL on the coordinator plus a run on the VPS node. While the laptop sleeps, its sessions sleep too, so nothing is missed: the node flushes its queue on wake, and any verdicts it gets are stale by then. A stopped event older than `hook-wait` is judged for attention and the brief only, with no verdict delivered.
- **Steering is local.** You talk to orange from Claude Code on the laptop, through the steering MCP over Tailscale. There is no terminal over SSH. The thin dashboard is reachable over Tailscale too.
- **Starting small.** Until the VPS phase, the coordinator and a node run together in one process on the laptop. The node protocol is the same from day one, so moving the coordinator to the VPS is a deployment change, not a rewrite.
- **Credentials.** Each node holds its own `CLAUDE_CODE_OAUTH_TOKEN` for the runs it hosts, and a per-node API token for the coordinator. The coordinator never ships the subscription token to a node.

## 7. Runs added

All three new kinds are read-only on the repo, except that `verify` may run commands in a scratch copy.

**`judge`.** Decides what happens at a stop.
- Input (from `get_session` on the worker MCP):
  - the session's stated intent (its first prompt, or a `work_intent` the user or session set);
  - the last assistant message;
  - the last N turns from the transcript;
  - the project's active goals, decisions and conventions;
  - the session's overrides;
  - recent attention items for the session;
  - `stop_hook_active` and the auto-continue count.
- Tools: Read, Glob, Grep in the worktree; `get_session`; `search_notes`; `propose_item`; and exactly one verdict:
  - `continue(message, rationale)`: orange answers the session. The message goes back through the hook. The rationale is recorded as an `orange` decision item, so the user can audit what was decided for them.
  - `verify()`: the session claims to be finished, so start verification.
  - `escalate(question, proposed_answer?, urgency, rationale)`: create an attention item; the session stops normally.
  - `idle(reason)`: nothing to do (for example a chatty stop with no question in it); the session stops normally.
- Routing: role `judge`, small tier with low effort by default. It moves to medium when the project is marked risky or the question touches a risk path.

**`verify`.** Collects evidence that the work is done.
1. orange itself runs the commands listed in the project's `evidence` convention (§8) in the work's worktree at its `head_sha`. Orange runs them directly, not an agent. For attended sessions, it uses a scratch worktree at that SHA, so it doesn't touch the user's working tree mid-session. Each command becomes an `evidence` row.
2. A `diff_review` run reads the diff against the base, compares it with the intent and the acceptance items, and calls `submit_review(verdict, gaps[])`.
3. Uncommitted changes and untracked files are flagged as a gap.
4. The outcome:
   - All required evidence passes: close the work and hand it to the integrator.
   - Any gap: for a session, the gaps become a `continue` message ("tests X fail; also Y is uncommitted"). For a task, they become feedback for a fix run, as in v2. Repeated failure follows v2 failure counting and escalation.

**`brief`.** Writes the focus brief.
- Input: a snapshot built by SQL, not by an agent. For each active project:
  - active goals and milestones (with `due_at` where set);
  - work items with their states and last-activity times;
  - integrations since the last brief;
  - open attention items;
  - the user's recent focus, from `user_prompt` events;
  - the share of each milestone's linked work that is done, against time elapsed, where a milestone has a date.
- Tool: `submit_brief(projects[{project, status_line, progress, timetable?, quiet_since?}], focus[ranked {project, item, why}], urgent[])`.
- Format: one line per project, at most three focus items, and urgent items only when there are any. Anything from `urgent[]` that isn't already an attention item becomes one.
- "Quiet" is judged against the project's own `cadence` convention if it has one. Otherwise it is judged against the project's own recent history. There is no fixed threshold.

## 8. Conventions

Conventions are `convention` space items, holding a key and a JSON value. They resolve in this order: session override, then project, then global default (`application.yaml`, under `orange.conventions`). The judge and the user can both set session overrides. A user can say so inside a session, and the reporter's `user_prompt` event carries it, or use `orange_set` in steering.

| Key | Default | Meaning |
|---|---|---|
| `done` | `["evidence", "integrated"]` | What closing requires. Others: `committed`, `pr_open`, `merged_pr`, `evidence` alone. |
| `integration` | `merge` | One of `merge` (rebase plus fast-forward), `pr` (open a PR, done when merged), `branch` (leave it on the branch), `ask` (push an attention item). |
| `evidence` | auto-detected | The commands to run, for example `["./gradlew test"]`. When unset, orange proposes a value from the repo (build files, CI config) as a `proposal`, and uses it once accepted. |
| `base-branch` | the repo default | Integration target. |
| `remote-push` | `true` if a remote exists | Push the base after integrating. |
| `auto-answer` | `routine` | One of `none`, `routine` (the judge answers when it is confident and nothing critical applies), `broad`. |
| `always-ask` | `[]` | Topics or paths the judge must escalate. |
| `auto-answer-confidence` | `0.8` | The judge's self-rated threshold. |
| `cadence` | unset | The expected rhythm, for example `"daily"` or `"weekly"`. Used only for quiet detection. |
| `brief-times` | `["09:00", "14:00"]` | Global only. |
| `quiet-hours` | `["22:00", "08:00"]` | Global only. |

New keys can be added without a migration.

## 9. Data model

These are new Flyway migrations after whatever PR 2 ends with. The patterns are the v2 ones.

| Table | Key columns | Notes |
|---|---|---|
| `projects` | id, name UNIQUE, status, summary, created_at | |
| `project_repos` | project_id, repo_root (absolute path of the main checkout), remote_url?, default_branch | A repo belongs to one project. |
| `space_items` | id, project_id, session_id?, task_id?, kind, key? (conventions), body JSONB, status, author_kind, author_ref, due_at?, supersedes_id?, created_at | Never edited in place; a change supersedes. Partial index on active items per project and kind. |
| `sessions` | id, project_id?, harness (`claude-code`, `opencode`), external_id, cwd, repo_root?, branch?, worktree?, intent?, status (`active`, `stopped`, `judging`, `verifying`, `waiting_on_user`, `closed`, `ended`), auto_continues, last_event_at, last_user_prompt_at, closed_reason? | Unique on `(harness, external_id)`. `pg_notify('session_changed')`. |
| `session_events` | id, session_id, type, payload, received_at | Raw neutral events. |
| `verdicts` | id, session_id, run_id, kind (`continue`, `verify`, `escalate`, `idle`), message?, status (`pending`, `delivered`, `cancelled`, `expired`), created_at | The hook long-polls the newest `pending` verdict. |
| `evidence` | id, session_id?, task_id?, head_sha, kind, command?, exit_code?, output_tail, passed, run_id?, created_at | |
| `attention_items` | id, project_id?, session_id?, task_id?, urgency, title, body, proposed_answer?, status, pushed_at?, resolved_by?, resolution?, created_at | Partial index on `open`. `pg_notify('attention')`. |
| `briefs` | id, content JSONB, run_id, delivered_at | |

Changes to v2 tables:
- **`runs.kind`** gains `judge`, `verify`, `diff_review`, `brief`. **`runs`** gains `session_id?`.
- **`tasks`** gains `project_id`.
- **v2 `epics`** gain `project_id`.
- **v2 `notes`** move into `space_items` (kind `note`). The v2 `post_note` and `search_notes` tools keep their names.

## 10. What changes in the v2 spec

This section supersedes the listed parts of the v2 spec. Everything not listed stands.

- **§1 Intent and Success.**
  - "turn a spec into merged pull requests" becomes "turn intent into verified, integrated work".
  - Success gate 1 becomes "a task goes from `ready` to verified and integrated, unattended".
  - Gate 3 becomes "a week in which the user only answers attention items".
- **§6 FSM.** The task lifecycle becomes:

  ```
  pending ─deps integrated─▶ ready ─scope + slot─▶ running ─run done─▶ verifying ─evidence ok─▶ verified ─integrate─▶ integrated
                                                    ▲                     │ gaps                      │ conflict
                                                    └──── fix feedback ───┴───────────────────────────┘
  any non-terminal ─▶ needs_human ─answer─▶ ready · cancelled · failed
  ```

  - `pr_open`, `in_review` and `merged` go. With `integration: pr`, `verified` waits for the PR merge event before becoming `integrated`.
  - Dependents are promoted on `integrated`.
  - PR columns (`pr_number`, `pr_url`, `ci_state`, `reviewed_sha`, `review_verdict`, `ready_notified_sha`) become optional, used only by the `pr` integration mode.
- **§7 post-run steps.** "Push, open a PR, move to `in_review`" becomes "commit check, move to `verifying`, queue `verify`". The `review` run kind becomes `diff_review`, which is part of verify, and its verdict is stored as evidence. It is not posted to GitHub unless `integration: pr`.
- **§12 scope claims.** Claims are released on `integrated`, `cancelled` or `failed`. Attended sessions don't take scope claims. When a session's diff overlaps an active task's claim, orange raises an attention item at integration time.
- **§13 events.** GitHub webhooks and the reconciler become one optional source. They matter only to projects with `integration: pr` or CI they want to count as evidence. The main sources are session events, orange's own runs, and git itself (the base moving after an integration triggers the rebase sweep).
  - New conflict handling: when the base moves, orange rebases each active task branch. It skips worktrees under a live attended session (decision 8).
    - A clean rebase triggers re-verification only if the required evidence was already recorded for the old SHA.
    - A conflicting rebase queues a fix run, or raises an attention item when the conflict is between a task and the user's own session.
- **§14 notifications.** The ntfy topics become `attention` (urgent and critical items) and `brief`. The `needs-human` and `ready-to-merge` topics go. Budget and plan-limit pushes become `urgent` attention items.
- **Decisions in v2 §4:**
  - 9 (rebase only on GitHub `dirty`): replaced by the rebase sweep above.
  - 10 (agent reviews posted as `COMMENT`): applies only to `pr` mode.
  - 12 (bot feedback rounds): applies only to `pr` mode.
  - 13 (one repo per deployment): lifted.
  - 14 (answer from the phone through GitHub threads): becomes the steering tools, plus ntfy action buttons (`Use orange's answer` / `Open`) that call `POST /attention/{id}/resolve`. orange must be reachable from the phone over Tailscale for those buttons to work.
- **Non-goals in v2 §2.** "Auto-merge to the base branch" is removed; it is now the default (§4 here).
- **§15 MCP.**
  - Worker endpoint additions: `get_session`, `propose_item`, the judge verdict tools, `submit_review` (re-scoped to diff review), `submit_brief`.
  - Steering endpoint additions:
    - `orange_projects`, `orange_project(id)` (the space);
    - `orange_add(item)`, `orange_set(convention)`, `orange_accept(proposal)`, `orange_drop(item)`;
    - `orange_focus` (the latest brief, or a fresh one);
    - `orange_attention` (open items), `orange_resolve(id, answer)`;
    - `orange_sessions`.
  - `orange_answer` folds into `orange_resolve`.

## 11. The PR 2 branch in flight

`feat/v2-pr2-runs` (plan `docs/superpowers/plans/2026-11-09-orange-v2-pr2-runs.md`) is mid-way through Task 1. It has uncommitted changes and may belong to another live session. Its parts fare as follows:

| PR 2 plan task | Fate |
|---|---|
| 1 Remove Temporal and v1 paths | Stands. |
| 2 FSM v2 (V14) | Changes to the FSM in §10 of this document. |
| 3 `runs`, `run_events`, `settings` | Stands. Add the new kinds to the `kind` check now, so no migration is needed later. |
| 4 Worker MCP | Stands. |
| 5 Worker runner, role prompts | Stands. |
| 6 Scheduler, reaper, task reactor | Stands, with the reactor rules rewritten for the new FSM. |
| 7 Coordinator push and PR; review runs | Replaced by the evidence runner, `diff_review` and the `merge`-mode integrator, for tasks only, with the evidence commands read from global config. Phase C extends the same code to sessions and conventions instead of building it a second time. PR mode moves to a later phase. |
| 8 Events inbox; merge through events | The inbox stands; merge-through-webhook becomes PR-mode only. |
| 9–11 | Stand. The e2e IT becomes ready → run → verify → integrated → dependents ready. |

The PR 2 plan has to be revised before Task 2 starts.

## 12. Phasing

These are new phases, one PR each. The remaining v2 phases follow them in the order 6 (containers), 4 (planner, scopes, notes, now on space items), 5 (escalation, budgets, steering), 7, 8, 9, re-scoped by §10. The attention phases come first because they pay off on the user's own sessions straight away, before orange runs any agents unattended.

- **A. Project space, sessions, attention, pushes.**
  - The `projects`, `project_repos`, `space_items`, `sessions`, `session_events` and `attention_items` tables.
  - `POST /sessions/events`.
  - The Claude Code reporter hooks in the plugin: SessionStart, UserPromptSubmit, Stop (report only, no verdict yet), Notification, SessionEnd.
  - Rule-based attention: a session stopped with a question, or waiting longer than a threshold.
  - ntfy, plus the steering tools for projects, space, attention and sessions.
  - Exit: two or more days of the user's real sessions with pushes that arrive when they should. Also an IT per hook event against a fake reporter.
- **B. Judge and hook verdicts.**
  - `judge` runs and the `verdicts` long-poll.
  - Stop-hook blocking, the loop guard, `UserPromptSubmit` cancellation, and fail-open behaviour.
  - Conventions `auto-answer`, `always-ask` and `auto-answer-confidence`.
  - Decisions are recorded as space items.
  - Exit: ITs for each verdict using a fake `claude`, plus a live check where a real session asking "proceed?" is continued by orange.
- **C. Evidence, close and integration.**
  - `verify`, `diff_review` and evidence rows.
  - Conventions `done`, `integration`, `evidence` (with auto-detect proposals), `base-branch` and `remote-push`.
  - The integrator (rebase, re-verify, fast-forward, optional push) and the rebase sweep.
  - Exit: an attended session in a sandbox repo is verified, closed and merged by orange, and a failing test is sent back into the session as a `continue`.
- **D. Focus brief.**
  - `brief` runs, the snapshot SQL, schedule and quiet hours.
  - `orange_focus`, plus a brief page on the dashboard.
  - Exit: a week of briefs on real projects that the user finds accurate. Judged by the user, not by an IT.
- **V. Coordinator on the VPS.**
  - Deploy the coordinator, Postgres and ntfy on the VPS, joined to Tailscale.
  - The laptop runs as a remote node.
  - Disk-queue replay on wake, plus node health shown in the brief and on the dashboard.
  - This lands together with v2 PR 6 (containers), because orange's own tasks on the VPS run in containers.
  - Exit: close the laptop, and the brief still arrives on schedule, and orange's VPS tasks keep running. On wake, the queued events replay and verdicts aren't wrongly delivered.
- **E. OpenCode reporter.** The same neutral events and the verdict path, through its plugin and SDK. Exit: phases A and B behave the same on an OpenCode session.

## 13. Testing

- **Unit:**
  - the session reactor decision table;
  - convention resolution (session → project → global);
  - critical-decision rules;
  - the push batching and quiet-hours logic;
  - the brief snapshot query;
  - the cwd → repo → project mapping, including worktrees.
- **Integration (Testcontainers Postgres):**
  - hook event ingestion and idempotency;
  - the verdict long-poll (delivered, cancelled by a user prompt, expired);
  - the loop guard;
  - evidence and integration on a temp git repo (clean rebase, conflicting rebase, re-verify after rebase);
  - attention items, pushes and resolve.
- **Fake harness:** `FakeClaude` gains `judge-continue`, `judge-escalate`, `judge-verify`, `review-pass`, `review-gaps` and `brief` scenarios.
- **A fake reporter** posts scripted hook payloads, captured from a real Claude Code session and kept as test resources.
- **Live checks** use the subscription only and need the user's real sessions. They are the phase exits in §12.

## 14. Left to the builder

- Exact endpoint shapes and the neutral event schema.
- Whether the reporter hooks use command hooks (a small script calling `curl`) or HTTP hooks, if the installed Claude Code supports them.
- The judge, review and brief prompt wording.
- The confidence-rating method.
- How many transcript turns the judge reads.
- Push batching windows.
- The scratch-worktree strategy for verifying attended sessions.
- The brief's exact layout on the dashboard and in ntfy.
- Auto-detection heuristics for `evidence`.
