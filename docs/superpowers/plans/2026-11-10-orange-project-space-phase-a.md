# Project space Phase A: space, sessions, attention, pushes

2026-11-10 · Spec: `docs/superpowers/specs/2026-11-09-orange-project-space-design.md` §12 A · Branch: `feat/project-space-phase-a`

Phase A makes orange useful on the user's own sessions: it sees every Claude Code session through
plugin hooks, files what wants the user into one inbox, and pushes the urgent part to the phone.
No judge yet (Phase B): attention is rule-based.

## Tasks

Mark each `[x]` when it is committed with its tests green.

- [x] **1. Schema (V19).** `projects`, `project_repos`, `space_items`, `sessions`, `session_events`,
  `attention_items`; `pg_notify` channels `session_changed` and `attention`.
- [x] **2. Projects and the space.** `project/` package: repositories, `ProjectService` (cwd → repo →
  project, proposed projects), `SpaceService` (add, supersede, accept, drop), `Conventions`
  (session → project → global `orange.conventions`).
- [x] **3. Attention inbox.** `attention/` package: items with a dedupe key, raise, escalate, ack,
  resolve; REST `GET /attention`, `POST /attention/{id}/ack|resolve`.
- [x] **4. Session events.** `POST /sessions/events` stores a neutral event in the inbox;
  `SessionEventHandler` applies it: `SessionReactor` (pure decision table) + `SessionService`.
  `SessionSweeper` escalates long waits. `GET /sessions`.
- [x] **5. Pushes.** `notify/`: `PushPlanner` (pure: urgency, batching window, quiet hours) and
  `NtfyNotifier`.
- [x] **6. API token.** `orange.api.token` guards the new endpoints when set.
- [x] **7. Steering MCP** at `/mcp/steering`: `orange_projects`, `orange_project`,
  `orange_project_update`, `orange_add`, `orange_set`, `orange_accept`, `orange_drop`,
  `orange_attention`, `orange_resolve`, `orange_sessions`.
- [x] **8. Claude Code reporter.** Plugin hooks (SessionStart, UserPromptSubmit, Stop, Notification,
  SessionEnd, and PostToolUse only while a session waits) and `plugin/hooks/orange-report.py`;
  captured hook payloads as test resources and an IT per hook event.
- [x] **9. Docs and live check.** CLAUDE.md, plugin README, `.env.example`, spec §3 hook findings;
  a real session against a running orange.

## Builder's rulings

1. **Session events go through the PR 2 inbox** (`events`, source `session`, `delivery_id` = the
   reporter's event id), so ingestion is idempotent, ordered and retried like webhooks.
   `session_events` keeps the per-session history the judge and the brief will read.
2. **The reporter resolves git, not the coordinator.** The hook script sends `repo_root` (the main
   checkout, through the common git dir), `worktree`, `branch`, `remote_url` and `default_branch`
   with each event. The coordinator never reads a node's files (spec §3 topology).
3. **Neutral event types:** `session_started`, `user_prompt`, `stopped`, `waiting`, `session_ended`
   and one addition, `active`: the session is working again after a wait. Without it a permission
   prompt the user answered would still escalate 15 minutes later. The reporter sends it from
   PostToolUse, and only while a marker file says the session was waiting.
4. **Proposals are a status, not a kind.** A proposed goal is `kind = goal, status = proposed`.
5. **Rule-based attention:**
   - a stop whose last message asks something → `normal` item now, `urgent` after
     `waiting-escalate-after` (15 min) without a user prompt;
   - a permission or input prompt (`waiting`) → the same;
   - a stop with no question → a `normal` item after `idle-after` (15 min), never pushed unless
     `idle-stop-urgency` says `urgent`;
   - a user prompt, activity or the session's end resolves the session's open items;
   - sessions of an `archived` project raise nothing, which is how a repo is muted.
6. **An unknown repo creates a `proposed` project** and a `normal` attention item. Resolving the
   item with `confirm` activates the project and `ignore` archives it. Sessions of a proposed
   project are watched like any other.
7. **Pushes:** only `urgent` and `critical` items; one push per `batch-window` (10 min) with every
   item that queued up; quiet hours hold everything but `critical`. No action buttons yet: they
   need a proposed answer (Phase B) and must not hand the API token to a public ntfy server.
8. **Auth:** with `ORANGE_API_TOKEN` set, `/sessions/**`, `/attention/**` and `/mcp/steering` need
   it as a bearer token (403 without); blank leaves them open, like the rest of the operator API on localhost.
   Per-node tokens come with Phase V.
9. **Deferred to the phase that uses them:** the `runs` → session link and `verdicts` (B),
   `evidence.session_id` and `session_work` (C), `briefs` and `tasks.project_id` (D / v2 PR 4).
   `runs.session_id` already exists as the harness's own session id, so Phase B names its column
   differently.
10. **The Python `orange-mcp` shim stays** for the task tools until v2 PR 5 folds them into
    `/mcp/steering`.

## Known limits

- **A permission prompt is tracked through PostToolUse only.** Claude Code has no hook for "the
  user answered a prompt", so the reporter takes the next finished tool call as the answer. Two
  consequences: when tools run in parallel, one finishing can clear the wait while another still
  shows its prompt; and a tool approved and then running for longer than
  `waiting-escalate-after` is escalated although it was answered. Both need a look in a real
  interactive session before the escalation time is tuned.
- **Sessions that die without a SessionEnd** keep their wait. The sweeper leaves a waiting session
  alone once it has been silent for `stale-after` (12 h), so it escalates at most once.
- **Review (2026-11-10, fresh-context reviewer):** one blocking finding, fixed: the API token was
  checked by a servlet filter on the raw URI, so `/%73essions` got past it. The check is now an
  authorization rule in the security chain. Also fixed: session-scoped space items go with their
  session, the sweeper re-reads each session under its lock, one writer per project space at a
  time, an item supersedes only its own kind and only one proposal against an item can be
  accepted, global-only convention keys are refused per project, project names are unique
  whatever the case, a bad `orange.notify.zone` stops the start, the reporter strips passwords
  from any remote URL.

## Verified

- `./gradlew test`: 357 tests, 0 failures (2026-11-10, after the review fixes).
- Live (2026-11-10, Claude Code 2.1.321, `claude -p --plugin-dir plugin` in a fresh git repo against
  a running orange): SessionStart, UserPromptSubmit, Stop and SessionEnd reached orange through the
  real hooks. The repo proposed a project, the first prompt became the intent, the stop's real
  `last_assistant_message` was filed as a question, and the session's end resolved it.
- Not checked live: Notification and PostToolUse (they need an interactive session with a
  permission prompt), and a push to a real ntfy server (none is configured yet). Both are covered
  by ITs against the real reporter script and a fake ntfy server. The spec's exit for this phase,
  two days of the user's real sessions, starts once the plugin is installed at user scope.
