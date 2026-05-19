# agent-runner

Python sidecar that wraps the [Claude Agent SDK](https://docs.claude.com/en/docs/agent-sdk) and speaks newline-delimited JSON over stdio with the `orange/` orchestrator.

One process == one agent run. The orchestrator spawns this process per task,
streams events, and SIGTERMs it on cancel.

## Wire format

See `../protocol/` for JSON Schemas. In short:

- **stdin**: commands (`start`, `hook_response`, `signal`, `cancel`).
- **stdout**: NDJSON events (`ready`, `assistant_message`, `tool_use`, `tool_result`,
  `hook_request`, `final`, …).
- **stderr**: human-readable logs.

## Local dev

Requires [uv](https://docs.astral.sh/uv/).

```sh
cd agent-runner
uv sync
uv run python -m agent_runner < example_start.jsonl
```

The orchestrator invokes us as:

```sh
python -u -m agent_runner
```

(`-u` is mandatory — without it stdout buffers and the orchestrator sees nothing
until the process exits.)

## Layout

```
src/agent_runner/
├── __init__.py
├── __main__.py    entrypoint, signal handlers
├── runner.py      stdio loop, ClaudeSDKClient driver, hook bridge
├── hooks.py       worktree-guard hook
└── protocol.py    typed event/command envelopes
```
