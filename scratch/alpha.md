# Alpha

Builds on the foundation in [base.md](base.md).

The **agent-runner** is the alpha component: it receives a `start` command on stdin, drives a single Claude SDK session, and exits after emitting a `final` event.
Each run is ephemeral — state is never held in memory across runs; the orchestrator re-supplies everything needed via the `start` payload.
Sandboxing is enforced by the `worktree_guard` hook, which blocks any file write or shell command that escapes the task's dedicated git worktree.
