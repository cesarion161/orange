# Project space Phase C: evidence, close and integration for sessions

2026-11-10 · Spec: `docs/superpowers/specs/2026-11-09-orange-project-space-design.md` §12 C · Branch: `feat/project-space-phase-c`

When the judge says a session is finished, orange checks the work itself, integrates it as the
project's conventions say, and closes it. A gap goes back into the session through the stop hook.

## Tasks

- [x] **1. Schema (V21).** `evidence.session_id` (a row belongs to a task or a session),
  `session_work`.
- [x] **2. Conventions.** `WorkConventions`: `done`, `integration`, `evidence`, `base-branch`,
  `remote-push`; `EvidenceCommands` parses the `evidence` value; `EvidenceDetector` guesses it.
- [x] **3. Verify for sessions.** `SessionChecks` behind `EvidenceRunner`: committed, then the
  commands in a scratch checkout of the head.
- [x] **4. Diff review for sessions.** Tool-less, in an empty directory, the diff in the prompt.
- [x] **5. Integration for sessions.** `Integrator` takes a session's branch as well as a task's;
  a session on the base branch is only pushed.
- [x] **6. The close.** `SessionClosing`: the steps, the send-back, `session_work`, the `closed`
  session, the `session_integration` and `evidence_missing` items.
- [x] **7. Time and the user.** A prompt ends the check; late results; a check whose run is gone.
- [x] **8. Tests.** `SessionCloseIT` (18 cases on temporary git repositories with the fake
  `claude`), `EvidenceConventionTest`.
- [ ] **9. Live check** in a sandbox repo: a session verified, closed and merged; a failing test
  sent back as a `continue`.

## Builder's rulings

1. **No evidence commands, no close.** With `done` asking for evidence and no `evidence`
   convention, the session stops as it did before this phase. orange files one `evidence_missing`
   item per project and proposes the commands it detected. Only the user accepts them: they run
   on the user's machine as the user (spec decision 13). So orange integrates nothing in a
   project until its user has said what proves the work.
2. **The scratch checkout is a detached `git worktree`** of the session's repo, under the run's
   own directory, removed when the check ends. It has the commit and nothing else: ignored files
   (`.env`, `node_modules`) are not there, so a project whose checks need them says how to get
   them in its commands.
3. **Uncommitted or untracked files end the check before any command runs**, and the first
   failing command ends it too: the session is waiting, and one clear gap is enough to send back.
4. **The diff review is part of `evidence`,** not a `done` entry of its own. It is skipped when
   there is nothing to diff against (no base branch known, or the head is the base).
5. **A session's reviewer has no file tools** and runs in an empty directory, like its judge
   (Phase B ruling 8). The spec gives it Read, Glob and Grep; that comes back with node-hosted
   runs.
6. **No dedicated integration checkout** (`~/.orange/integration/<repo>`, spec decision 11). The
   rebase happens in the session's own checkout, which is clean and stopped at that point
   (decision 8 allows it), and moving the base needs no checkout: a push, a `merge --ff-only` in
   the clean checkout that has the base, or an `update-ref`.
7. **A session on the base branch behind origin is told to catch up** (`behind`); orange does not
   pull under the user's tree.
8. **`pr` integration is treated as `ask`.** PR mode is a later phase.
9. **Late results** (spec decision 12): a late pass closes and integrates as usual. A late gap is
   not delivered on the next prompt; it expires into a `session_question` item with the gap as the
   proposed answer. Delivery as prompt context needs a reporter change and is left for later.
10. **Orange's own tasks keep `orange.evidence.commands` and `orange.integration.remote-push`.**
    Tasks get project conventions when they get a `project_id` (v2 PR 4).
11. **The rebase sweep is not built.** A task is rebased at integration, one at a time, which is
    the only point where it has no live run; sessions are rebased at the stop orange handles. A
    separate sweep would only add rebases under running agents. It can come back with scope
    claims (v2 PR 4), when early conflict detection has something to act on.
12. **Session runs of the close go ahead of task work** (priority 10) but count against
    `max-concurrent`, unlike the judge: evidence commands are heavy.

## Known limits

- A judge that wrongly says "finished" starts a close. Evidence and the diff review against the
  intent are what stand between that and an integration.
- The rebase at integration rewrites the session's branch while its stop hook waits. A late
  integration (the hook has gone) does the same in an idle checkout; it is skipped when tracked
  files changed since the check.
- One `evidence_missing` item per project, ever: resolving it without setting commands means
  orange stays silent about it.

## Verified

- `./gradlew test`: 401 tests, 0 failures (2026-11-10).
