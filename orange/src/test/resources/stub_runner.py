"""Test stub for AgentRunnerProcessIT.

Reads NDJSON commands on stdin like the real agent-runner, but instead of
talking to Claude it emits a pre-scripted sequence of events:

  start  -> ready, assistant_message, hook_request
            (then waits for hook_response on stdin)
            (then waits for cancel OR `final_now=true` start option)
  cancel -> emits final{status='cancelled'} and exits 0
  hook_response -> emits final{status='success'} and exits 0
  signal -> echoed back as an assistant_message ("got signal: <name>")

This keeps the Java protocol layer testable without network/API access.
"""

from __future__ import annotations

import json
import sys


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def log(msg: str) -> None:
    sys.stderr.write(f"[stub-runner] {msg}\n")
    sys.stderr.flush()


def main() -> int:
    start_line = sys.stdin.readline()
    if not start_line:
        emit({"type": "error", "code": "bad_start", "message": "stdin closed before start"})
        emit({"type": "final", "status": "failure", "summary": "no start"})
        return 2

    start = json.loads(start_line)
    if start.get("type") != "start":
        emit({"type": "error", "code": "bad_start", "message": f"first command must be 'start', got {start.get('type')!r}"})
        emit({"type": "final", "status": "failure", "summary": "no start"})
        return 2

    log(f"start prompt={start.get('prompt')!r}")

    emit({"type": "ready", "session_id": "stub-session-1"})
    emit({"type": "assistant_message", "text": "hello from stub"})
    emit({"type": "hook_request", "id": "h1", "event": "PreToolUse", "tool": "Write",
          "input": {"file_path": "/tmp/example"}})

    # Wait for either a hook_response, a cancel, or stdin EOF.
    while True:
        line = sys.stdin.readline()
        if not line:
            log("stdin closed; emitting failure final")
            emit({"type": "final", "status": "failure", "summary": "stdin closed"})
            return 1
        try:
            cmd = json.loads(line)
        except json.JSONDecodeError as exc:
            emit({"type": "error", "code": "bad_stdin", "message": str(exc)})
            continue

        kind = cmd.get("type")
        if kind == "hook_response":
            log(f"got hook_response decision={cmd.get('decision')}")
            emit({"type": "final", "status": "success", "summary": "ok"})
            return 0
        if kind == "cancel":
            log("got cancel")
            emit({"type": "final", "status": "cancelled", "summary": cmd.get("reason")})
            return 0
        if kind == "signal":
            emit({"type": "assistant_message", "text": f"got signal: {cmd.get('name')}"})
            continue
        emit({"type": "error", "code": "bad_command", "message": f"unknown command {kind!r}"})


if __name__ == "__main__":
    sys.exit(main())
