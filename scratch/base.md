# Base Overview

This repository is a multi-component AI task-orchestration system composed of three peers:
**`orange/`** (Java 26 / Spring Boot 4 orchestrator), **`agent-runner/`** (Python 3.12 Claude Agent SDK sidecar), and **`protocol/`** (shared JSON Schemas + dataclasses defining the NDJSON wire contract between them).

The orchestrator owns persistence (Postgres + Flyway + JOOQ), workflow state (Temporal), GitHub webhook ingress, and a JTE + htmx dashboard; it spawns one `agent-runner` subprocess per agent run.
The agent-runner reads `start`/`hook_response`/`cancel` commands from stdin, drives a `ClaudeSDKClient` inside an isolated git worktree, and emits structured events on stdout.

All topic files in `scratch/` build on this foundation and should be read alongside this file.
