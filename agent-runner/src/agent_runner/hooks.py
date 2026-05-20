"""Hooks installed on the Claude Agent SDK client.

The worktree guard is the load-bearing one: it refuses any tool call whose
target path escapes the configured worktree root. The orchestrator can
additionally opt into round-tripping certain hook events back to itself
(see `Start.options.hooks_via_runner`); those are wired by `runner.py`.
"""

from __future__ import annotations

import os
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Any

HookFn = Callable[[dict[str, Any], str | None, Any], Awaitable[dict[str, Any]]]


def _path_inside(child: Path, parent: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def worktree_guard(worktree: Path) -> HookFn:
    """Block tool calls that would write outside the worktree."""

    write_tools = {"Write", "Edit", "NotebookEdit"}
    bash_tools = {"Bash"}

    async def hook(input_data: dict[str, Any], tool_use_id: str | None, ctx: Any) -> dict[str, Any]:
        tool = input_data.get("tool_name", "")
        params = input_data.get("tool_input", {}) or {}

        if tool in write_tools:
            target = params.get("file_path") or params.get("path")
            if target and not _path_inside(Path(target), worktree):
                return _block(f"{tool}: target {target!r} is outside worktree {worktree}")

        if tool in bash_tools:
            cwd = params.get("cwd")
            if cwd and not _path_inside(Path(cwd), worktree):
                return _block(f"Bash: cwd {cwd!r} is outside worktree {worktree}")

        return {}

    return hook


def _block(reason: str) -> dict[str, Any]:
    return {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }


def chdir_guard(worktree: Path) -> None:
    """Set cwd; the OS then enforces the boundary for all tools that respect cwd."""
    os.chdir(worktree)
