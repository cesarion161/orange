# Project space Phase D: the focus brief

2026-11-10 · Spec: `docs/superpowers/specs/2026-11-09-orange-project-space-design.md` §12 D · Branch: `feat/project-space-phase-d`

At each brief time, and when asked, orange reads where the active projects stand (the snapshot,
in SQL) and a short `brief` run adds one line per project and what to focus on next. A failed,
skipped or refused run costs the words, never the brief: the facts are always orange's own.

## Tasks

- [x] **D1. Schema, the brief record, settings and the schedule rule.**
- [x] **D2. The snapshot: the facts in SQL, the quiet rule, the news rule.**
- [x] **D3. `submit_brief` and the brief run's wiring.**
- [x] **D4. The prompt and the text (pure).**
- [x] **D5. Writing a brief: the service, the tick and the fake.**
- [x] **D6. Delivery and the page.**
- [x] **D7. `orange_focus`, the plugin README and the docs.** The live check is under Open.

## Builder's rulings

1. Brief runs start directly (`WorkerRunner.runDirect`, as the planner's do), not through the
   queue and slots (spec §6 'one run model'). Reason: nothing waits on a brief the way a stop hook
   waits on a judge, so the caller can hold the run and its result. A queued brief can be lost
   four ways (queued behind a pause, starved behind task runs, reaped with no post-run step, a
   post-run step that threw), each needing its own sweep and tests; a direct run has none of them
   and still gets routing, the budget, the timeout, the lease, the per-run MCP token, an outcome
   through MCP only and the plan-limit pause. Slots stay `brief: 0`; `RunScheduler`, `RunPreparer`
   and `PostRunSteps` are untouched.
2. The brief has no built-in tools and runs in an empty directory (spec decision 13 gives it Read,
   Glob and Grep). Reason: Phase B ruling 8 and Phase C ruling 5: its prompt carries text sessions
   wrote, from every project, and it has no single checkout to read.
3. The run's input is its prompt (Phase B ruling 1), and the snapshot is stored on the brief
   (`briefs.snapshot`), where the page shows it beside the model's lines. `runs.input` holds only
   the trigger, the slot and the project names the run may write about, so no session text reaches
   the open `/runs/{id}`.
4. orange owns the facts; the model writes `status_line`, `focus` and `urgent` only. The spec's
   `progress`, `timetable` and `quiet_since` are not arguments: counts, dates, the timetable and
   quiet are computed from the snapshot and printed by orange beside each line. Reason: the
   phase's exit is accuracy, nothing links work to goals so 'progress' is prose in any case, and
   Phase B ruling 4 already has orange, not the model, hold the lines that can be held
   mechanically.
5. An `urgent[]` entry becomes a `normal` attention item (`brief_urgent`): at most one unresolved
   per project, three per brief, never pushed by itself, and the brief never raises an existing
   item's urgency. Reason: the brief's own push carries it, and a model that reads session text
   must not be able to page the phone (the push-noise anti-goal). 'Not already an attention item'
   is held by the prompt (the model sees the waiting list) and by the one-per-project bound, not
   by ids.
6. When there are no model lines, the brief is written from the facts by rule, with a note saying
   why: the scheduler is paused (no run is started, Phase B ruling 7), the run failed or reported
   nothing valid, or a run for that brief time already exists. A brief time gets one model
   attempt, never a retry, so nothing can loop.
7. A scheduled brief with nothing new (no open item, no prompt, no closed work, no task news, no
   milestone due within 2 days or overdue, no project newly quiet) is written but not pushed, and
   no run is started. Reason: success criterion 4; the spec says a brief is sent at each time.
8. `briefs` has more columns than spec §9 (`slot_at`, `snapshot_at`, `snapshot`, `note`,
   `pushable`, `page_key`): the slot makes the schedule idempotent, the snapshot is the half that
   is always there, and the key opens the page. No status column: a row is inserted once,
   complete.
9. Milestone progress is not 'the share of linked work that is done': nothing links work to a
   milestone today and this phase adds no link. The snapshot gives days left, the share of time
   gone and the project's work closed since the milestone was set, labelled as not linked.
10. `tasks` gets no `project_id` in this phase (spec §9; Phase A ruling 9 said 'D / v2 PR 4',
    Phase C ruling 10 says v2 PR 4). orange's own tasks are one block outside the projects.
    Reason: a guessed attribution is worse than none, and tasks get projects together with project
    conventions.
11. Only `active` projects get a line. Proposed and paused ones are counted, and active ones
    beyond the twelve most recently active are only named. Reason: spec §7 says 'each active
    project', and an unconfirmed repo name should not enter the prompt as a project.
12. Quiet is orange's verdict, never the model's: with a `cadence` (daily 1, weekly 7, monthly 30,
    or a number of days) a project is quiet when silent for longer than it; without one, when it
    has at least 3 active days in the last 60 and has been silent for longer than max(2 x its
    usual gap, its longest gap); otherwise 'too new to judge'. An active day is a day in the
    user's zone with a prompt or a stop. No fixed threshold, as spec §7 asks.
13. A brief time inside quiet hours is skipped, not deferred. A missed brief time is caught up
    only within `catch-up` (2h) and only the latest one; a brief ntfy has not taken by then is
    never pushed. Reason: a late brief is noise, and a deferred 07:00 brief would land an hour
    before the 09:00 one.
14. `since` is the previous scheduled brief's snapshot time (else `first-window`, 24h); on-demand
    briefs do not move it. Reason: a session calling `orange_focus` must not be able to empty the
    next scheduled brief.
15. There is no dashboard to add a page to, so the brief page stands alone: `GET
    /brief/{id}?k=<page key>` and `GET /brief` for the latest. The per-brief key, valid for
    `link-ttl` (48h), is the phone's credential because a tapped link carries no bearer header and
    the API token must never reach ntfy (Phase A ruling 7). The API token opens the page too, at
    any age. A wrong key, an expired key and an unknown id are the same 404, checked in the
    controller. The push carries a link only when `orange.brief.public-url` is set.
16. `orange_focus` without `fresh` never starts a run, even when there is no brief yet (spec §10
    says 'the latest brief, or a fresh one'); `fresh` writes at most one brief per
    `fresh-min-interval` (10m) and waits `focus-wait` (60s). Reason: reading must be free, and a
    looping session must not burn the plan.
17. Brief runs route to the medium tier at low effort, with a 5 minute timeout (was 10). Reason:
    two runs a day make cost irrelevant, the ranking is the product, and a hung run should fall
    back inside the catch-up time.
18. Names: the settings are `orange.brief.*` (Phase B ruling 12), the topic
    `orange.notify.ntfy.brief-topic`, the attention kind `brief_urgent`.
19. No JSON REST for briefs and no plugin skill: `orange_focus` is the API and arrives through
    `tools/list`, so the plugin changes only its README and keeps its version.
20. Deferred: node health in the brief (Phase V), ntfy action buttons, milestone-to-work links,
    `tasks.project_id`, per-project brief times, file tools for the brief (they come back with
    node-hosted runs).
21. The brief topic has no built-in name (spec §14 names the topics `attention` and `brief`):
    with none set, the brief goes to the attention topic. Reason: on a public ntfy server a
    topic's name is the only secret, and an install from before this phase has a secret attention
    topic and no brief topic; a fixed word would publish its briefs at the first brief time after
    the upgrade. (From the review.)
22. `orange.notify.enabled` switches the brief's push off too, and `orange.brief.enabled` off
    stops the scheduled write and the push but not the retention. Both are asked by the sweep, not
    by `BriefScheduler.tick` and `BriefDelivery.deliver`, which tests call, as `AttentionNotifier`
    does it. (From the review.)

## Known limits

- Nothing links work to a goal or a milestone, so the timetable is time elapsed plus unlinked
  counts, and 'progress against goals' is the model's prose.
- orange's own tasks have no project: they are one block, and a task waiting for a person shows
  only there.
- A status line can still paraphrase session text or claim something the facts do not show;
  orange's facts sit beside it, and it is one line with no links. A link is known by its
  `scheme://` or its `www.`: a bare domain name (`example.com/x`) passes, because file and
  setting names look the same.
- A model text with a tab, a soft hyphen or a zero-width joiner (some emoji) is refused like any
  other invisible character; the model is told and must submit again.
- Without a brief topic the brief arrives on the attention topic, where only its priority (3) and
  its title tell it from what needs the user now.
- `orange_focus` read inside a session carries lines the model derived from other sessions' text.
- The model's tool call is in `run_events`, readable at the open `/runs/{id}/events`, like a
  judge's.
- A brief whose push failed or was held past `catch-up` is on the page only. A 2xx followed by a
  failed update repeats one push. A push that fails is tried again at every tick (60s) until then,
  with no backoff.
- A coordinator stopped or killed during a brief run writes that brief from the facts at its next
  start (inside `catch-up`); the model is not asked again.
- One coordinator is assumed: the write lock is in the process. Two coordinators could both run a
  brief for one time; the slot index keeps one row.
- `brief-times` and `quiet-hours` change only with a restart. Milestone dates still use the
  coordinator's zone (`SteeringTools.due`), the brief uses `orange.notify.zone`.
- Quiet: a long gap inside the 60 days hides quietness until a `cadence` is set; history starts
  when the plugin was installed; days are counted by when orange received an event, which after
  Phase V's queue replay is the replay time.
- Whether an answer orange gives a session shows up as a `user_prompt` event is not verified; if
  it does, it inflates the focus numbers.
- Acked attention items are not in the brief.
- An `urgent` entry that repeats an existing item is prevented only by the prompt and the
  one-unresolved-per-project bound.
- The page key travels through ntfy when a public url is set, and `orange_focus` shows it inside
  sessions; it opens one brief for 48 hours. With no API token set, `/brief/{id}` opens without a
  key, like the rest of the operator API on localhost.
- The prompt is bounded by fixed caps (about 125,000 characters at worst, 92,000 of them the capped
  texts themselves), not trimmed to a budget.
- Whether Claude Code's MCP client waits 60 seconds for a tool call is not checked; it is part of
  the live check.
- With `ORANGE_BRIEF_ENABLED=false` no brief is scheduled, but `orange_focus` with `fresh` still
  writes one (and retention still deletes it). The variable must be `true`, `false` or absent: left empty in `.env` it stops the
  start (an empty string does not bind to a switch), so `.env.example` lists it commented out.
  The topics and the public url take an empty variable as "the default".
- `GET /brief` shows the latest brief of any kind, so after an `orange_focus` with `fresh` it is
  the one that was asked for.
- Outside the projects, the urgent count in "Waiting on you" covers only the five items listed;
  the snapshot has no urgent count for the rest.
- When a fresh brief cannot be written at all (the run could not be started), `orange_focus`
  answers `orange_focus failed: RuntimeException`; the cause is in orange's log, also when the
  caller had stopped waiting.
- The time is read again before each step of a tick, not during one: a push that ntfy takes
  slowly is still judged by the time it was started at.
- No test covers `orange_focus` when `focus-wait` runs out while the run goes on (it needs a run
  slower than the wait); the `writing` answer is tested through the held write lock.
- The exit (a week of briefs the user finds accurate) is the user's to judge, not a test's.

## Review (2026-11-10, fresh-context reviewers, each finding checked by a second one)

One blocking finding: with no brief topic set, briefs went to the fixed ntfy topic `brief`, so an
install with a secret attention topic on a public server would have published its briefs at the
first brief time after the upgrade. Fixed: the brief topic has no built-in name and falls back to
the attention topic (ruling 21). Also fixed: each step of a tick reads the time again, so the push
after a brief run is judged and recorded at its own time; `orange.notify.enabled` switches the
brief's push off, and with `orange.brief.enabled` off old briefs are still deleted (ruling 22);
`fresh-min-interval` is checked again under the write lock; a fresh write that fails after the
caller stopped waiting is logged; `submit_brief` refuses control and invisible characters and
links by any scheme or `www.`, the snapshot's one-line texts drop format characters, and an
urgent entry reaches the inbox as one clean line. Tests added for the sweep itself (one tick at a
time, the switches), orange stopping during a brief run, and brief times on the days the clocks
change; `SteeringMcpIT` now checks that reading writes no brief and starts no run. Left as known
limits above: bare domain names, the shared topic's single ring, `focus-wait` running out
untested.

## Open

- **The live check has not been run.** It needs the user's running orange and a subscription
  token: from a Claude Code session with the plugin, call `orange_focus` with `fresh: true`
  against the real projects, and record here, as Phases B and C did: the run's time, tier and
  estimated cost; whether the MCP call came back within `focus-wait` (and whether Claude Code's
  MCP client waits that long for a tool call); what the brief got wrong against the facts on its
  page; and any wording fix made to `orange/src/main/resources/roles/brief.md`. Do not point it
  at a public ntfy server.
- The exit of the phase (a week of briefs the user finds accurate) starts once orange runs with
  the plugin installed.

## Verified

- `./gradlew test`: 581 tests, 0 failures (2026-11-10, after the review fixes; 570 before the
  review, 406 before the phase). No model was called: the brief runs are the fake `claude`'s, the
  pushes a fake ntfy's. The live check is still open (above).
