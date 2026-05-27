"""MCP tool surface: each tool is a thin shim over the orange-ai REST API.

The orange-ai orchestrator runs as a Spring Boot app (default
http://localhost:8080). This server is launched as a subprocess by Claude
Code via MCP stdio transport, exposes a handful of tools the model can call
to plan/create/monitor/cancel tasks, and translates each call to an HTTP
request.

Planner and dispatcher are independent steps: orange_plan produces a plan DAG
(creating nothing), and orange_dispatch runs a plan — from inline JSON or a
saved planId. Review (or skip review) between the two.

Two flavors of tools live here:
  - "user-facing" tools (orange_plan, orange_dispatch, orange_create_task,
    orange_list_tasks, orange_task, orange_tail, orange_cancel) for
    chat-as-user contexts;
  - "chat-as-executor" tools (orange_claim_next_task, orange_heartbeat,
    orange_complete_task, orange_fail_task, orange_release_claim,
    orange_submit_plan, orange_dep_artifacts, orange_my_active_claims) for
    chat-as-worker contexts where the chat session itself drives a task
    through the FSM instead of the subprocess agent-runner.

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
                "edited after review) OR a `plan_id` returned by orange_plan / "
                "orange_submit_plan. orange-ai validates the DAG (acyclic, pipelines resolve), "
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
                            "{tasks: [{key,title,description,role,priority,pipeline?,overrides?}], "
                            "edges: [{from,to}]}. Provide this OR plan_id, not both."
                        ),
                    },
                    "architecture_md": {
                        "type": "string",
                        "description": "Optional architecture markdown, persisted alongside an inline plan.",
                    },
                    "default_pipeline": {
                        "type": "string",
                        "description": (
                            "Pipeline applied to any task without an explicit one. "
                            "One of: dev_only, dev_qa, dev_review_qa. Null → dev_only."
                        ),
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
                        "description": "Agent role (e.g. dev, tester, reviewer). Defaults to dev.",
                    },
                    "pipeline": {
                        "type": "string",
                        "description": (
                            "Pipeline name: dev_only | dev_qa | dev_review_qa. "
                            "Defaults to dev_only for one-off manual tasks."
                        ),
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
                "Get the full state of a specific task — current status, claim info, "
                "pipeline, priority, PR url if any. Returns the task row alone, not events."
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
                "Cancel a running task. Terminates its Temporal workflow, marks the task "
                "as cancelled. The agent-runner subprocess closes via its own AutoCloseable "
                "wiring; the worktree may need a manual sweep if cleanup activities were "
                "skipped by termination."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                },
                "required": ["task_id"],
            },
        ),
        # ───────────────── chat-as-executor surface ─────────────────────
        Tool(
            name="orange_claim_next_task",
            description=(
                "CHAT-AS-EXECUTOR: Atomically claim the next ready task from the orange-ai "
                "queue for the given role. Returns the task, the prompt to execute, the "
                "worktree path to cd into, parent-task ids (deps), a claim_token to use on "
                "subsequent calls, and the lease expiry. While holding the claim you MUST "
                "call orange_heartbeat every ~30s or the orchestrator will reap your lease "
                "and re-enqueue the task. Returns null if no task is available."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "role": {
                        "type": "string",
                        "description": "Agent role to claim a task for (e.g. dev, tester, planner).",
                    },
                    "worker_id": {
                        "type": "string",
                        "description": "Stable identifier for this chat session — used by the orchestrator to track lease ownership and to resume after restart.",
                    },
                },
                "required": ["role", "worker_id"],
            },
        ),
        Tool(
            name="orange_heartbeat",
            description=(
                "CHAT-AS-EXECUTOR: Refresh the lease on a claim. Returns whether the "
                "claim is still alive, whether the orchestrator has flagged it for "
                "cancellation, and the new expiry. Call every ~30s while holding a claim. "
                "If alive=false the lease was reaped; stop work and discard the token."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "claim_token": {"type": "string"},
                },
                "required": ["claim_token"],
            },
        ),
        Tool(
            name="orange_complete_task",
            description=(
                "CHAT-AS-EXECUTOR: Mark the claim's task as done. Releases the lease and "
                "persists `summary` + `artifacts` on tasks.metadata so downstream "
                "dependents can read them via orange_dep_artifacts.\n\n"
                "Status routing:\n"
                "  • `artifacts.pr_url` set → pr_open (caller opened a PR themselves)\n"
                "  • else if `open_pr=true` OR GitHub is configured (auto) → orange attempts "
                "to push + open a PR via GithubPrService. Success → pr_open + metadata stamped. "
                "Failure → falls through to dev_ready with a pr_open_skipped event.\n"
                "  • else → dev_ready (work done, no PR pending)."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "claim_token": {"type": "string"},
                    "summary": {"type": "string"},
                    "artifacts": {
                        "type": "object",
                        "description": "Arbitrary structured outputs from this run (file paths, URLs, key facts) — visible to downstream dependents. If you opened a PR yourself, include pr_url / pr_repo / pr_number here.",
                    },
                    "open_pr": {
                        "type": "boolean",
                        "description": "Force or skip the server-side auto-open-PR. Omit (default) to let orange decide based on its GitHub config.",
                    },
                },
                "required": ["claim_token"],
            },
        ),
        Tool(
            name="orange_fail_task",
            description=(
                "CHAT-AS-EXECUTOR: Mark the claim as failed. If retryable=true and the "
                "per-task attempt budget remains, the task is bounced back to ready for "
                "another worker; otherwise the task is marked failed terminally. Releases "
                "the lease."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "claim_token": {"type": "string"},
                    "reason": {"type": "string"},
                    "retryable": {"type": "boolean"},
                },
                "required": ["claim_token", "reason"],
            },
        ),
        Tool(
            name="orange_release_claim",
            description=(
                "CHAT-AS-EXECUTOR: Cooperative drop. Unlike orange_fail_task this does NOT "
                "count against the retry budget — the task goes straight back to ready. Use "
                "when the chat has to leave for reasons unrelated to the task itself."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "claim_token": {"type": "string"},
                    "reason": {"type": "string"},
                },
                "required": ["claim_token"],
            },
        ),
        Tool(
            name="orange_submit_plan",
            description=(
                "CHAT-AS-EXECUTOR (planner): Save the {plan.json, architecture.md} pair "
                "produced by an interactive planner chat and complete the planner claim. "
                "PLAN-ONLY: this validates the DAG and stores it, but creates no tasks and "
                "dispatches nothing — pipeline selection is a dispatch concern. Returns the "
                "saved plan DAG and a planId; run it later with orange_dispatch(plan_id=…).\n\n"
                "Each task in `plan.tasks[]` MAY specify per-task `pipeline` "
                "(dev_only | dev_qa | dev_review_qa) and `overrides` "
                "({skip_review, skip_test, requires_auth}); these are honored at dispatch."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "claim_token": {"type": "string"},
                    "plan": {
                        "type": "object",
                        "description": (
                            "Wire-format plan.json: "
                            "{tasks: [{key,title,description,role,priority,pipeline?,overrides?}], "
                            "edges: [{from,to}]}."
                        ),
                    },
                    "architecture_md": {
                        "type": "string",
                        "description": "Optional architecture design-doc markdown body.",
                    },
                },
                "required": ["claim_token", "plan"],
            },
        ),
        Tool(
            name="orange_dep_artifacts",
            description=(
                "CHAT-AS-EXECUTOR: Read the parent tasks' captured artifacts for a given "
                "task id. Useful for a worker to peek at upstream results before deciding "
                "how to attack its own task. Does not require a claim."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                },
                "required": ["task_id"],
            },
        ),
        Tool(
            name="orange_status_live",
            description=(
                "Aggregate 'what's happening right now' view: every in_progress task plus its "
                "last few events. Use this to narrate parallel agent activity in chat without "
                "fanning out to per-task orange_tail calls. Filter by role and/or pipeline."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "role": {
                        "type": "string",
                        "description": "Filter to tasks claimed for this role (e.g. dev, tester, planner). Omit for all roles.",
                    },
                    "pipeline": {
                        "type": "string",
                        "description": "Filter to tasks on this pipeline (e.g. dev_qa, dev_review_qa). Omit for all pipelines.",
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
                "One-shot system health summary: queue depths (ready / in_progress / pr_open / "
                "dev_ready), env occupancy (total/leased/free), per-role concurrency usage. "
                "Print this as a single status line when the user wants a 'how's it going' check."
            ),
            inputSchema={"type": "object", "properties": {}},
        ),
        Tool(
            name="orange_envs",
            description=(
                "List dev environments with current occupancy — who holds each env, ports, "
                "status (free/leased/maintenance). Used by the orchestrator skill to show env "
                "availability when planning the test stage."
            ),
            inputSchema={"type": "object", "properties": {}},
        ),
        Tool(
            name="orange_replay_task",
            description=(
                "Create a fresh copy of an existing task on the ready queue. Same "
                "title/description/role/pipeline; metadata.replayed_from points back. "
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
                "TRIAGE: reopen a FAILED or TEST_DONE task by transitioning it back to "
                "READY with a reason attached as augmentation metadata. The next dev "
                "agent that claims it will see your reason in the prompt. Use sparingly "
                "and only after inspecting the failure context — this is the triage "
                "agent's primary mutating tool."
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
        Tool(
            name="orange_concurrency",
            description=(
                "Per-role parallelism usage snapshot — for each role, how many tasks are "
                "in_progress and what the configured cap is (null = unlimited). Use when the "
                "user asks why a claim was refused or to surface bottlenecks during narration. "
                "Roles with no cap configured are still shown so the operator sees the full picture."
            ),
            inputSchema={"type": "object", "properties": {}},
        ),
        Tool(
            name="orange_my_active_claims",
            description=(
                "CHAT-AS-EXECUTOR: List the chat session's currently active claims by "
                "worker_id. Use on chat resume after restart to figure out whether to keep "
                "heartbeating an existing claim or call orange_claim_next_task."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "worker_id": {"type": "string"},
                },
                "required": ["worker_id"],
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
        if name == "orange_claim_next_task":
            return await _claim_next(arguments)
        if name == "orange_heartbeat":
            return await _heartbeat(arguments)
        if name == "orange_complete_task":
            return await _complete_task(arguments)
        if name == "orange_fail_task":
            return await _fail_task(arguments)
        if name == "orange_release_claim":
            return await _release_claim(arguments)
        if name == "orange_submit_plan":
            return await _submit_plan(arguments)
        if name == "orange_dep_artifacts":
            return await _dep_artifacts(arguments)
        if name == "orange_my_active_claims":
            return await _my_active_claims(arguments)
        if name == "orange_status_live":
            return await _status_live(arguments)
        if name == "orange_envs":
            return await _envs(arguments)
        if name == "orange_health":
            return await _health(arguments)
        if name == "orange_concurrency":
            return await _concurrency(arguments)
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
    plan_id = args.get("plan_id")
    plan = args.get("plan")
    if bool(plan_id) == bool(plan):
        return _err("provide exactly one of 'plan_id' or 'plan'")
    body: dict[str, Any] = {"defaultPipeline": args.get("default_pipeline")}
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
    body.setdefault("pipeline", "dev_only")
    body.setdefault("role", "dev")
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(f"{BASE_URL}/tasks", json=body)
        r.raise_for_status()
        payload = r.json()
    return _ok(
        f"Created task {payload['id']}: '{payload['title']}' "
        f"(role={payload['role']}, pipeline={payload['pipeline']}, status={payload['status']}).",
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
            f"{row['pipeline']:12}  {row['title']}"
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
        f"  pipeline: {row['pipeline']}\n"
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


# ─── chat-as-executor ───────────────────────────────────────────────────


async def _claim_next(args: dict[str, Any]) -> list[TextContent]:
    body = {"role": args["role"], "workerId": args["worker_id"]}
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(f"{BASE_URL}/claims/next", json=body)
        if r.status_code == 204:
            return _ok("no ready tasks for that role", None)
        r.raise_for_status()
        payload = r.json()
    return _ok(
        f"claimed task {payload['taskId']} (role={payload['role']}, "
        f"attempt={payload['attempt']}, token={payload['claimToken']}, "
        f"lease_expires={payload['leaseExpiresAt']})\n"
        f"cwd: {payload['cwd']}\n"
        f"deps: {payload['deps']}\n\n"
        f"--- prompt ---\n{payload['prompt']}",
        payload,
    )


async def _heartbeat(args: dict[str, Any]) -> list[TextContent]:
    token = args["claim_token"]
    async with httpx.AsyncClient(timeout=10.0) as client:
        r = await client.post(f"{BASE_URL}/claims/{token}/heartbeat")
        if r.status_code == 410:
            payload = r.json()
            return _ok("LEASE EXPIRED — stop work, your token is dead", payload)
        r.raise_for_status()
        payload = r.json()
    flag = " (CANCEL REQUESTED)" if payload.get("cancelRequested") else ""
    return _ok(
        f"alive: lease_expires={payload['leaseExpiresAt']}{flag}",
        payload,
    )


async def _complete_task(args: dict[str, Any]) -> list[TextContent]:
    token = args["claim_token"]
    body: dict[str, Any] = {}
    if args.get("summary") is not None:
        body["summary"] = args["summary"]
    if args.get("artifacts") is not None:
        body["artifacts"] = args["artifacts"]
    if args.get("open_pr") is not None:
        body["openPr"] = bool(args["open_pr"])
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(f"{BASE_URL}/claims/{token}/complete", json=body)
        if r.status_code == 410:
            payload = r.json()
            return _err(f"claim already gone: {payload.get('reason')}")
        r.raise_for_status()
        payload = r.json()
    return _ok(
        f"task {payload['taskId']} → {payload['newStatus']}",
        payload,
    )


async def _fail_task(args: dict[str, Any]) -> list[TextContent]:
    token = args["claim_token"]
    body = {
        "reason": args.get("reason"),
        "retryable": bool(args.get("retryable", False)),
    }
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(f"{BASE_URL}/claims/{token}/fail", json=body)
        if r.status_code == 410:
            payload = r.json()
            return _err(f"claim already gone: {payload.get('reason')}")
        r.raise_for_status()
        payload = r.json()
    return _ok(
        f"task {payload['taskId']} → {payload['newStatus']} (retryable={payload['retryable']})",
        payload,
    )


async def _release_claim(args: dict[str, Any]) -> list[TextContent]:
    token = args["claim_token"]
    body = {"reason": args.get("reason")}
    async with httpx.AsyncClient(timeout=30.0) as client:
        r = await client.post(f"{BASE_URL}/claims/{token}/release", json=body)
        if r.status_code == 410:
            payload = r.json()
            return _err(f"claim already gone: {payload.get('reason')}")
        r.raise_for_status()
        payload = r.json()
    return _ok(
        f"released task {payload['taskId']} back to {payload['newStatus']}",
        payload,
    )


async def _submit_plan(args: dict[str, Any]) -> list[TextContent]:
    token = args["claim_token"]
    body = {
        "claimToken": token,
        "plan": args["plan"],
        "architectureMd": args.get("architecture_md"),
    }
    async with httpx.AsyncClient(timeout=60.0) as client:
        r = await client.post(f"{BASE_URL}/claims/{token}/submit-plan", json=body)
        if r.status_code == 422:
            payload = r.json()
            return _err(f"plan rejected: {payload.get('reason', 'unknown')}")
        r.raise_for_status()
        payload = r.json()
    return _ok(_summarise_plan(payload), payload)


async def _dep_artifacts(args: dict[str, Any]) -> list[TextContent]:
    task_id = args["task_id"]
    async with httpx.AsyncClient(timeout=15.0) as client:
        r = await client.get(f"{BASE_URL}/tasks/{task_id}/dep-artifacts")
        r.raise_for_status()
        payload = r.json()
    if not payload:
        return _ok("no dependencies", payload)
    lines = [f"{len(payload)} parent(s):"]
    for parent_id, art in payload.items():
        title = art.get("title", "?") if isinstance(art, dict) else "?"
        status = art.get("status", "?") if isinstance(art, dict) else "?"
        lines.append(f"  {parent_id[:8]}…  {status:12}  {title}")
    return _ok("\n".join(lines), payload)


async def _status_live(args: dict[str, Any]) -> list[TextContent]:
    params: dict[str, Any] = {}
    if args.get("role"):
        params["role"] = args["role"]
    if args.get("pipeline"):
        params["pipeline"] = args["pipeline"]
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
            f"by {row['claimedBy'] or '-':14}  {row['title'][:40]:40}  | last: {last_desc}"
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


async def _concurrency(_args: dict[str, Any]) -> list[TextContent]:
    async with httpx.AsyncClient(timeout=10.0) as client:
        r = await client.get(f"{BASE_URL}/concurrency")
        r.raise_for_status()
        rows = r.json()
    lines = [f"role usage ({len(rows)} role(s)):"]
    for row in rows:
        cap = row.get("cap")
        cap_str = str(cap) if cap is not None else "∞"
        marker = " (AT CAP)" if row.get("atCap") else ""
        lines.append(f"  {row['role']:10}  {row['inProgress']:>3}/{cap_str:<3}{marker}")
    return _ok("\n".join(lines), rows)


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


async def _my_active_claims(args: dict[str, Any]) -> list[TextContent]:
    params = {"workerId": args["worker_id"]}
    async with httpx.AsyncClient(timeout=10.0) as client:
        r = await client.get(f"{BASE_URL}/claims/active", params=params)
        r.raise_for_status()
        rows = r.json()
    if not rows:
        return _ok(f"no active claims for worker {args['worker_id']}", rows)
    lines = [f"{len(rows)} active claim(s):"]
    for row in rows:
        flag = " (cancel-requested)" if row.get("cancelRequested") else ""
        lines.append(
            f"  task={row['taskId']}  token={row['claimToken']}  "
            f"attempt={row['attempt']}  expires={row['leaseExpiresAt']}{flag}"
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
        pipeline = t.get("pipeline") or "(default)"
        lines.append(
            f"  {t['key']:14}  role={t.get('role', 'dev'):8}  pipeline={pipeline:14}  {t.get('title', '')}"
        )
    if edges:
        lines.append("  edges: " + ", ".join(f"{e['from']}→{e['to']}" for e in edges))
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
            f"  {t['status']:8}  role={t['role']:8}  pipeline={t['pipeline']:10}  {t['title']}"
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
