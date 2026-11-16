#!/usr/bin/env python3
"""orange's session reporter for Claude Code.

Claude Code runs this from the plugin's hooks with the hook's JSON on stdin. It turns the hook
into one of orange's neutral session events and posts it to the coordinator
(POST /sessions/events). It resolves git here, on the machine that has the files, so the
coordinator never needs to read them.

It must never get in the session's way: it always exits 0 and gives up on the coordinator after
a short timeout (a session works the same with orange down). It prints nothing, with two
exceptions: at a Stop, when orange decides to answer the session itself, it prints the hook
decision that blocks the stop with orange's answer; and at the start of a steering session it
prints orange's few lines of status as the session's added context.

A steering session is the tab the user talks to orange in (started with ORANGE_STEERING=1, or in
the steering directory). Its events carry payload.steering, and orange records them but never
judges the session, files nothing about it and maps it to no project.

While orange is out of reach (the laptop offline, the VPS restarting) events wait on disk, one file
each under <node dir>/queue/, and the next hook that gets through sends them first, oldest first.
Each keeps its event id, so a replay that orange already took is stored once. The queue keeps the
newest 1000 events of the last 7 days. An event orange refuses for good (a 4xx other than
401/403/408/425/429) is dropped; anything else waits for the next hook. A replayed event carries
payload.replayed, and orange never judges a replayed stop: its hook is gone. A stop is waited on for
a verdict only when this hook sent it.

Environment:
  ORANGE_URL             the coordinator (default http://localhost:8080)
  ORANGE_API_TOKEN       bearer token, when the coordinator wants one
  ORANGE_NODE            this machine's name in orange (default: its hostname)
  ORANGE_REPORT          set to 0 to switch the reporter off
  ORANGE_REPORT_TIMEOUT  seconds to wait for the coordinator (default 2)
  ORANGE_NODE_DIR        where the reporter keeps its markers (default ~/.orange/node)
  ORANGE_HOOK_WAIT       seconds a Stop hook waits for orange's verdict (default 600; 0 = never wait)
  ORANGE_STEERING        set to 1 to make this session the steering tab
  ORANGE_STEERING_DIR    a session at or under it is the steering tab too (default ~/.orange/steering)
"""

import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from urllib.parse import urlencode, urlsplit, urlunsplit

MAX_PROMPT = 4000
MAX_MESSAGE = 8000
# At a stop: at most this many uncommitted paths, and this long for git to say them.
MAX_DIRTY = 50
STOP_GIT_TIMEOUT = 5
# The disk queue: its bounds, and the refusals that are worth sending again.
QUEUE_MAX = 1000
QUEUE_MAX_AGE = 7 * 24 * 3600
RETRY_STATUSES = {401, 403, 408, 425, 429}

try:
    import fcntl
except ImportError:  # not on this platform: flushes then just do not exclude each other
    fcntl = None

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


def queue_dir():
    return os.path.join(node_dir(), "queue")


def queued():
    """The queued events' files, oldest first (their names start with the time they were made)."""
    try:
        names = sorted(n for n in os.listdir(queue_dir()) if n.endswith(".json") and not n.startswith("."))
    except OSError:
        return []
    return [os.path.join(queue_dir(), n) for n in names]


def enqueue(event):
    """Writes the event to the queue in one rename, so a reader never sees half of it. Returns its path."""
    os.makedirs(queue_dir(), exist_ok=True)
    safe = "".join(c for c in event["event_id"] if c.isalnum() or c == "-")[:64]
    name = "%d-%s.json" % (time.time_ns(), safe)  # 19 digits until the year 2286: sorts by time
    tmp = os.path.join(queue_dir(), "." + name + ".tmp")
    with open(tmp, "w") as f:
        json.dump(event, f)
    path = os.path.join(queue_dir(), name)
    os.replace(tmp, path)
    return path


def prune(files):
    """Drops what is older than QUEUE_MAX_AGE, then all but the newest QUEUE_MAX. Returns what is left."""
    cutoff = time.time() - QUEUE_MAX_AGE
    kept = []
    for f in files:
        try:
            if os.path.getmtime(f) < cutoff:
                os.remove(f)
                continue
        except OSError:
            continue
        kept.append(f)
    for f in kept[:-QUEUE_MAX]:
        try:
            os.remove(f)
        except OSError:
            pass
    return kept[-QUEUE_MAX:]


def post(url, data, headers, timeout):
    """Posts one event: "ok", "retry" (orange out of reach, or a refusal that may pass later) or "drop"."""
    try:
        request = urllib.request.Request(url, data=data, headers=headers, method="POST")
        urllib.request.urlopen(request, timeout=timeout).read()
        return "ok"
    except urllib.error.HTTPError as e:
        return "retry" if e.code >= 500 or e.code in RETRY_STATUSES else "drop"
    except Exception:  # noqa: BLE001 — unreachable, timed out, reset: all mean "later"
        return "retry"


def flush(url, headers, deadline, mine=None):
    """Sends the queue oldest first until it is empty, orange fails, or the hook's time is up.

    One hook at a time: another one holding the lock is already sending, so this one leaves it.
    Every event but this hook's own (`mine`) goes with payload.replayed: its hook is gone, so
    nothing waits for a verdict on it and orange does not judge it.
    """
    try:
        os.makedirs(queue_dir(), exist_ok=True)
        lock = open(os.path.join(queue_dir(), ".lock"), "w")
    except OSError:
        return
    with lock:
        if fcntl is not None:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                return
        for f in prune(queued()):
            left = deadline - time.monotonic()
            if left <= 0.05:
                return
            try:
                with open(f, "rb") as fh:
                    data = fh.read()
            except OSError:
                continue
            if f != mine:
                data = replayed(data)
            if post(url, data, headers, left) == "retry":
                return
            try:
                os.remove(f)
            except OSError:
                pass


def replayed(data):
    """The event marked as sent late; anything that is not an event goes as it is (orange refuses it)."""
    try:
        event = json.loads(data)
        if isinstance(event, dict):
            payload = event.get("payload")
            event["payload"] = dict(payload, replayed=True) if isinstance(payload, dict) else {"replayed": True}
            return json.dumps(event).encode("utf-8")
    except ValueError:
        pass
    return data


def git(cwd, *args):
    out = git_raw(cwd, *args)
    text = out.strip() if out is not None else ""
    return text or None


def git_raw(cwd, *args, timeout=1):
    """git's stdout as it is (an empty string is an answer), or None when git failed."""
    try:
        out = subprocess.run(["git", "-C", cwd, *args], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        return None
    return out.stdout if out.returncode == 0 else None


def stop_facts(cwd):
    """What orange needs to close the work from GitHub: the head, and what is not committed.

    orange never reads the checkout itself (remote-first spec §7.4). A fact git cannot give is left out.
    """
    if not cwd or not os.path.isdir(cwd):
        return {}
    facts = {}
    head = git(cwd, "rev-parse", "HEAD")
    if head:
        facts["head_sha"] = head
    status = git_raw(cwd, "status", "--porcelain", timeout=STOP_GIT_TIMEOUT)
    if status is not None:
        facts["dirty_files"] = [line for line in status.splitlines() if line.strip()][:MAX_DIRTY]
    return facts


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


def steering(cwd):
    """Whether this is the steering tab: ORANGE_STEERING=1, or a cwd at or under ORANGE_STEERING_DIR (real paths)."""
    if os.environ.get("ORANGE_STEERING") == "1":
        return True
    root = os.environ.get("ORANGE_STEERING_DIR") or os.path.join(os.path.expanduser("~"), ".orange", "steering")
    if not cwd:
        return False
    try:
        root = os.path.realpath(root)
        here = os.path.realpath(cwd)
        return os.path.commonpath([root, here]) == root
    except (OSError, ValueError):  # another drive, an unreadable path: not under it
        return False


def steering_context(headers, deadline):
    """orange's start context for the steering tab, or None: anything but a 200 with text is nothing."""
    left = deadline - time.monotonic()
    if left <= 0.05:
        return None
    url = os.environ.get("ORANGE_URL", "http://localhost:8080").rstrip("/") + "/steering/context"
    try:
        request = urllib.request.Request(url, headers={k: v for k, v in headers.items() if k == "Authorization"},
                                         method="GET")
        with urllib.request.urlopen(request, timeout=left) as response:
            if response.status != 200:
                return None
            text = response.read(64 * 1024).decode("utf-8").strip()
    except Exception:  # noqa: BLE001 — orange down, a refusal, a timeout: the tab just starts without it
        return None
    return text or None


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
    timeout = float(os.environ.get("ORANGE_REPORT_TIMEOUT", "2"))
    deadline = time.monotonic() + timeout
    url = os.environ.get("ORANGE_URL", "http://localhost:8080").rstrip("/") + "/sessions/events"
    headers = {"Content-Type": "application/json"}
    token = os.environ.get("ORANGE_API_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    if not session_id or translated is None:
        # Nothing to say, but a chance to send what waits.
        if queued():
            flush(url, headers, deadline)
        return
    event_type, payload, with_git = translated
    remember_wait(session_id, event_type, payload)

    cwd = hook.get("cwd") or os.getcwd()
    if steering(cwd):
        payload["steering"] = True
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
    if event_type == "stopped":
        event["payload"].update(stop_facts(cwd))

    # The budget is for talking to orange; git (up to STOP_GIT_TIMEOUT at a stop) does not spend it.
    deadline = time.monotonic() + timeout
    if queued():
        # Behind what waits: orange applies a session's events in the order it gets them.
        mine = enqueue(event)
        flush(url, headers, deadline, mine)
        delivered = not os.path.exists(mine)
    else:
        outcome = post(url, json.dumps(event).encode("utf-8"), headers, timeout)
        if outcome == "retry":
            enqueue(event)
        delivered = outcome == "ok"

    if event_type == "session_started" and payload.get("steering") and delivered:
        # The tab starts from facts: orange's few lines of status, in what is left of the budget. Asked
        # only of an orange that just took the event; one that did not would not answer this either.
        context = steering_context(headers, deadline)
        if context:
            print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": context}}))

    if event_type == "stopped" and delivered:
        message = wait_for_verdict(event, headers)
        if message:
            # orange's answer, which keeps the session going.
            print(json.dumps({"decision": "block", "reason": message}))


def wait_for_verdict(event, headers):
    """Long-polls orange for its verdict on this stop. Returns the message of a `continue`, else None.

    orange says when to stop asking ("none"); ORANGE_HOOK_WAIT (seconds, default 600, 0 = do not
    wait) is this side's own limit. Any failure means no verdict: the session just stops.
    """
    limit = float(os.environ.get("ORANGE_HOOK_WAIT", "600"))
    deadline = time.monotonic() + limit
    base = os.environ.get("ORANGE_URL", "http://localhost:8080").rstrip("/") + "/sessions/verdict?"
    while time.monotonic() < deadline:
        wait = max(1, min(20, int(deadline - time.monotonic())))
        query = urlencode({"harness": event["harness"], "session_id": event["session_id"],
                           "event_id": event["event_id"], "wait": wait})
        request = urllib.request.Request(base + query, headers=headers, method="GET")
        answer = json.load(urllib.request.urlopen(request, timeout=wait + 10))
        state = answer.get("state")
        if state == "verdict":
            if answer.get("kind") != "continue" or not answer.get("message"):
                return None
            # The answer counts only once orange has it as delivered. If the user spoke meanwhile, or
            # this call fails, say nothing: orange keeps the answer for the user instead.
            ack = urllib.request.Request(base.replace("/verdict?", "/verdict/ack?") + query, headers=headers,
                                         data=b"", method="POST")
            ok = json.load(urllib.request.urlopen(ack, timeout=5)).get("ok")
            return answer["message"] if ok else None
        if state != "pending":
            return None
    return None


if __name__ == "__main__":
    try:
        main()
    except Exception:  # noqa: BLE001 — a reporter failure must never reach the session
        pass
    sys.exit(0)
