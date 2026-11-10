#!/usr/bin/env python3
"""orange's session reporter for Claude Code.

Claude Code runs this from the plugin's hooks with the hook's JSON on stdin. It turns the hook
into one of orange's neutral session events and posts it to the coordinator
(POST /sessions/events). It resolves git here, on the machine that has the files, so the
coordinator never needs to read them.

It must never get in the session's way: it prints nothing, always exits 0, and gives up on the
coordinator after a short timeout (a session works the same with orange down).

Environment:
  ORANGE_URL             the coordinator (default http://localhost:8080)
  ORANGE_API_TOKEN       bearer token, when the coordinator wants one
  ORANGE_NODE            this machine's name in orange (default: its hostname)
  ORANGE_REPORT          set to 0 to switch the reporter off
  ORANGE_REPORT_TIMEOUT  seconds to wait for the coordinator (default 2)
  ORANGE_NODE_DIR        where the reporter keeps its markers (default ~/.orange/node)
"""

import json
import os
import socket
import subprocess
import sys
import urllib.request
import uuid
from datetime import datetime, timezone
from urllib.parse import urlsplit, urlunsplit

MAX_PROMPT = 4000
MAX_MESSAGE = 8000

# Notification types that mean "the session cannot go on without the user".
WAITING = {
    "permission_prompt": "permission",
    "elicitation_dialog": "input",
    "elicitation_url_dialog": "input",
    "agent_needs_input": "input",
    "idle_prompt": "idle",
}
# Notification types that mean the user answered and the session works again.
RESUMED = {"elicitation_complete", "elicitation_response"}


def node_dir():
    return os.environ.get("ORANGE_NODE_DIR") or os.path.join(os.path.expanduser("~"), ".orange", "node")


def marker(session_id):
    # The id is the harness's own; keep it from naming anything outside the directory.
    safe = "".join(c for c in session_id if c.isalnum() or c in "-_")
    return os.path.join(node_dir(), "waiting", safe or "unknown")


def git(cwd, *args):
    try:
        out = subprocess.run(["git", "-C", cwd, *args], capture_output=True, text=True, timeout=1)
    except (OSError, subprocess.SubprocessError):
        return None
    text = out.stdout.strip()
    return text if out.returncode == 0 and text else None


def without_credentials(url):
    """https://token@host/x and ssh://user:pass@host/x lose their userinfo; scp-style urls have none."""
    try:
        parts = urlsplit(url)
    except ValueError:
        return url
    if parts.scheme and "@" in parts.netloc:
        userinfo, host = parts.netloc.rsplit("@", 1)
        # A bare user (ssh://git@host) is no secret; a password or an http(s) token is.
        if ":" in userinfo or parts.scheme in ("http", "https"):
            return urlunsplit(parts._replace(netloc=host))
    return url


def git_facts(cwd):
    """The main checkout (also for a session in a worktree), this checkout, the branch, the remote."""
    if not cwd or not os.path.isdir(cwd):
        return {}
    common = git(cwd, "rev-parse", "--path-format=absolute", "--git-common-dir")
    if not common:
        return {}
    facts = {"repo_root": os.path.dirname(common) if os.path.basename(common) == ".git" else common}
    top = git(cwd, "rev-parse", "--show-toplevel")
    if top:
        facts["worktree"] = top
    branch = git(cwd, "symbolic-ref", "--quiet", "--short", "HEAD")
    if branch:
        facts["branch"] = branch
    remote = git(cwd, "config", "--get", "remote.origin.url")
    if remote:
        facts["remote_url"] = without_credentials(remote)
    default = git(cwd, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")
    if default:
        facts["default_branch"] = default.split("/", 1)[1] if "/" in default else default
    return facts


def translate(hook):
    """(type, payload, with_git) for a hook, or None when orange has no use for it."""
    name = hook.get("hook_event_name") or (sys.argv[1] if len(sys.argv) > 1 else "")
    if name == "SessionStart":
        return "session_started", {"source": hook.get("source")}, True
    if name == "UserPromptSubmit":
        return "user_prompt", {"prompt": (hook.get("prompt") or "")[:MAX_PROMPT]}, True
    if name == "Stop":
        return "stopped", {
            "last_message": (hook.get("last_assistant_message") or "")[-MAX_MESSAGE:],
            "transcript_path": hook.get("transcript_path"),
            "stop_hook_active": bool(hook.get("stop_hook_active")),
        }, True
    if name == "Notification":
        kind = hook.get("notification_type")
        if kind in WAITING:
            return "waiting", {"reason": WAITING[kind], "message": hook.get("message")}, False
        if kind in RESUMED:
            return "active", {}, False
        return None
    if name == "PostToolUse":
        # Only worth a report when the session was waiting: a tool ran, so the user answered.
        return ("active", {}, False) if os.path.exists(marker(hook.get("session_id") or "")) else None
    if name == "SessionEnd":
        return "session_ended", {"reason": hook.get("reason")}, False
    return None


def remember_wait(session_id, event_type, payload):
    """The marker is how PostToolUse knows, without asking orange, that this session was waiting."""
    path = marker(session_id)
    try:
        if event_type == "waiting" and payload.get("reason") != "idle":
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w"):
                pass
        elif event_type != "waiting" and os.path.exists(path):
            os.remove(path)
    except OSError:
        pass


def main():
    if os.environ.get("ORANGE_REPORT", "1") == "0":
        return
    hook = json.load(sys.stdin)
    session_id = hook.get("session_id")
    translated = translate(hook)
    if not session_id or translated is None:
        return
    event_type, payload, with_git = translated
    remember_wait(session_id, event_type, payload)

    cwd = hook.get("cwd") or os.getcwd()
    event = {
        "event_id": str(uuid.uuid4()),
        "harness": "claude-code",
        "session_id": session_id,
        "type": event_type,
        "at": datetime.now(timezone.utc).isoformat(),
        "node": os.environ.get("ORANGE_NODE") or socket.gethostname(),
        "cwd": cwd,
        "payload": {k: v for k, v in payload.items() if v is not None},
    }
    if with_git:
        event.update(git_facts(cwd))

    url = os.environ.get("ORANGE_URL", "http://localhost:8080").rstrip("/") + "/sessions/events"
    headers = {"Content-Type": "application/json"}
    token = os.environ.get("ORANGE_API_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=json.dumps(event).encode("utf-8"), headers=headers, method="POST")
    urllib.request.urlopen(request, timeout=float(os.environ.get("ORANGE_REPORT_TIMEOUT", "2"))).read()


if __name__ == "__main__":
    try:
        main()
    except Exception:  # noqa: BLE001 — a reporter failure must never reach the session
        pass
    sys.exit(0)
