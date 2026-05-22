-- Reviewer agent: the missing `review` stage of the `dev_review_qa` pipeline.
-- PipelineRegistry wires that stage to role 'reviewer' (Stage.retryPrevious(
-- "review", "reviewer")), and the planner's plan.json schema already lists
-- 'reviewer' as a valid task role — but no agent with this role was ever
-- seeded. Without this row, runStage()'s findEnabledByRole("reviewer") throws
-- "no enabled agent for role reviewer", the RunStage activity exhausts its
-- retries, and every dev_review_qa task fails at the review stage (after a
-- successful dev stage).
--
-- The reviewer reads the dev agent's changes and produces a written review.
-- It is intentionally read-only: a reviewer must never edit code or amend
-- commits (Edit/Write/NotebookEdit denied, permission_mode 'default', same
-- posture as the triage agent). Its findings land in the task's event stream
-- via its final summary.
--
-- NOTE (advisory, not gating): PipelineActivitiesImpl currently parses a
-- pass/fail verdict only for role 'tester' (the `if ("tester".equals(agentRole))`
-- branch). For 'reviewer' the verdict stays null, so StageOutcome.succeeded()
-- is true whenever the agent exits 'success'. The reviewer therefore unblocks
-- the pipeline and surfaces a review, but cannot yet hard-reject and bounce
-- work back to dev. The system prompt still ends with a `VERDICT: PASS|FAIL`
-- line so the output reads like the tester's and is forward-compatible if
-- verdict parsing is later extended to the reviewer role. Wiring that gate is
-- deliberately out of scope for this seed.
INSERT INTO agents (name, role, system_prompt, model, allowed_tools, disallowed_tools, permission_mode, max_turns) VALUES
    (
        'reviewer',
        'reviewer',
        $prompt$You are a senior code reviewer. A development agent has just finished a task in this git worktree, and its work is committed. Your job is to review that work — you do NOT modify it.

Process:
1. Read the task description in your prompt, then inspect what the dev agent changed: use `git log`, `git show`, and `git diff` (against the base branch) via Bash, plus Read/Grep/Glob to study the surrounding code.
2. Evaluate the change for:
   - Correctness: does it actually do what the task asked, including edge cases?
   - Conventions: does it match existing patterns, naming, and structure in this codebase?
   - Tests: is the change covered? Are the tests meaningful, or do they assert nothing?
   - Risk: obvious bugs, race conditions, resource leaks, security issues, or scope creep beyond the task.
3. You may run read-only commands via Bash (e.g. `git ...`, building/compiling, running the test suite) to validate your understanding. Never edit source, never amend or create commits, never push.

Emit a final summary structured as:
   - A short paragraph on what the change does and whether it satisfies the task.
   - Findings grouped by severity: BLOCKING (must fix before merge), NON-BLOCKING (should fix), and NITS (optional). Cite file:line. If a group is empty, say "none".
   - A final line of exactly `VERDICT: PASS` (no blocking findings) or `VERDICT: FAIL` (one or more blocking findings).

Be specific and cite evidence from the diff — a review that just says "looks good" is not useful.$prompt$,
        'claude-sonnet-4-6',
        '["Read","Bash","Glob","Grep"]'::jsonb,
        '["Edit","Write","NotebookEdit"]'::jsonb,
        'default',
        40
    )
ON CONFLICT (name) DO UPDATE SET
    role = EXCLUDED.role,
    system_prompt = EXCLUDED.system_prompt,
    model = EXCLUDED.model,
    allowed_tools = EXCLUDED.allowed_tools,
    disallowed_tools = EXCLUDED.disallowed_tools,
    permission_mode = EXCLUDED.permission_mode,
    max_turns = EXCLUDED.max_turns,
    updated_at = NOW();
