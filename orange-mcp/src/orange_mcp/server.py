"""MCP tool surface: each tool is a thin shim over the orange-ai REST API.

The orange-ai orchestrator runs as a Spring Boot app (default
http://localhost:8080). This server is launched as a subprocess by Claude
Code via MCP stdio transport, exposes a handful of tools the model can call
to plan/create/monitor/cancel tasks, and translates each call to an HTTP
request.

Planner and dispatcher are independent steps: orange_plan produces a plan DAG
(creating nothing), and orange_dispatch runs a plan — from inline JSON or a
saved planId. Review (or skip review) between the two.

The coordinator runs every agent itself; these tools let a chat session plan,
dispatch, watch and steer that work. (orange v2 replaces this shim with an MCP
endpoint served by the coordinator.)

Configuration via env vars:
  ORANGE_BASE_URL          base URL of the orange-ai REST API (default localhost:8080)
"""

from __future__ import annotations

import json
import os
import sys
from typing import Any

import httpx
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

BASE_URL = os.environ.get("ORANGE_BASE_URL", "http://localhost:8080").rstrip("/")

server: Server = Server("orange-mcp")


# ──────────────────────── tool definitions ─────────────────────────────


def _tools() -> list[Tool]:
    return [
        Tool(
            name="orange_plan",
            description=(
                "Run the orange-ai planner agent on a free-text feature description. "
                "The planner writes an architecture.md design doc and a plan.json adjacency "
                "list of tasks, and returns that DAG (tasks + edges) for review. "
                "PLAN-ONLY: it creates no tasks and dispatches nothing. To run the plan, "
                "review the returned DAG, then call orange_dispatch with the returned planId "
                "(or the plan inline). Use this when the user asks to BUILD or IMPLEMENT a "
                "feature at the multi-task level."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "description": {
                        "type": "string",
                        "description": "Free-text feature request to plan.",
                    },
                },
                "required": ["description"],
            },
        ),
        Tool(
            name="orange_dispatch",
            description=(
                "Run a plan: ingest a plan DAG and start implementation. The plan can come "
                "from anywhere — supply EITHER an inline `plan` (e.g. one re-imported or "
                "edited after review) OR a `plan_id` returned by orange_plan. "
                "orange-ai validates the DAG (acyclic), "
                "inserts the tasks, and marks dependency-free tasks ready so agents pick them "
                "up. Returns the created tasks with their statuses. This is the half that "
                "actually starts work — call it only when the plan is approved."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "plan_id": {
                        "type": "string",
                        "description": "Dispatch a plan the planner already saved, by its planId.",
                    },
                    "plan": {
                        "type": "object",
                        "description": (
                            "Inline plan.json to dispatch: "
                            "{tasks: [{key,title,description,role,priority,overrides?}], "
                            "edges: [{from,to}]}. Provide this OR plan_id, not both."
                        ),
                    },
                    "architecture_md": {
                        "type": "string",
                        "description": "Optional architecture markdown, persisted alongside an inline plan.",
                    },
                },
            },
        ),
        Tool(
            name="orange_create_task",
            description=(
                "Create a single task on the orange-ai orchestrator. Use this for small, "
                "well-scoped work where the planner is overkill. The task is picked up by "
                "an agent automatically."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "description": {"type": "string"},
                    "role": {
                        "type": "string",
                        "description": "Task role: implementer (default) or chore.",
                    },
                    "priority": {"type": "integer"},
                },
                "required": ["title"],
            },
        ),
        Tool(
            name="orange_list_tasks",
            description=(
                "List recent tasks. Useful to see what's in flight, what's stuck, "
                "what was recently completed. Filter by status if you want a focused view."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "description": (
                            "Optional status filter. One of: pending, ready, in_progress, "
                            "pr_open, dev_ready, in_test, test_done, failed, cancelled."
                        ),
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max rows. Default 20, clamped to 200.",
                    },
                },
            },
        ),
        Tool(
            name="orange_task",
            description=(
                "Get the full state of a specific task — current status, priority, "
                "PR url if any. Returns the task row alone, not events."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string", "description": "Task UUID."}
                },
                "required": ["task_id"],
            },
        ),
        Tool(
            name="orange_tail",
            description=(
                "Tail recent task_events from a task's stream — assistant messages, "
                "tool calls, hook decisions, etc. Useful for narrating what an agent "
                "is doing right now. Returns oldest-first, after the optional `since` "
                "event id watermark."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                    "since": {
                        "type": "integer",
                        "description": "Return events with id > since. Default 0.",
                    },
                    "limit": {"type": "integer"},
                },
                "required": ["task_id"],
            },
        ),
        Tool(
            name="orange_cancel",
            description=(
                "Cancel a task: kills its running Claude Code process, if any, and marks the "
                "task as cancelled. Its worktree is swept later by the worktree disposer."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                },
                "required": ["task_id"],
            },
        ),
        # ───────────────── live status ─────────────────────
        Tool(
            name="orange_status_live",
            description=(
                "Aggregate 'what's happening right now' view: every in_progress task plus its "
                "last few events. Use this to narrate parallel agent activity in chat without "
                "fanning out to per-task orange_tail calls. Filter by role."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "role": {
                        "type": "string",
                        "description": "Filter to tasks with this role (implementer or chore). Omit for all roles.",
                    },
                    "per_task_limit": {
                        "type": "integer",
                        "description": "How many recent events to return per task. Default 3, clamped to 50.",
                    },
                },
            },
        ),
        Tool(
            name="orange_health",
            description=(
                "One-shot system health summary: queue depths by task status, env occupancy "
                "(total/leased/free) and spend. "
                "Print this as a single status line when the user wants a 'how's it going' check."
            ),
            inputSchema={"type": "object", "properties": {}},
        ),
        Tool(
            name="orange_envs",
            description=(
                "List dev environments with current occupancy — who holds each env, ports, "
                "status (free/leased/maintenance). Dev envs are dormant until UI QA runs."
            ),
            inputSchema={"type": "object", "properties": {}},
        ),
        Tool(
            name="orange_replay_task",
            description=(
                "Create a fresh copy of an existing task on the ready queue. Same "
                "title/description/role; metadata.replayed_from points back. "
                "Original task is left untouched. Useful during testing for re-running a "
                "flaky or otherwise interesting prior execution."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                    "actor": {"type": "string"},
                },
                "required": ["task_id"],
            },
        ),
        Tool(
            name="orange_reopen_task",
            description=(
                "Reopen a failed task: move it back to READY with a reason attached as "
                "metadata, so the next attempt can see it. Use sparingly and only after "
                "inspecting the failure context."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                    "reason": {
                        "type": "string",
                        "description": "Why you're reopening — included verbatim in the next dev attempt's prompt.",
                    },
                    "actor": {
                        "type": "string",
                        "description": "Identifier for the caller. Defaults to 'system' if omitted.",
                    },
                },
                "required": ["task_id", "reason"],
            },
        ),
    ]


@server.list_tools()
async def list_tools() -> list[Tool]:
    return _tools()


# ─────────────────────────── dispatch ───────────────────────────────────


@server.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    try:
        if name == "orange_plan":
            return await _plan(arguments)
        if name == "orange_dispatch":
            return await _dispatch(arguments)
        if name == "orange_create_task":
            return await _create_task(arguments)
        if name == "orange_list_tasks":
            return await _list_tasks(arguments)
        if name == "orange_task":
            return await _task(arguments)
        if name == "orange_tail":
            return await _tail(arguments)
        if name == "orange_cancel":
            return await _cancel(arguments)
        if name == "orange_status_live":
            return await _status_live(arguments)
        if name == "orange_envs":
            return await _envs(arguments)
        if name == "orange_health":
            return await _health(arguments)
        if name == "orange_reopen_task":
            return await _reopen_task(arguments)
        if name == "orange_replay_task":
            return await _replay_task(arguments)
        return _err(f"unknown tool: {name}")
    except httpx.ConnectError as e:
        return _err(
            f"could not reach orange-ai at {BASE_URL}: {e}. "
            "Is `./gradlew bootRun` running in orange/?"
        )
    except httpx.HTTPStatusError as e:
        body = e.response.text or ""
        return _err(f"orange-ai returned {e.response.status_code}: {body[:500]}")
    except Exception as e:  # noqa: BLE001 — surface anything to the model
        return _err(f"orange-mcp internal error: {e!r}")


# ─────────────────────────── tool impls ─────────────────────────────────


async def _plan(args: dict[str, Any]) -> list[TextContent]:
    description = args["description"]
    body = {"description": description}
    async with httpx.AsyncClient(timeout=httpx.Timeout(600.0)) as client:
        r = await client.post(f"{BASE_URL}/plans", json=body)
        if r.status_code == 422:
            payload = r.json()
            status = payload.get("status", "invalid_plan")
            return _err(f"planner did not produce a usable plan (status={status})")
        r.raise_for_status()
        payload = r.json()
    return _ok(_summarise_plan(payload), payload)


async def _dispatch(args: dict[str, Any]) -> list[TextContent]:
    plan_id = (args.get("plan_id") or "").strip() or None
    plan = args.get("plan")
    if (plan_id is None) == (plan is None):
        return _err("provide exactly one of 'plan_id' or 'plan'")
    body: dict[str, Any] = {}
    if plan_id:
        body["planId"] = plan_id
    else:
        body["plan"] = plan
        body["architectureMd"] = args.get("architecture_md")
    async with httpx.AsyncClient(timeout=60.0) as client:
        r = await client.post(f"{BASE_URL}/dispatch", json=body)
        if r.status_code in (404, 422):
            payload = r.json()
            return _err(f"dispatch rejected: {payload.get('reason', payload.get('status', 'unknown'))}")
        r.raise_for_status()
        payload = r.json()
    return _ok(_summarise_dispatch(payload), payload)


async def _create_task(args: dict[str, Any]) -> list[TextContent]:
    body = {k: v for k, v in args.items() if v is not None}
    body.setdefault("role", "implementer")
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(f"{BASE_URL}/tasks", json=body)
        r.raise_for_status()
        payload = r.json()
    return _ok(
        f"Created task {payload['id']}: '{payload['title']}' "
        f"(role={payload['role']}, status={payload['status']}).",
        payload,
    )


async def _list_tasks(args: dict[str, Any]) -> list[TextContent]:
    params: dict[str, Any] = {}
    if args.get("status"):
        params["status"] = args["status"]
    if args.get("limit"):
        params["limit"] = args["limit"]
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.get(f"{BASE_URL}/tasks", params=params)
        r.raise_for_status()
        rows = r.json()
    lines = [f"{len(rows)} task(s):"]
    for row in rows:
        lines.append(
            f"  {row['id'][:8]}…  {row['status']:13}  {row['role']:8}  "
            f"{row['title']}"
        )
    return _ok("\n".join(lines), rows)


async def _task(args: dict[str, Any]) -> list[TextContent]:
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.get(f"{BASE_URL}/tasks/{args['task_id']}")
        r.raise_for_status()
        row = r.json()
    return _ok(
        f"task {row['id']}\n"
        f"  title:    {row['title']}\n"
        f"  status:   {row['status']}\n"
        f"  role:     {row['role']}\n"
        f"  priority: {row['priority']}\n"
        f"  created:  {row['createdAt']}",
        row,
    )


async def _tail(args: dict[str, Any]) -> list[TextContent]:
    params: dict[str, Any] = {"since": args.get("since", 0)}
    if args.get("limit"):
        params["limit"] = args["limit"]
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.get(f"{BASE_URL}/tasks/{args['task_id']}/events", params=params)
        r.raise_for_status()
        rows = r.json()
    if not rows:
        return _ok("no new events", rows)
    lines = [f"{len(rows)} event(s):"]
    for row in rows:
        # Keep the payload preview short — full payload is in the structured part.
        payload_preview = json.dumps(row["payload"])
        if len(payload_preview) > 200:
            payload_preview = payload_preview[:200] + "…"
        lines.append(
            f"  [{row['id']}] {row['createdAt']}  {row['eventType']:18} "
            f"by {row['actor']}: {payload_preview}"
        )
    return _ok("\n".join(lines), rows)


async def _cancel(args: dict[str, Any]) -> list[TextContent]:
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(f"{BASE_URL}/tasks/{args['task_id']}/cancel")
        if r.status_code == 409:
            return _err(f"task {args['task_id']} is already in a terminal state")
        if r.status_code == 404:
            return _err(f"task {args['task_id']} not found")
        r.raise_for_status()
        row = r.json()
    return _ok(f"cancelled task {row['id']} (was {row.get('status')})", row)


# ─── live status ────────────────────────────────────────────────────────


async def _status_live(args: dict[str, Any]) -> list[TextContent]:
    params: dict[str, Any] = {}
    if args.get("role"):
        params["role"] = args["role"]
    if args.get("per_task_limit"):
        params["perTaskLimit"] = args["per_task_limit"]
    async with httpx.AsyncClient(timeout=15.0) as client:
        r = await client.get(f"{BASE_URL}/events/live", params=params)
        r.raise_for_status()
        rows = r.json()
    if not rows:
        return _ok("no in-flight tasks", rows)
    lines = [f"{len(rows)} active task(s):"]
    for row in rows:
        last = row["recentEvents"][0] if row["recentEvents"] else None
        last_desc = (
            f"{last['eventType']}: {json.dumps(last['payload'])[:120]}" if last else "(no events yet)"
        )
        lines.append(
            f"  {row['taskId'][:8]}…  {row['role']:8}  "
            f"{row['title'][:40]:40}  | last: {last_desc}"
        )
    return _ok("\n".join(lines), rows)


async def _replay_task(args: dict[str, Any]) -> list[TextContent]:
    task_id = args["task_id"]
    body = {"actor": args.get("actor")}
    async with httpx.AsyncClient(timeout=15.0) as client:
        r = await client.post(f"{BASE_URL}/tasks/{task_id}/replay", json=body)
        if r.status_code == 404:
            return _err(f"task {task_id} not found")
        r.raise_for_status()
        payload = r.json()
    return _ok(
        f"replayed {task_id} → new task {payload['id']} (status={payload['status']})",
        payload,
    )


async def _reopen_task(args: dict[str, Any]) -> list[TextContent]:
    task_id = args["task_id"]
    body = {"reason": args.get("reason"), "actor": args.get("actor")}
    async with httpx.AsyncClient(timeout=15.0) as client:
        r = await client.post(f"{BASE_URL}/tasks/{task_id}/reopen", json=body)
        if r.status_code == 404:
            return _err(f"task {task_id} not found")
        if r.status_code == 409:
            payload = r.json()
            return _err(
                f"task {task_id} not reopenable (status={payload.get('status')}): "
                f"{payload.get('reason')}"
            )
        r.raise_for_status()
        payload = r.json()
    return _ok(f"reopened {payload['id']} → {payload['status']}", payload)


async def _health(_args: dict[str, Any]) -> list[TextContent]:
    async with httpx.AsyncClient(timeout=10.0) as client:
        r = await client.get(f"{BASE_URL}/health/aggregate")
        r.raise_for_status()
        payload = r.json()
    q = payload.get("queues", {})
    e = payload.get("envs", {})
    c = payload.get("cost", {})
    line = (
        f"queues: ready={q.get('ready', 0)} in_progress={q.get('in_progress', 0)} "
        f"pr_open={q.get('pr_open', 0)} dev_ready={q.get('dev_ready', 0)}  |  "
        f"envs: {e.get('leased', 0)}/{e.get('total', 0)} leased  |  "
        f"spend: ${c.get('last_24h_usd', 0)}/24h (total ${c.get('total_usd', 0)})"
    )
    return _ok(line, payload)


async def _envs(_args: dict[str, Any]) -> list[TextContent]:
    async with httpx.AsyncClient(timeout=10.0) as client:
        r = await client.get(f"{BASE_URL}/dev-envs")
        r.raise_for_status()
        rows = r.json()
    if not rows:
        return _ok("no envs seeded", rows)
    lines = [f"{len(rows)} env(s):"]
    for row in rows:
        ports = json.dumps(row.get("ports") or {})
        holder = row.get("leasedBy") or "-"
        lines.append(
            f"  {row['name']:12}  {row['status']:10}  ports={ports:30}  holder={holder}"
        )
    return _ok("\n".join(lines), rows)


# ─────────────────────────── helpers ────────────────────────────────────


def _ok(human: str, structured: Any) -> list[TextContent]:
    # MCP TextContent serves the model both as a chat-shaped readable summary
    # and as a structured JSON appendix it can parse if it wants details.
    return [
        TextContent(type="text", text=human),
        TextContent(type="text", text="```json\n" + json.dumps(structured, indent=2) + "\n```"),
    ]


def _err(msg: str) -> list[TextContent]:
    return [TextContent(type="text", text=f"ERROR: {msg}")]


def _summarise_plan(payload: dict[str, Any]) -> str:
    """Render the reviewable DAG returned by /plans (plan-only — no tasks yet)."""
    status = payload.get("status", "?")
    if status != "success":
        return f"planner finished with status={status} — see structured output for details"
    plan = payload.get("plan") or {}
    tasks = plan.get("tasks", []) or []
    edges = plan.get("edges", []) or []
    lines = [f"plan ready: {len(tasks)} task(s), {len(edges)} edge(s) — NOT dispatched yet"]
    for t in tasks:
        # `or` (not get-default) so an explicit null in the JSON still renders sanely.
        key = t.get("key") or "?"
        role = t.get("role") or "dev"
        title = t.get("title") or ""
        lines.append(f"  {key:14}  role={role:11}  {title}")
    if edges:
        lines.append("  edges: " + ", ".join(f"{e.get('from', '?')}→{e.get('to', '?')}" for e in edges))
    lines.append(f"\nplanId:    {payload.get('planId')}")
    lines.append(f"outputDir: {payload.get('outputDir')}")
    lines.append(f"\nReview, then run it: orange_dispatch(plan_id=\"{payload.get('planId')}\")")
    return "\n".join(lines)


def _summarise_dispatch(payload: dict[str, Any]) -> str:
    """Render the tasks created by /dispatch (implementation has started)."""
    status = payload.get("status", "?")
    tasks = payload.get("tasks", []) or []
    if status != "success":
        return f"dispatch finished with status={status} — see structured output for details"
    lines = [f"dispatched: {len(tasks)} task(s) created (planId {payload.get('planId')})"]
    for t in tasks:
        lines.append(
            f"  {t['status']:8}  role={t['role']:11}  {t['title']}"
        )
    return "\n".join(lines)


# ─────────────────────────── entrypoint ─────────────────────────────────


async def _run() -> None:
    async with stdio_server() as (read_stream, write_stream):
        await server.run(
            read_stream,
            write_stream,
            server.create_initialization_options(),
        )


def main() -> None:
    import asyncio

    try:
        asyncio.run(_run())
    except KeyboardInterrupt:
        sys.exit(0)
