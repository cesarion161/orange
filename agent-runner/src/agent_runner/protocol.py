"""Typed envelopes for the NDJSON wire protocol.

Mirrors `protocol/events.schema.json` and `protocol/commands.schema.json` at the repo root.
"""

from __future__ import annotations

import json
import sys
from dataclasses import asdict, dataclass, field
from typing import Any


def emit(obj: dict[str, Any]) -> None:
    """Write one NDJSON event to stdout and flush."""
    sys.stdout.write(json.dumps(obj, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def emit_dc(dc: object) -> None:
    """Write a dataclass-shaped event to stdout."""
    emit({k: v for k, v in asdict(dc).items() if v is not None})


@dataclass
class Ready:
    session_id: str
    sdk_version: str | None = None
    type: str = "ready"


@dataclass
class AssistantMessage:
    text: str
    usage: dict[str, int] | None = None
    type: str = "assistant_message"


@dataclass
class Thinking:
    text: str
    type: str = "thinking"


@dataclass
class ToolUse:
    id: str
    tool: str
    input: dict[str, Any]
    type: str = "tool_use"


@dataclass
class ToolResult:
    id: str
    is_error: bool = False
    output: str = ""
    type: str = "tool_result"


@dataclass
class HookRequest:
    id: str
    event: str
    tool: str
    input: dict[str, Any]
    type: str = "hook_request"


@dataclass
class SubagentStart:
    agent: str
    parent_id: str | None = None
    type: str = "subagent_start"


@dataclass
class SubagentEnd:
    agent: str
    summary: str | None = None
    type: str = "subagent_end"


@dataclass
class Cost:
    usd: float
    tokens: dict[str, int] | None = None
    type: str = "cost"


@dataclass
class Final:
    status: str  # success | failure | cancelled
    summary: str | None = None
    artifacts: dict[str, str] = field(default_factory=dict)
    type: str = "final"


@dataclass
class Error:
    message: str
    code: str | None = None
    type: str = "error"
