"""Tester stub: reads its worktree from start.options.cwd, writes report.md
with VERDICT: FAIL on the first invocation and VERDICT: PASS on the second
(tracked via a `.tester_count` counter file in the worktree). Always emits
final.success with artifacts.report -> report.md.

Used by PipelineRetryIT to exercise the dev_qa retry-previous loop.
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
    counter_path = os.path.join(cwd, ".tester_count")
    n = 0
    if os.path.exists(counter_path):
        try:
            n = int(open(counter_path).read().strip() or "0")
        except ValueError:
            n = 0
    n += 1
    with open(counter_path, "w") as f:
        f.write(str(n))

    verdict = "PASS" if n >= 2 else "FAIL"
    report_path = os.path.join(cwd, "report.md")
    with open(report_path, "w") as f:
        f.write(f"VERDICT: {verdict}\n")
        f.write(f"Run number: {n}\n")
        if verdict == "FAIL":
            f.write("Reason: stub configured to fail on the first call.\n")

    emit({"type": "ready", "session_id": f"tester-stub-{n}"})
    emit({"type": "assistant_message", "text": f"ran tests; verdict={verdict}"})
    emit({
        "type": "final",
        "status": "success",
        "summary": f"verdict={verdict}",
        "artifacts": {"report": "report.md"},
    })
    return 0


if __name__ == "__main__":
    sys.exit(main())
