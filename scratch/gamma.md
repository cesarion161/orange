# Gamma — Observability & Failure Modes

Building on the layer model in [base.md](base.md), this file covers how Orange surfaces failure.

Each agent-run subprocess emits structured NDJSON events (`error`, `final`) that the orchestrator parses; stderr is tee'd to Spring logs, giving a dual stream of structured telemetry and human-readable traces.

When a runner exits non-zero without emitting `final`, the orchestrator marks the task `failed` and retains the stored prompt so the run can be replayed at a later attempt number.

Persistent failures are visible in the dashboard (JTE + htmx) and in Grafana via the LGTM stack brought up by `docker compose`, enabling both real-time alerting and post-mortem trace analysis.
