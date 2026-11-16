---
name: orange
description: Talk to orange, the coordinator that runs coding agents on the user's projects. Use it when the user asks for the status ("what's the status", "what's running", "what's new"), where to focus ("where should I focus", "what needs me"), whether a milestone is on track ("are we on track"), to hand over work ("build X in Y", "add X to Y", "fix X in Y"), to start a project ("start a project X", "new project with a repo"), to follow, cancel, pause or resume work, or to answer orange's questions (an approval, a stuck task, a proposed project). Not for editing code in this session itself.
---

# orange

orange runs the work; this tab steers it. orange plans what the user hands over, runs the agents on its own machine, checks the work with each project's evidence commands (the tests) and merges it. What needs the user waits in orange's attention inbox. Everything lives in orange, nothing in this tab: the user can clear it or open a new one at any time, and a new tab starts with a few lines of orange's status.

The flow is **status → hand over work → follow → answer**. Call the tools; never guess a count, a state or an id.

## Status

- "What's the status", "what's running", "are we on track": `orange_status`. It gives every project's plans, unfinished tasks (stuck first, with short ids), what was merged today, each dated milestone **on track** or **behind** (orange's own verdict from its facts: report it, never make one up), what waits for the user and whether orange is paused.
- "What's new", "where should I focus", "what needs me": `orange_focus`, the latest brief. `fresh: true` writes a new one, which takes up to a minute. Give its page link when there is one.
- One project's goals, milestones, decisions and conventions: `orange_project`. All projects: `orange_projects`.

## Hand over work

1. **A new project:** `orange_project_create` with `name` and `repo` (`owner/name`), or `create: true` for a new private GitHub repo. Pass `evidence`, the commands that prove work is done (such as `./gradlew test`), when the user knows them: without them orange can implement but never merges. Ask for them if the user did not say.
2. **Work:** `orange_plan` with `project` and `request`. Write the request completely, in the user's words plus anything said earlier in this tab that matters: the planner sees nothing else. Add `milestone` (its text or id) when the work serves one.
3. `orange_plan` returns at once with a plan id. orange then plans in the background and either dispatches the tasks on its own or asks first with one attention item (a large or risky plan, or a project without evidence commands yet). orange decides which; tell the user so, and that `orange_status` shows where the plan is.

Milestones: `orange_add` with `kind: milestone`, the text and an optional `due` date; then plan work with that `milestone`, so `orange_status` and the brief can say whether it is on track.

## Follow

- One task in detail (its runs, why it failed, its evidence): `orange_task` with its id. The short id `orange_status` shows works.
- What its agent is doing right now: `orange_tail`. Call it again for newer events; summarise, do not paste raw lines.

## Answer

- `orange_attention` lists what waits for the user, most urgent first. Read an item to the user before answering it.
- `orange_resolve` with the item's id and the user's answer:
  - a plan that asks first: `approve` dispatches it (and puts the evidence commands it proposed in force), `drop` drops it, any other text is a note orange plans again with;
  - a stuck task: the answer reopens the task with the answer as its note. Its branch is on GitHub as `orange/task/<short id>` when the user wants to look at it first;
  - a proposed project: `confirm` or `ignore`.
- Never answer for the user. When the answer is not clear from what the user said, ask them.

## Control

- `orange_cancel` with a task id cancels a task for good.
- `orange_pause` (with a short `reason`) holds every new run; `orange_resume` starts again, also after orange paused itself (a plan limit, a rejected token). Plans are refused while orange is paused.
- Conventions (`orange_set`): `plan-approval` (`auto`, `always`, `never`), `review`, `evidence`, `auto-answer` and the rest, per project.
