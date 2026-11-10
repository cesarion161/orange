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
- [ ] **9. Docs and live check.** CLAUDE.md, plugin README, `.env.example`, spec §3 hook findings;
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
   it as a bearer token; blank leaves them open, like the rest of the operator API on localhost.
   Per-node tokens come with Phase V.
9. **Deferred to the phase that uses them:** the `runs` → session link and `verdicts` (B),
   `evidence.session_id` and `session_work` (C), `briefs` and `tasks.project_id` (D / v2 PR 4).
   `runs.session_id` already exists as the harness's own session id, so Phase B names its column
   differently.
10. **The Python `orange-mcp` shim stays** for the task tools until v2 PR 5 folds them into
    `/mcp/steering`.
