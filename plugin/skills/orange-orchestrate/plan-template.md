# Plan template

The planner run produces both artifacts in this shape. Use it when you edit a plan before dispatching it inline with `orange_dispatch(plan=…)`. A plan creates nothing until it is dispatched.

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
      "role": "implementer",
      "priority": 100,
      "overrides": { "requires_auth": false }
    },
    {
      "key": "auth-ui",
      "title": "Login + signup React forms",
      "description": "Form components, client-side validation, calls into auth-api. No protected pages yet.",
      "role": "implementer",
      "priority": 100
    },
    {
      "key": "todo-api",
      "title": "TODO CRUD endpoints",
      "description": "Behind auth (requires_auth=true). REST style. Pagination + filtering.",
      "role": "implementer",
      "priority": 100,
      "overrides": { "requires_auth": true }
    },
    {
      "key": "readme",
      "title": "Document the API in README",
      "description": "Endpoints, auth flow, local setup.",
      "role": "chore",
      "priority": 200
    }
  ],
  "edges": [
    { "from": "auth-api", "to": "auth-ui" },
    { "from": "auth-api", "to": "todo-api" },
    { "from": "todo-api", "to": "readme" }
  ]
}
```

`role` is `implementer` for real work and `chore` for low-risk housekeeping, which routes to a smaller, cheaper model.

## Picking `overrides`

- `requires_auth: true` — task touches surfaces that need a real session. Sequence auth-introducing tasks BEFORE these.

Unknown override keys are preserved on the task row but ignored — safe to add new advisory flags for future use.

## DAG-sizing rules

- Cap at ~7 tasks for a single planner run. More than that → ask the user which slice to expand and re-plan.
- Each task should be one focused agent session (~30-90 min of work) and one reviewable change. If a task feels bigger, split it; if smaller, fold it into a neighbor.
- Edges express *requires*: `from` produces output that `to` consumes. A task starts only after every parent is integrated. Don't add edges for soft ordering preferences — those go in `priority`.
- Acyclic. Dispatch rejects a cycle; better to spot it in the draft.
