# Project space Phase B: the session judge and hook verdicts

2026-11-10 · Spec: `docs/superpowers/specs/2026-11-09-orange-project-space-design.md` §12 B · Branch: `feat/project-space-phase-b`

At a stop, a short judge run decides whether orange answers the session itself, the user must
decide, or nothing is asked. The verdict reaches the session through the Stop hook.

## Tasks

- [x] **1. Schema (V20).** `runs.attended_session_id`, `sessions.stop_event_id`, `verdicts`.
- [x] **2. Judge runs.** `RunKind.JUDGE` in `WorkerRunner`, routing role `judge` (small, low),
  `ToolPolicy` (read-only, no shell), `roles/judge.md`, `JudgePrompt`.
- [x] **3. Verdict tools** on `/mcp/worker`: `judge_continue`, `judge_verify`, `judge_escalate`,
  `judge_idle`, `propose_item`; `RunOutcome.Verdict`.
- [x] **4. Handing a stop over.** `SessionReactor` (`Judging`), `SessionService.apply`: `judging`
  status, the loop guard, user-prompt cancellation.
- [x] **5. Applying the decision.** `SessionJudgement.after`: confidence threshold, decision
  recorded in the space, escalation with a proposed answer, stale decisions dropped, fallback.
- [x] **6. Delivery.** `GET /sessions/verdict` long-poll; the reporter blocks the stop with
  orange's answer; fail-open.
- [x] **7. Time.** Uncollected answers expire into the inbox; undecided stops fall back.
- [x] **8. Tests.** `JudgeIT` (each verdict with the fake `claude`, the real Stop hook end to end),
  reactor rules, fake scenarios.
- [ ] **9. Live check.** A real session asking "proceed?" is continued by orange. Needs
  `CLAUDE_CODE_OAUTH_TOKEN` in `orange/.env`, which is not set (2026-11-10).

## Builder's rulings

1. **The judge's input is its prompt.** The spec has the judge fetch it with `get_session`; here
   `JudgePrompt` puts the same slice of state into the prompt, as the diff review does with its
   diff. One tool call fewer per stop, and the input is on record with the run.
2. **"Recent turns" are the events orange already has** (the latest prompts and stops, 600
   characters each), not a read of the transcript: the transcript lives on the node (spec §3).
3. **A verdict belongs to one stop.** `sessions.stop_event_id` is the reporter's id of the latest
   stop; a verdict is delivered only to that stop, once. Anything else the session does (a prompt,
   activity, a newer stop) voids it.
4. **orange holds the confidence line, not the judge.** A `continue` below
   `auto-answer-confidence` is turned into an escalation with the judge's answer as the proposal.
   The other critical-decision rules (spec decision 4) are the judge's instructions; they cannot
   be checked mechanically.
5. **`verify` only lets the session stop for now.** Verification, close and integration of
   sessions are Phase C.
6. **Judge proposals stay proposals.** Spec decision 1 lets orange accept notes and decisions by
   default; this phase leaves every proposal to the user. What orange itself decided (a
   `continue`) is recorded as an active `orange` decision.
7. **No judging while the scheduler is paused** (plan limit, missing token): the stop follows
   Phase A's rules at once, instead of leaving the hook to wait for a run that cannot start.
8. **Process mode reads the user's checkout.** The judge runs in the session's own directory with
   Read, Glob and Grep only (spec decision 13). When the coordinator cannot see that directory it
   gets an empty one and judges from its prompt.
9. **Deferred:** `search_notes` and `get_session` tools, ntfy action buttons, delivering the
   user's `orange_resolve` answer back into a stopped session, and the medium tier for risky
   projects (nothing marks a project risky yet).

## Open

- Whether the TUI accepts input while a Stop hook runs is still unverified (spec §3, decision
  12). If it does not, `hook-wait` should drop to 60 s. The judge normally answers in seconds, so
  the default stays 10 minutes until a real session shows otherwise.
