"""Simple Phase-3 stub: read start, emit ready + assistant + final, exit.

No hook round-trip, no signals — exercises just the linear happy path so
DevTaskActivitiesImpl.runAgent has something deterministic to drive.
"""

from __future__ import annotations

import json
import sys


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def main() -> int:
    line = sys.stdin.readline()
    if not line:
        emit({"type": "error", "code": "bad_start", "message": "no start"})
        emit({"type": "final", "status": "failure", "summary": "no start"})
        return 2

    start = json.loads(line)
    if start.get("type") != "start":
        emit({"type": "error", "code": "bad_start", "message": f"got {start.get('type')!r}"})
        emit({"type": "final", "status": "failure", "summary": "bad start"})
        return 2

    emit({"type": "ready", "session_id": "phase3-stub"})
    emit({"type": "assistant_message", "text": "doing the work"})
    emit({"type": "final", "status": "success", "summary": "ok"})
    return 0


if __name__ == "__main__":
    sys.exit(main())
