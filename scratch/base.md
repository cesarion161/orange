# Base

Orange is a task-orchestration system with three layers: the **orchestrator** (Java/Spring Boot), the **agent-runner** (Python sidecar), and the **protocol** (NDJSON schemas) that binds them.

A **task** is the unit of work — it moves through a status FSM (`pending → ready → in_progress → …`) and is executed by an **agent run**, which is one subprocess lifetime of the agent-runner.

The orchestrator owns persistence and scheduling; the agent-runner owns Claude SDK calls and tool sandboxing; the protocol is the wire contract that neither side may change unilaterally.
