# protocol

Wire-format JSON Schemas shared by `orange/` (Java orchestrator) and `agent-runner/` (Python sidecar).

The orchestrator spawns an `agent-runner` subprocess per agent run and exchanges newline-delimited JSON over the child's stdio:

- `events.schema.json` — messages emitted by the runner on **stdout** (one JSON object per line).
- `commands.schema.json` — messages sent to the runner on **stdin** (one JSON object per line).

## Conventions

- Both sides must always write a trailing `\n` after each JSON object and flush.
- `id` fields correlate request/response pairs (e.g. `hook_request` ↔ `hook_response`).
- `stderr` is human-readable; the orchestrator tees it to logs.
- The runner exits with code 0 after emitting a `final` event. Any non-zero exit without a `final` is a hard failure.

## Versioning

When adding a new event/command type, bump the `$id` query string (`?v=2`) on both sides simultaneously. The orchestrator should reject `ready` events whose `sdk_version`/protocol mismatch its expectation.
