"""Planner stub: writes a deterministic 3-task DAG into the worktree's
architecture.md + plan.json, then emits final.success with both as artifacts.

Used by PlannerServiceIT to exercise PlannerService end-to-end without
calling the real Claude API.
"""

from __future__ import annotations

import json
import os
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

    cmd = json.loads(line)
    if cmd.get("type") != "start":
        emit({"type": "final", "status": "failure", "summary": "bad start"})
        return 2

    cwd = cmd.get("options", {}).get("cwd") or os.getcwd()
    description = cmd.get("prompt", "")

    architecture = (
        "# Architecture\n\n"
        "Problem statement\n----------------\n"
        f"User asked: {description}\n\n"
        "Approach: split work into auth → ui → tests.\n"
    )
    plan = {
        "tasks": [
            {"key": "auth", "title": "Build auth module",
             "description": "JWT-based session handling.", "role": "dev", "priority": 100},
            {"key": "ui", "title": "Build UI",
             "description": "List view + detail view.", "role": "dev", "priority": 100},
            {"key": "tests", "title": "Write integration tests",
             "description": "Cover auth and UI happy paths.", "role": "tester", "priority": 100},
        ],
        "edges": [
            {"from": "auth", "to": "ui"},
            {"from": "ui", "to": "tests"},
        ],
    }

    with open(os.path.join(cwd, "architecture.md"), "w") as f:
        f.write(architecture)
    with open(os.path.join(cwd, "plan.json"), "w") as f:
        json.dump(plan, f, indent=2)

    emit({"type": "ready", "session_id": "planner-stub"})
    emit({"type": "assistant_message", "text": "wrote architecture.md and plan.json"})
    emit({
        "type": "final",
        "status": "success",
        "summary": "planned 3 tasks",
        "artifacts": {"architecture": "architecture.md", "plan": "plan.json"},
    })
    return 0


if __name__ == "__main__":
    sys.exit(main())
