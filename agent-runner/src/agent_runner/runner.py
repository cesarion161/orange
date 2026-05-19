"""Main agent-runner loop.

Reads NDJSON commands from stdin, drives a `ClaudeSDKClient`, and emits
NDJSON events on stdout. One process == one agent run.
"""

from __future__ import annotations

import asyncio
import json
import logging
import sys
import uuid
from pathlib import Path
from typing import Any

from claude_agent_sdk import (
    AssistantMessage as SdkAssistantMessage,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    HookMatcher,
    ResultMessage as SdkResultMessage,
    TextBlock,
    ThinkingBlock,
    ToolResultBlock,
    ToolUseBlock,
)

from . import protocol as p
from .hooks import chdir_guard, worktree_guard

log = logging.getLogger("agent_runner")


class Runner:
    def __init__(self) -> None:
        self._pending_hook_replies: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._signal_queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self._cancel_event = asyncio.Event()
        self._stdin: asyncio.StreamReader | None = None

    async def run(self) -> int:
        try:
            cmd = await self._read_start()
        except Exception as exc:
            msg = f"failed to read start command: {exc}"
            p.emit_dc(p.Error(message=msg, code="bad_start"))
            p.emit_dc(p.Final(status="failure", summary=msg))
            return 2

        opts_in = cmd.get("options", {}) or {}
        cwd = Path(opts_in.get("cwd") or ".").resolve()
        chdir_guard(cwd)

        hooks_via_runner = set(opts_in.get("hooks_via_runner") or [])

        sdk_hooks: dict[str, list[HookMatcher]] = {
            "PreToolUse": [HookMatcher(matcher=None, hooks=[worktree_guard(cwd)])],
        }
        if "PreToolUse" in hooks_via_runner:
            sdk_hooks["PreToolUse"].append(
                HookMatcher(matcher=None, hooks=[self._round_trip_hook("PreToolUse")])
            )

        options = ClaudeAgentOptions(
            cwd=str(cwd),
            system_prompt=opts_in.get("system_prompt"),
            model=opts_in.get("model"),
            fallback_model=opts_in.get("fallback_model"),
            allowed_tools=opts_in.get("allowed_tools"),
            disallowed_tools=opts_in.get("disallowed_tools"),
            permission_mode=opts_in.get("permission_mode", "acceptEdits"),
            max_turns=opts_in.get("max_turns"),
            max_budget_usd=opts_in.get("max_budget_usd"),
            agents=opts_in.get("agents"),
            mcp_servers=opts_in.get("mcp_servers") or {},
            hooks=sdk_hooks,
        )

        final_status = "failure"
        final_summary: str | None = None

        try:
            async with ClaudeSDKClient(options=options) as client:
                session_id = getattr(client, "session_id", None) or str(uuid.uuid4())
                p.emit_dc(p.Ready(session_id=session_id))

                stdin_task = asyncio.create_task(self._stdin_pump())
                signal_task = asyncio.create_task(self._signal_pump(client))
                try:
                    await client.query(cmd["prompt"])
                    async for msg in client.receive_response():
                        if self._cancel_event.is_set():
                            break
                        self._translate(msg)
                finally:
                    signal_task.cancel()
                    stdin_task.cancel()

            final_status = "cancelled" if self._cancel_event.is_set() else "success"
        except Exception as exc:
            log.exception("agent-runner crashed")
            p.emit_dc(p.Error(message=str(exc), code="unhandled"))
            final_status = "failure"
            final_summary = str(exc)
        finally:
            p.emit_dc(p.Final(status=final_status, summary=final_summary))

        return 0 if final_status != "failure" else 1

    # ──────────────────────────── stdin pump ─────────────────────────────

    async def _stdin_reader(self) -> asyncio.StreamReader:
        """Wrap sys.stdin in an asyncio StreamReader so reads can be cancelled
        cleanly. Using `asyncio.to_thread(sys.stdin.readline)` here would create
        a default-executor thread that blocks forever on `readline()` — and
        `asyncio.run()` waits on the default executor at shutdown, deadlocking
        the process even after the main task returns. Proper async I/O via
        `connect_read_pipe` doesn't have this problem."""
        if self._stdin is None:
            loop = asyncio.get_running_loop()
            reader = asyncio.StreamReader()
            protocol = asyncio.StreamReaderProtocol(reader)
            await loop.connect_read_pipe(lambda: protocol, sys.stdin)
            self._stdin = reader
        return self._stdin

    async def _read_start(self) -> dict[str, Any]:
        reader = await self._stdin_reader()
        line = await reader.readline()
        if not line:
            raise RuntimeError("stdin closed before start")
        msg = json.loads(line.decode("utf-8"))
        if msg.get("type") != "start":
            raise RuntimeError(f"first command must be 'start', got {msg.get('type')!r}")
        return msg

    async def _stdin_pump(self) -> None:
        """Forward subsequent stdin commands (hook_response, signal, cancel).

        Reads via the shared asyncio StreamReader so cancellation closes the
        loop cleanly. Returns on EOF (parent closed its write end) or when a
        cancel command arrives."""
        reader = await self._stdin_reader()
        while True:
            line = await reader.readline()
            if not line:
                return
            try:
                msg = json.loads(line.decode("utf-8"))
            except json.JSONDecodeError as exc:
                p.emit_dc(p.Error(message=f"bad stdin JSON: {exc}", code="bad_stdin"))
                continue
            kind = msg.get("type")
            if kind == "hook_response":
                fut = self._pending_hook_replies.pop(msg["id"], None)
                if fut and not fut.done():
                    fut.set_result(msg)
            elif kind == "signal":
                await self._signal_queue.put(msg)
            elif kind == "cancel":
                self._cancel_event.set()
                return
            else:
                p.emit_dc(p.Error(message=f"unknown command {kind!r}", code="bad_command"))

    # ──────────────────────────── signals ────────────────────────────────

    async def _signal_pump(self, client: ClaudeSDKClient) -> None:
        """Forward each `signal` stdin command to the SDK as a follow-up user
        message. Signals are external information the orchestrator wants the
        agent to reconsider against (e.g. "PR review submitted")."""
        while True:
            sig = await self._signal_queue.get()
            name = sig.get("name", "unknown")
            payload = sig.get("payload")
            text = f"[orchestrator signal] {name}"
            if payload is not None:
                try:
                    text += "\n" + json.dumps(payload, indent=2)
                except Exception:
                    text += f"\n{payload!r}"
            try:
                await client.query(text)
            except Exception:
                log.exception("failed to forward signal %r", name)

    # ──────────────────────────── hooks ──────────────────────────────────

    def _round_trip_hook(self, event: str):
        async def hook(input_data: dict[str, Any], tool_use_id: str | None, ctx: Any) -> dict[str, Any]:
            req_id = uuid.uuid4().hex
            fut: asyncio.Future[dict[str, Any]] = asyncio.get_event_loop().create_future()
            self._pending_hook_replies[req_id] = fut
            p.emit_dc(
                p.HookRequest(
                    id=req_id,
                    event=event,
                    tool=input_data.get("tool_name", ""),
                    input=input_data.get("tool_input", {}) or {},
                )
            )
            try:
                reply = await asyncio.wait_for(fut, timeout=300)
            except asyncio.TimeoutError:
                self._pending_hook_replies.pop(req_id, None)
                return {}  # fail open; sandbox/worktree guards still apply
            if reply.get("decision") == "block":
                return {
                    "hookSpecificOutput": {
                        "hookEventName": event,
                        "permissionDecision": "deny",
                        "permissionDecisionReason": reply.get("reason", "blocked by orchestrator"),
                    }
                }
            return {}

        return hook

    # ──────────────────────────── translate ──────────────────────────────

    def _translate(self, msg: object) -> None:
        if isinstance(msg, SdkAssistantMessage):
            for block in msg.content:
                if isinstance(block, TextBlock):
                    p.emit_dc(p.AssistantMessage(text=block.text, usage=_usage_of(msg)))
                elif isinstance(block, ThinkingBlock):
                    p.emit_dc(p.Thinking(text=block.thinking))
                elif isinstance(block, ToolUseBlock):
                    p.emit_dc(p.ToolUse(id=block.id, tool=block.name, input=block.input or {}))
                elif isinstance(block, ToolResultBlock):
                    p.emit_dc(
                        p.ToolResult(
                            id=block.tool_use_id,
                            is_error=bool(getattr(block, "is_error", False)),
                            output=str(block.content or ""),
                        )
                    )
            return
        if isinstance(msg, SdkResultMessage):
            # The SDK emits this once per turn / completion with aggregate
            # cost + usage. Forward it as a Cost event so the orchestrator's
            # task_events stream gets per-run spend telemetry.
            usd = getattr(msg, "total_cost_usd", None) or 0.0
            tokens = _tokens_from_result(msg)
            p.emit_dc(p.Cost(usd=float(usd), tokens=tokens))


def _usage_of(msg: SdkAssistantMessage) -> dict[str, int] | None:
    usage = getattr(msg, "usage", None)
    if not usage:
        return None
    return {
        "input": getattr(usage, "input_tokens", 0) or 0,
        "output": getattr(usage, "output_tokens", 0) or 0,
        "cache_read": getattr(usage, "cache_read_input_tokens", 0) or 0,
        "cache_creation": getattr(usage, "cache_creation_input_tokens", 0) or 0,
    }


def _tokens_from_result(msg: SdkResultMessage) -> dict[str, int] | None:
    """Pull a flat input/output/cache token map out of `ResultMessage.usage`.
    SDK shape varies — usage may be a dict or a typed object — so we read
    defensively and only emit what's present."""
    usage = getattr(msg, "usage", None)
    if not usage:
        return None
    if isinstance(usage, dict):
        out: dict[str, int] = {}
        for key, name in (
            ("input", "input_tokens"),
            ("output", "output_tokens"),
            ("cache_read", "cache_read_input_tokens"),
            ("cache_creation", "cache_creation_input_tokens"),
        ):
            v = usage.get(name)
            if isinstance(v, (int, float)) and v:
                out[key] = int(v)
        return out or None
    return None
