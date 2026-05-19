# Planner draft template

When you (the chat orchestrator) are acting as the chat-as-planner, draft both artifacts in this shape before calling `orange_submit_plan`.

## architecture.md

```markdown
# <Feature short name>

## Problem
<1-3 sentences: what the user wants, why it matters>

## Approach
<1 paragraph: shape of the solution at the level a senior engineer would sketch on a whiteboard>

## Components
- <component or module>: <its responsibility>
- ...

## Data model touch-points
<which DB tables / external resources are affected>

## Risks / open questions
- <risk + mitigation>
- <open question for the user>
```

Aim for design-review quality, not exhaustive prose. The user reads this to decide whether to approve.

## plan.json

```json
{
  "tasks": [
    {
      "key": "auth-api",
      "title": "Build login + signup endpoints",
      "description": "POST /auth/login + /auth/signup with bcrypt + session cookies. Include unit tests for the password-hash flow.",
      "role": "dev",
      "priority": 100,
      "pipeline": "dev_review_qa",
      "overrides": { "requires_auth": false }
    },
    {
      "key": "auth-ui",
      "title": "Login + signup React forms",
      "description": "Form components, client-side validation, calls into auth-api. No protected pages yet.",
      "role": "dev",
      "priority": 100,
      "pipeline": "dev_qa"
    },
    {
      "key": "todo-api",
      "title": "TODO CRUD endpoints",
      "description": "Behind auth (requires_auth=true). REST style. Pagination + filtering.",
      "role": "dev",
      "priority": 100,
      "pipeline": "dev_qa",
      "overrides": { "requires_auth": true }
    },
    {
      "key": "todo-tests",
      "title": "Cross-feature smoke tests",
      "description": "Spin up the app, create user, create + list + delete TODOs. Assert auth gating.",
      "role": "tester",
      "priority": 200,
      "pipeline": "dev_only"
    }
  ],
  "edges": [
    { "from": "auth-api", "to": "auth-ui" },
    { "from": "auth-api", "to": "todo-api" },
    { "from": "auth-ui", "to": "todo-tests" },
    { "from": "todo-api", "to": "todo-tests" }
  ]
}
```

## Picking `pipeline` per task

| Task shape | Pipeline |
|---|---|
| Refactor, docs, plumbing — no behavior change | `dev_only` |
| Behavior change, needs validation | `dev_qa` |
| Security-critical, auth, money, data integrity | `dev_review_qa` |
| Tester-role tasks (verification of a previous dev task) | `dev_only` (the tester IS the verification — no need to re-test it) |

The user's overall preference (the default_pipeline you derived from follow-ups) sets the default; pick per-task only when a specific task warrants a different gate.

## Picking `overrides`

- `requires_auth: true` — task touches surfaces that need a real session. Sequence auth-introducing tasks BEFORE these.
- `skip_review: true` — even if pipeline is `dev_review_qa`, this specific task is too low-risk to block on review style notes.
- `skip_test: true` — pipeline says `dev_qa` but this task's behavior is trivially verifiable by inspection.

Unknown override keys are preserved on the task row but ignored by the executor — safe to add new advisory flags for future use.

## DAG-sizing rules

- Cap at ~7 tasks for a single planner run. More than that → ask the user which slice to expand and re-plan.
- Each task should be one focused agent session (~30-90 min of work). If a task feels bigger, split it; if smaller, fold it into a neighbor.
- Edges express *requires*: `from` produces output that `to` consumes. Don't add edges for soft ordering preferences — those go in `priority`.
- Acyclic. The submit endpoint will reject a cycle; better to spot it in the draft.
