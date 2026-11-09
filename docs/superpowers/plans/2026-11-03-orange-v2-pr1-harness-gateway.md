# orange v2, PR 1: Claude Code adapter, LiteLLM gateway, routing — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Python agent-runner with a direct Claude Code harness adapter. Runs are routed by role, difficulty, risk and failures to a model, an effort and a credential (Max subscription by default, LiteLLM virtual key for gateway tiers). All of this stays inside the existing Temporal pipeline, so the base branch keeps working.

**Architecture:**
- **New packages:**
  - `routing`: a pure rule table.
  - `harness`: the adapter interface, the Claude Code command builder, the stream-json parser and process supervision.
  - `gateway`: a LiteLLM admin client.
  - `billing`: prices, cost estimate, plan-limit detection.
- **Glue:** a new `AgentRunService` ties the packages together.
- **Callers switched:** `PipelineActivitiesImpl.runStage` and `PlannerService.plan` move from `AgentRunnerLauncher` to `AgentRunService`.
- **Removed:** the Python runner and its protocol.
- **Schema:** no change. Run metadata goes in the existing `agent_runs.metadata` JSONB.

**Tech Stack:** Java 27 (fallback 26), Spring Boot 4.0.x, Gradle 9.4.1, jOOQ, Postgres 18 (Testcontainers), Claude Code 2.1.316 CLI, LiteLLM v1.106.6 (Docker).

**Spec:** `docs/superpowers/specs/2026-11-02-orange-factory-v2-design.md` (§1, §3, §4, §8, §9, §10, §14, §16, §17, §19 item 1).

## Global Constraints

- Java toolchain: latest GA, JDK 27 (`languageVersion = 27`) via the foojay resolver. If Lombok, Byte Buddy or Gradle can't build and test on 27, use 26 and say why in the PR body.
- PR base branch: `orange-e2e-base`. Merge with `gh pr merge --merge`. Work in a git worktree.
- Model IDs, verbatim: `claude-fable-5-1`, `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`.
- Effort values, verbatim (Claude Code `--effort`): `low`, `medium`, `high`, `xhigh`, `max`.
- **Subscription tokens.** `CLAUDE_CODE_OAUTH_TOKEN` is passed only into the environment of the `claude` child process. It never goes to LiteLLM, logs, `task_events`, `agent_runs`, exception messages or `toString()`.
- **Child environments.** A child process gets an environment built from an allowlist only. It never inherits the coordinator's environment. It never receives `ANTHROPIC_API_KEY`, `ORANGE_GITHUB_TOKEN`, `GITHUB_WEBHOOK_SECRET` or `LITELLM_MASTER_KEY`.
- **No API spend.** `orange.billing.overflow-to-gateway: false`. Default tiers use `auth: subscription`. Live tests make no provider calls on an API key.
- LiteLLM image: `ghcr.io/berriai/litellm:v1.106.6@sha256:37e2dafb088a7723beb998590682957386dfc83ffea1848486f8e0e5cfac0b06`.
- **Prices** (USD per million tokens; list prices as of 2026-10, used for estimates). Cache write is 1.25 × input.

  | Model | Input | Output | Cache read |
  |---|---|---|---|
  | `claude-fable-5-1` | 10.00 | 50.00 | 0.25 |
  | `claude-opus-5-5` | 4.00 | 20.00 | 0.20 |
  | `claude-sonnet-5-5` | 2.00 | 10.00 | 0.20 |
  | `claude-haiku-5-5` | 0.10 | 0.50 | 0.01 |

- **Default routing** (spec §10). Tiers:

  | Tier | Model | Budget (USD) | Auth |
  |---|---|---|---|
  | `large` | opus-5-5 | 8 | subscription |
  | `medium` | sonnet-5-5 | 4 | subscription |
  | `small` | haiku-5-5 | 1 | subscription |

  Rules, first match wins:

  | Role | Difficulty | Tier | Effort |
  |---|---|---|---|
  | planner | any | large | xhigh |
  | reviewer | any | large | high |
  | ci_fixer | any | medium | medium |
  | chore | any | small | low |
  | implementer | L | large | high |
  | implementer | M | medium | high |
  | implementer | S | medium | medium |
  | tester | any | medium | medium |
  | triage | any | medium | medium |

  `tester` and `triage` exist only until PR 2.

  Other settings:
  - Risk: min tier `large`, min effort `high`, paths `**/auth/**`, `**/migrations/**`, `**/db/migration/**`, `**/payments/**`, `infra/**`, `.github/workflows/**`.
  - Escalation: tier up after 2 failures; `needs-human-after-failures: 3`. The latter is unused until PR 6.
  - Small model: `claude-haiku-5-5`.
- No Flyway migration in this PR.

## Review Focus

1. **Killing a run must kill its whole process tree.** Claude Code's Bash children must die on timeout or budget kill, or orphaned `cargo` and `npm` processes pile up. Pinned in Task 5.
2. **Secrets must never leak** into `task_events`, `agent_runs.metadata`, logs or exception text. That covers the OAuth token and the virtual key, including on failure paths. Pinned in Tasks 4, 6 and 8.
3. **Unknown or malformed stream-json lines** must not crash the run. Newer Claude Code versions add event types, and stray non-JSON stdout happens. They become `Unparsed` events. Pinned in Task 3.
4. **A subscription tier with no token configured** must fail the run immediately with reason `subscription_token_missing`, not hang on a login prompt. Pinned in Task 8.
5. **A gateway tier with LiteLLM down, or a run that crashes after minting a key,** must fail fast with a clear reason. The key must still be blocked (in a `finally`). Pinned in Task 8.

---

### Task 0: Worktree and docs

**Files:**
- Create: `docs/superpowers/specs/2026-11-02-orange-factory-v2-design.md` (copied from the session scratchpad)
- Create: `docs/superpowers/plans/2026-11-03-orange-v2-pr1-harness-gateway.md` (this file)

- [ ] **Step 1:** Create a worktree on branch `feat/v2-pr1-harness-gateway` from `origin/orange-e2e-base`, using the superpowers:using-git-worktrees skill.
- [ ] **Step 2:** Copy the spec and this plan into `docs/superpowers/`.
- [ ] **Step 3: Commit.** `git commit -m "docs: orange v2 design spec and PR 1 plan"`

### Task 1: Toolchain (JDK 27), jOOQ codegen on Apple Silicon, dependency cleanup

**Files:**
- Modify: `orange/settings.gradle.kts`: add `plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }`.
- Modify: `orange/build.gradle.kts`:
  - `languageVersion = JavaLanguageVersion.of(27)`;
  - buildscript `embedded-postgres-binaries-bom` 18.0.0 → 18.6.0;
  - remove `spring-ai-starter-model-anthropic`, the `spring-ai-bom` import and the `springAiVersion` extra;
  - Spring Boot plugin 4.0.6 → 4.0.9;
  - add `tasks.test { useJUnitPlatform { excludeTags("live") } }` and a `liveTest` task (type `Test`, same classpath, `includeTags("live")`).
- Modify: `orange/src/main/resources/application.yaml`: remove `spring.ai`.

- [ ] **Step 1: Baseline.** On the unchanged branch, run `cd orange && ./gradlew test -x generateJooq --continue`. Record the passing and failing test counts in the task notes; these are the pre-existing failures.
- [ ] **Step 2:** Bump the embedded Postgres binaries BOM to 18.6.0. Run `./gradlew generateJooq --console=plain`.
  - Expected: `BUILD SUCCESSFUL` (the 18.6.0 darwin package is a universal binary; 18.0.0's "arm64" package contains x86_64 binaries).
  - If initdb fails with `could not create shared memory segment`, add `.setServerConfig("shared_memory_type", "mmap")` and `.setServerConfig("dynamic_shared_memory_type", "posix")` to `codegenPg`, then rerun.
- [ ] **Step 3:** Apply the toolchain change (foojay plugin, JDK 27), the Boot 4.0.9 bump and the Spring AI removal. Run `./gradlew compileJava compileTestJava`.
  - Expected: `BUILD SUCCESSFUL`.
  - If Lombok fails on JDK 27, set `extra["lombok.version"] = "1.18.48"` and retry.
- [ ] **Step 4:** Run `./gradlew test --continue`.
  - Expected: no new failures against the Step 1 baseline.
  - If Mockito or Byte Buddy errors mention class file version 71, set `extra["byte-buddy.version"] = "1.18.17-jdk5"` and retry.
  - If it still fails, set `languageVersion` to 26, keep the other changes, and note the reason.
- [ ] **Step 5: Commit.** `git commit -m "build: JDK 27 toolchain via foojay, native arm64 embedded Postgres for jOOQ codegen, drop unused Spring AI"`

### Task 2: Routing table

**Files:**
- Create: `orange/src/main/java/com/ai/orange/routing/Effort.java`
- Create: `orange/src/main/java/com/ai/orange/routing/AuthMode.java`
- Create: `orange/src/main/java/com/ai/orange/routing/RoutingProperties.java`
- Create: `orange/src/main/java/com/ai/orange/routing/RouteRequest.java`
- Create: `orange/src/main/java/com/ai/orange/routing/RouteDecision.java`
- Create: `orange/src/main/java/com/ai/orange/routing/RoutingTable.java`
- Modify: `orange/src/main/resources/application.yaml`: add `orange.routing` with the Global Constraints defaults.
- Test: `orange/src/test/java/com/ai/orange/routing/RoutingTableTest.java` (plain JUnit; build `RoutingProperties` in code)

**Interfaces:**
- Produces:
  - `enum Effort { LOW, MEDIUM, HIGH, XHIGH, MAX; String cli(); Effort raise(); static Effort parse(String) }`.
    - `cli()` is lower-case.
    - `raise()`: MAX stays MAX.
    - `parse` accepts lower-case values and throws `IllegalArgumentException` on anything else.
  - `enum AuthMode { SUBSCRIPTION, GATEWAY }` (config values `subscription` and `gateway`).
  - `@ConfigurationProperties("orange.routing") record RoutingProperties(List<String> tierOrder, Map<String, TierSpec> tiers, List<Rule> rules, Risk risk, Escalation escalation, String smallModel)`. Nested records:
    - `TierSpec(String model, String harness, AuthMode auth, BigDecimal budgetUsd)`
    - `Rule(String role, String difficulty /* null = any */, String tier, Effort effort)`
    - `Risk(String minTier, Effort minEffort, List<String> paths)`
    - `Escalation(int tierUpAfterFailures, int needsHumanAfterFailures)`
    - `tierOrder` defaults to `[small, medium, large]`.
  - `record RouteRequest(String role, String difficulty, boolean risk, int failures)`
  - `record RouteDecision(String tier, String model, Effort effort, String harness, AuthMode auth, BigDecimal budgetUsd)`
  - `@Component class RoutingTable`:
    - `RouteDecision route(RouteRequest r)`;
    - `boolean isRiskPath(String repoRelativePath)`;
    - the constructor validates the config: every rule's tier exists, and `tierOrder` covers all tiers. Otherwise it throws `IllegalStateException`.

- [ ] **Step 1: Write failing tests** in `RoutingTableTest`:
  - `implementerByDifficulty`: `route(new RouteRequest("implementer","S",false,0))` → tier `medium`, model `claude-sonnet-5-5`, effort MEDIUM. `"L"` → `large`, `claude-opus-5-5`, HIGH.
  - `firstMatchWins_andAnyDifficulty`: `route(new RouteRequest("reviewer","S",false,0))` → `large`, HIGH. `planner` → XHIGH.
  - `riskFloorsTierAndEffort`: `route(new RouteRequest("chore","S",true,0))` → `large`, HIGH.
  - `escalatesTierAfterTwoFailures`: `("implementer","S",false,2)` → `large`, MEDIUM.
  - `escalatesEffortAtTopTier`: `("implementer","L",false,2)` → `large`, XHIGH; `("planner","L",false,2)` → MAX.
  - `unknownRoleThrows`: `route(new RouteRequest("astronaut","M",false,0))` throws `IllegalArgumentException` with a message containing `astronaut`.
  - `riskPaths`: `isRiskPath("svc/auth/Login.java")` is true; `isRiskPath(".github/workflows/ci.yml")` is true; `isRiskPath("src/main/App.java")` is false.
  - `badConfigFailsFast`: a rule with tier `huge` makes the constructor throw `IllegalStateException`.
  - `effortRaiseAndParse`: `Effort.MAX.raise()` is MAX; `Effort.parse("xhigh")` is XHIGH; `Effort.parse("HIGHEST")` throws.
- [ ] **Step 2: Run them and see them fail.** `./gradlew test --tests com.ai.orange.routing.RoutingTableTest`. Expected: compilation failure.
- [ ] **Step 3: Implement.**
  - Order of operations in `route`: matching rule → risk floor (max of the tiers by `tierOrder` index, max of the efforts by ordinal) → escalation (if `failures >= tierUpAfterFailures`, move up one tier in `tierOrder`; if already at the top, `effort.raise()`).
  - `isRiskPath` uses `FileSystems.getDefault().getPathMatcher("glob:" + p)` against `Path.of(path)`.
  - Add the `application.yaml` defaults.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(routing): role x difficulty x risk routing table with escalation"`

### Task 3: Harness event types and stream-json parser

**Files:**
- Create: `orange/src/main/java/com/ai/orange/harness/RunEvent.java`
- Create: `orange/src/main/java/com/ai/orange/harness/claude/StreamJsonParser.java`
- Test: `orange/src/test/java/com/ai/orange/harness/claude/StreamJsonParserTest.java`
- Test fixture: `orange/src/test/resources/harness/claude-stream-sample.jsonl` (realistic lines: a `system`/`init`; an `assistant` with text and `tool_use` blocks and `usage`; a `user` with a `tool_result` that has `is_error`; a final `result` with `total_cost_usd`, `usage`, `num_turns` and `session_id`)

**Interfaces:**
- Produces `sealed interface RunEvent`, with records:
  - `Init(String sessionId, String model)`
  - `AssistantText(String text)`
  - `ToolUse(String id, String name, JsonNode input)`
  - `ToolResult(String toolUseId, boolean isError, String excerpt /* ≤ 2000 chars */)`
  - `Usage(String model, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens)`, one per assistant message
  - `Result(boolean isError, String subtype, String text, BigDecimal costUsd, long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens, int numTurns, String sessionId)`
  - `Stderr(String line)`
  - `Unparsed(String line)`
- Also produces `final class StreamJsonParser { List<RunEvent> parse(String line) }`. It is stateless, never throws, and returns an empty list for blank lines.

- [ ] **Step 1: Write failing tests:**
  - `parsesSampleInOrder`: parsing every fixture line yields, in order, `Init(sessionId="s-1", model="claude-sonnet-5-5")`, `AssistantText("Reading files")`, `ToolUse(name="Read")`, `Usage(inputTokens=1200, outputTokens=80, cacheReadTokens=900)`, `ToolResult(isError=true)`, and `Result(isError=false, costUsd=new BigDecimal("0.0123"), numTurns=3, sessionId="s-1")`.
  - `unknownTypeBecomesUnparsed`: `parse("{\"type\":\"rate_limit_event\"}")` → a single `Unparsed`.
  - `garbageBecomesUnparsed`: `parse("not json {")` → a single `Unparsed`.
  - `blankIsEmpty`: `parse("   ")` → an empty list.
  - `toolResultExcerptIsTruncated`: a 10,000-character tool result gives an excerpt of 2000 characters.
- [ ] **Step 2:** Run `./gradlew test --tests '*StreamJsonParserTest'`. Expected: FAIL (compilation).
- [ ] **Step 3: Implement** with Jackson `ObjectMapper.readTree`. The `type` field selects the mapping:
  - `system`+`init` → `Init`
  - `assistant` → one event per content block, plus one `Usage` from `message.usage` (`input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`)
  - `user` → `ToolResult` for each `tool_result` block
  - `result` → `Result`
  - anything else → `Unparsed`
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(harness): normalized run events and Claude Code stream-json parser"`

### Task 4: Run spec, credentials, Claude Code command builder

**Files:**
- Create: `orange/src/main/java/com/ai/orange/harness/Credentials.java`
- Create: `orange/src/main/java/com/ai/orange/harness/RunSpec.java`
- Create: `orange/src/main/java/com/ai/orange/harness/HarnessProperties.java`
- Create: `orange/src/main/java/com/ai/orange/harness/claude/ClaudeCodeCommand.java`
- Modify: `orange/src/main/resources/application.yaml`. Add:

  ```yaml
  orange:
    harness:
      run-root: ${ORANGE_RUN_ROOT:${user.home}/.orange/runs}
      claude-code:
        command: [claude]
        pass-env: [PATH, LANG, LC_ALL, TERM, TMPDIR, HOME, USER]
  ```

  `run-root` is outside every git worktree.
- Test: `orange/src/test/java/com/ai/orange/harness/claude/ClaudeCodeCommandTest.java`

**Interfaces:**
- Produces:
  - `sealed interface Credentials` with `record Subscription(String oauthToken)` and `record Gateway(String baseUrl, String virtualKey)`. Both override `toString()` to `Subscription[***]` and `Gateway[baseUrl=…, key=***]`.
  - `record RunSpec(UUID runId, String prompt, String appendSystemPrompt, String model, String smallModel, Effort effort, Path cwd, List<String> tools /* null = default set, empty = none */, List<String> allowedTools, List<String> disallowedTools, String permissionMode, Path mcpConfig /* nullable */, Credentials credentials, Duration timeout)`
  - `@ConfigurationProperties("orange.harness") record HarnessProperties(Path runRoot, ClaudeCode claudeCode)`, with `record ClaudeCode(List<String> command, List<String> passEnv)`.
  - `final class ClaudeCodeCommand`:
    - `static List<String> argv(RunSpec s, List<String> commandPrefix)`
    - `static Map<String,String> env(RunSpec s, Map<String,String> hostEnv, List<String> passEnv, Path claudeConfigDir)`

- [ ] **Step 1: Write failing tests:**
  - `argvHasRequiredFlags`. The argv starts with the prefix, then contains, in this order:

    ```
    -p <prompt> --output-format stream-json --verbose
      --model <model> --effort <cli> --session-id <runId>
      --setting-sources project,local --strict-mcp-config
    ```

    It contains `--append-system-prompt <text>` when the text is non-blank, `--allowedTools` followed by the comma-joined list when it isn't empty, `--disallowedTools` likewise, `--permission-mode <mode>`, and `--mcp-config <path>` when it's set.
  - `noToolsMeansEmptyToolsFlag`: `tools = List.of()` → argv contains `--tools` followed by `""` (an empty string element); `tools = null` → no `--tools`.
  - `envIsAllowlistOnly`:
    - The host environment has `PATH=/bin`, `ORANGE_GITHUB_TOKEN=x`, `LITELLM_MASTER_KEY=y`, `ANTHROPIC_API_KEY=z`.
    - The result has `PATH`, has none of the other three, and has `CLAUDE_CONFIG_DIR` = the given dir, `ANTHROPIC_DEFAULT_HAIKU_MODEL` = the small model, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`, `DISABLE_AUTOUPDATER=1`, `GIT_AUTHOR_NAME=orange[bot]` and `GIT_COMMITTER_NAME=orange[bot]`.
  - `subscriptionEnv`: `Credentials.Subscription("tok")` → `CLAUDE_CODE_OAUTH_TOKEN=tok`, and no `ANTHROPIC_BASE_URL` or `ANTHROPIC_AUTH_TOKEN`.
  - `gatewayEnv`: `Credentials.Gateway("http://localhost:4000","sk-1")` → `ANTHROPIC_BASE_URL=http://localhost:4000`, `ANTHROPIC_AUTH_TOKEN=sk-1`, `ENABLE_TOOL_SEARCH=true`, and no `CLAUDE_CODE_OAUTH_TOKEN`.
  - `credentialsToStringRedacts`: neither `toString()` contains `tok` or `sk-1`.
- [ ] **Step 2:** Run `./gradlew test --tests '*ClaudeCodeCommandTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** both static methods. Git author and committer email are `orange-bot@users.noreply.github.com`.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(harness): Claude Code argv/env builder with env allowlist and credential modes"`

### Task 5: Process supervision, adapter and fake Claude Code

**Files:**
- Create: `orange/src/main/java/com/ai/orange/harness/RunExit.java`
- Create: `orange/src/main/java/com/ai/orange/harness/RunHandle.java`
- Create: `orange/src/main/java/com/ai/orange/harness/HarnessAdapter.java`
- Create: `orange/src/main/java/com/ai/orange/harness/HarnessRegistry.java`
- Create: `orange/src/main/java/com/ai/orange/harness/ProcessRunHandle.java`
- Create: `orange/src/main/java/com/ai/orange/harness/claude/ClaudeCodeAdapter.java`
- Create: `orange/src/test/resources/fake-claude/FakeClaude.java`. This is a Java single-file program with no package and JDK classes only.
- Test: `orange/src/test/java/com/ai/orange/harness/claude/ClaudeCodeAdapterIT.java` (no Spring context; construct the adapter directly)
- Test helper: `orange/src/test/java/com/ai/orange/harness/FakeClaude.java` (test util). `static List<String> command()` returns `[<current java binary>, <absolute path of fake-claude/FakeClaude.java copied to a temp dir>]`.

**Interfaces:**
- Consumes: `RunSpec`, `Credentials`, `ClaudeCodeCommand`, `StreamJsonParser`, `RunEvent` (Tasks 3 and 4).
- Produces:
  - `record RunExit(int exitCode, String killReason /* null unless killed: "timeout" | "budget" | "cancelled" */, RunEvent.Result result /* null if none */)`
  - `interface RunHandle { CompletableFuture<RunExit> exit(); void kill(String reason); boolean isAlive(); }`
  - `interface HarnessAdapter { String id(); RunHandle start(RunSpec spec, Consumer<RunEvent> sink) throws IOException; }`
  - `@Component class HarnessRegistry { HarnessAdapter get(String id) }` (throws `IllegalArgumentException` for an unknown id)
  - `ClaudeCodeAdapter`:
    - `id()` is `"claude-code"`.
    - Per run it creates `runRoot/<runId>/claude-config`.
    - It appends raw stdout to `runRoot/<runId>/stream.jsonl` and stderr to `runRoot/<runId>/stderr.log`.
- **Fake contract.** `FakeClaude` reads the `-p` prompt and the `--append-system-prompt` text and picks a scenario from a marker in either:
  - no marker: writes `FAKE_OUTPUT.md` in cwd, runs `git add -A && git commit -m fake` if cwd is a git repo, then prints init, assistant, user and result lines (success, cost `0.01`)
  - `[[fake:error]]`: result `is_error=true`, exit 1
  - `[[fake:hang]]`: starts a child `sleep 600`, then sleeps
  - `[[fake:expensive]]`: an assistant usage line of 5,000,000 input tokens on the given `--model`, then hangs
  - `[[fake:plan-limit]]`: result `is_error=true` with text `Claude usage limit reached. Your limit will reset at 5pm (Europe/Berlin).`, exit 1
  - `[[fake:tester-pass]]` / `[[fake:tester-fail]]`: writes `report.md` whose first line is `VERDICT: PASS` / `VERDICT: FAIL`
  - `[[fake:review-fail]]`: the final assistant text ends with `VERDICT: FAIL`
  - `[[fake:plan]]`: writes a valid `plan.json` (2 tasks, 1 edge) and an `architecture.md`

  In every scenario it writes `fake-args.json` and `fake-env.json` into `$CLAUDE_CONFIG_DIR`.

- [ ] **Step 1: Write failing tests:**
  - `successStreamsEventsAndExitsZero`: in a temp git repo, a prompt with no marker → the sink receives `Init`, `AssistantText` and `Result` in order; `exit().get(30s)` has exitCode 0, killReason null and `result.costUsd()` 0.01; `FAKE_OUTPUT.md` is committed.
  - `childEnvIsIsolated`: after a successful run, `runRoot/<runId>/claude-config/fake-env.json` has no `ORANGE_GITHUB_TOKEN` key even though the test JVM sets it (use `HarnessProperties.passEnv` without it and set it through a host-env override in the adapter's test constructor), and `CLAUDE_CONFIG_DIR` points inside `runRoot`.
  - `killTerminatesProcessTree`: `[[fake:hang]]`, then `kill("timeout")` → `exit()` completes within 10 s with killReason `timeout`, and the fake's `sleep 600` child PID (written by the fake to `claude-config/child.pid`) is no longer alive (`ProcessHandle.of(pid).map(ProcessHandle::isAlive)` is false or empty).
  - `errorScenarioReportsErrorResult`: `[[fake:error]]` → exitCode 1, and `result.isError()` is true.
  - `streamIsTeedToDisk`: `runRoot/<runId>/stream.jsonl` holds as many lines as stdout printed.
- [ ] **Step 2:** Run `./gradlew test --tests '*ClaudeCodeAdapterIT'`. Expected: FAIL.
- [ ] **Step 3: Implement `ProcessRunHandle`.**
  - It has two virtual-thread readers: stdout lines go to the parser, then the sink, then `stream.jsonl`; stderr lines go to `Stderr` events and `stderr.log`.
  - `exit()` completes after process exit and both readers finish.
  - `result` is the last `RunEvent.Result` seen.
  - `kill(reason)` records the reason, then calls `destroyForcibly()` on `process.descendants()` before the process itself.
  - Sink exceptions are caught and logged, never propagated.

  The `ClaudeCodeAdapter` constructor takes `HarnessProperties` plus a `Supplier<Map<String,String>> hostEnv` (production: `System::getenv`). `start` builds the `ProcessBuilder` with `environment().clear()`, then `putAll(ClaudeCodeCommand.env(...))`, and sets the working directory to `spec.cwd()`.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(harness): Claude Code adapter with process-tree kill and a fake claude for tests"`

### Task 6: LiteLLM gateway client, compose service, config

**Files:**
- Create: `orange/src/main/java/com/ai/orange/gateway/GatewayProperties.java`
- Create: `orange/src/main/java/com/ai/orange/gateway/VirtualKey.java`
- Create: `orange/src/main/java/com/ai/orange/gateway/KeyInfo.java`
- Create: `orange/src/main/java/com/ai/orange/gateway/GatewayClient.java`
- Create: `orange/litellm/config.yaml`. It has a `model_list` entry for each of the four model IDs (`litellm_params.model: anthropic/<id>`, `api_key: os.environ/ANTHROPIC_API_KEY`) with `model_info` per-token prices from Global Constraints (`input_cost_per_token`, `output_cost_per_token`, `cache_read_input_token_cost`, `cache_creation_input_token_cost`). It also sets `general_settings.master_key: os.environ/LITELLM_MASTER_KEY` and `general_settings.database_url: os.environ/DATABASE_URL`.
- Create: `orange/.docker/postgres-init/02-litellm-db.sql`: `CREATE DATABASE litellm;`
- Create: `orange/.env.example`. It names every key (`CLAUDE_CODE_OAUTH_TOKEN`, `LITELLM_MASTER_KEY`, `ANTHROPIC_API_KEY` (optional, gateway tiers only), `GITHUB_WEBHOOK_SECRET`, `ORANGE_GITHUB_TOKEN`, `ORANGE_GITHUB_REPO`, `ORANGE_GITHUB_BASE_BRANCH`, `WORKTREES_BASE_REPO`) with one-line comments and no values.
- Modify: `orange/compose.yaml`. Add a `litellm` service with:
  - the pinned image;
  - `command: ["--config", "/app/config.yaml", "--port", "4000"]`;
  - volume `./litellm/config.yaml:/app/config.yaml:ro`;
  - env `LITELLM_MASTER_KEY`, `ANTHROPIC_API_KEY`, and `DATABASE_URL=postgresql://orange:secret@postgres:5432/litellm`;
  - port `4000:4000`;
  - `depends_on: postgres (service_healthy)`.
- Modify: `orange/src/main/resources/application.yaml`. Add `orange.gateway.base-url: ${LITELLM_BASE_URL:http://localhost:4000}`, `master-key: ${LITELLM_MASTER_KEY:}` and `key-ttl-margin: 30m`.
- Test: `orange/src/test/java/com/ai/orange/gateway/GatewayClientTest.java` (`MockRestServiceServer` bound to the `RestClient.Builder`)
- Test: `orange/src/test/java/com/ai/orange/gateway/LiteLlmGatewayLiveIT.java`. It is `@Tag("live")` and `@EnabledIfEnvironmentVariable(named="LITELLM_MASTER_KEY", matches=".+")`, and runs against compose on `localhost:4000`.

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("orange.gateway") record GatewayProperties(URI baseUrl, String masterKey, Duration keyTtlMargin)`
  - `record VirtualKey(String alias, String key, Instant expiresAt)`, with `toString()` redacting `key`
  - `record KeyInfo(BigDecimal spend, boolean blocked)`
  - `@Component class GatewayClient`:
    - `VirtualKey mint(String alias, List<String> models, BigDecimal maxBudgetUsd, Duration ttl, Map<String,Object> metadata)`
    - `void block(String key)`
    - `KeyInfo info(String key)`
    - `void delete(String key)`
    - `boolean healthy()`
    - Every admin call sends `Authorization: Bearer <masterKey>`. If `masterKey` is blank, it throws `IllegalStateException("gateway master key not configured")` before any HTTP call.
- LiteLLM wire formats:
  - `POST /key/generate` body: `{key_alias, models, max_budget, duration, metadata}`, where `duration` is whole minutes, e.g. `"95m"`. The response has `key` and `expires`.
  - `POST /key/block`: `{key}`.
  - `GET /key/info?key=…`: the response has `info.spend` and `info.blocked`.
  - `POST /key/delete`: `{keys:[key]}`.
  - `GET /health/liveliness` → 200 means healthy.

- [ ] **Step 1: Write failing tests** in `GatewayClientTest`:
  - `mintSendsBudgetModelsAndDuration`: the expected request matches path `/key/generate`, the bearer header, and JSON body fields `key_alias=run-abc`, `models=["claude-sonnet-5-5","claude-haiku-5-5"]`, `max_budget=4`, `duration="95m"` and `metadata.run_id`. The response `{"key":"sk-x","expires":"2026-11-03T12:00:00Z"}` yields `VirtualKey("run-abc","sk-x",…)`, and `toString()` lacks `sk-x`.
  - `infoParsesSpend`: `{"info":{"spend":1.25,"blocked":true}}` → `KeyInfo(1.25, true)`.
  - `blockAndDeleteHitEndpoints`.
  - `missingMasterKeyFailsBeforeHttp`.
  - `healthyFalseOnConnectionError`.
- [ ] **Step 2:** Run `./gradlew test --tests '*GatewayClientTest'`. Expected: FAIL.
- [ ] **Step 3: Implement** with Spring `RestClient`, plus the compose, config and env-example files.
- [ ] **Step 4:** Run the tests. Expected: PASS. Then validate compose: run `docker compose config -q` in `orange/`. Expected: exit 0.
- [ ] **Step 5:** Write the live IT: mint → info (spend 0, not blocked) → block → info (blocked) → delete. It makes no model calls.
- [ ] **Step 6: Commit.** `git commit -m "feat(gateway): LiteLLM admin client, pinned compose service, model prices"`

### Task 7: Billing: price table, cost estimate, plan-limit detection

**Files:**
- Create: `orange/src/main/java/com/ai/orange/billing/BillingProperties.java`
- Create: `orange/src/main/java/com/ai/orange/billing/CostEstimator.java`
- Create: `orange/src/main/java/com/ai/orange/billing/PlanLimit.java`
- Modify: `orange/src/main/resources/application.yaml`. Add `orange.billing.subscription-token: ${CLAUDE_CODE_OAUTH_TOKEN:}`, `overflow-to-gateway: false`, and `prices` per model (per MTok: `input`, `output`, `cache-read`, `cache-write`) from Global Constraints.
- Test: `orange/src/test/java/com/ai/orange/billing/CostEstimatorTest.java`
- Test: `orange/src/test/java/com/ai/orange/billing/PlanLimitTest.java`

**Interfaces:**
- Produces:
  - `@ConfigurationProperties("orange.billing") record BillingProperties(String subscriptionToken, boolean overflowToGateway, Map<String, Price> prices)`, with `record Price(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite)`. Its `toString()` redacts the token.
  - `@Component class CostEstimator { BigDecimal estimate(String model, long in, long out, long cacheRead, long cacheWrite) }`. An unknown model returns `BigDecimal.ZERO` and logs a warning once per model.
  - `final class PlanLimit { static boolean matches(String text) }`. It is case-insensitive and true for text containing `usage limit reached` or `limit will reset`.

- [ ] **Step 1: Write failing tests:**
  - `estimateUsesPerMillionPrices`: `estimate("claude-sonnet-5-5", 1_000_000, 100_000, 2_000_000, 0)` equals `2.00 + 1.00 + 0.40 = 3.40` (scale-insensitive `compareTo`).
  - `unknownModelIsZero`.
  - `planLimitMatches`: the fake's text matches; `"rate limited, retrying"` doesn't.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(billing): price table, cost estimate, plan-limit detection"`

### Task 8: AgentRunService (route → credentials → adapter → events → finish)

**Files:**
- Create: `orange/src/main/java/com/ai/orange/agent/AgentRunService.java`
- Create: `orange/src/main/java/com/ai/orange/agent/AgentRunRequest.java`
- Create: `orange/src/main/java/com/ai/orange/agent/AgentRunResult.java`
- Modify: `orange/src/main/java/com/ai/orange/agent/AgentRunRepository.java`: add `int countFailed(UUID taskId)`.
- Test: `orange/src/test/java/com/ai/orange/agent/AgentRunServiceIT.java` (Spring Boot + Testcontainers; fake claude through `orange.harness.claude-code.command`; a `@MockitoBean GatewayClient`)

**Interfaces:**
- Consumes: `RoutingTable.route`, `HarnessRegistry.get`, `GatewayClient.mint/block/info`, `CostEstimator.estimate`, `PlanLimit.matches`, `BillingProperties`, `GatewayProperties`, `AgentRunRepository.start/finish`, `TaskEventRepository.append`, `TaskRepository.updateHeartbeat`.
- Produces:
  - `record AgentRunRequest(UUID taskId /* nullable */, String role, Agent agent, String prompt, Path cwd, String difficulty, boolean risk, Duration timeout, String actor)`
  - `record AgentRunResult(String status /* "success" | "failure" | "cancelled" */, String failureReason /* nullable: subscription_token_missing | gateway_unavailable | timeout | budget | plan_limit | error_result | no_result | exit_<code> */, String lastAssistantText, RunEvent.Result result, BigDecimal costUsd, UUID agentRunId)`
  - `@Service class AgentRunService { AgentRunResult run(AgentRunRequest req) }`
- **Behaviour (pinned):**
  - **Routing role.** The role is `"implementer"` when `req.role()` is `"dev"`, otherwise `req.role()`.
  - **Failures.** `failures = taskId == null ? 0 : countFailed(taskId)`.
  - **Run identity.**
    - With a task: an `agent_runs` row is started (`attempt = nextAttempt(taskId)`) and `runId` is its id.
    - Without a task (the planner): `agent_runs.task_id` is NOT NULL, so no row is written, and `runId = UUID.randomUUID()`.
    - `smallModel` comes from `RoutingProperties.smallModel()`.
  - **Heartbeat.** While waiting for exit, update the task heartbeat every 30 s as well as on each event. `HeartbeatReaper` reaps after 5 minutes of silence, and a long `cargo build` emits no events.
  - **Gateway spend.** The spend-sync job and key deletion arrive in PR 2 along with the `runs` table. In this PR the key is only blocked when the run ends.
  - **Subscription auth with a blank token.** No process is spawned. The result is `failure` / `subscription_token_missing`, and an `agent_runs` row is recorded as FAILED.
  - **Gateway auth.**
    - If `!gateway.healthy()`, the result is `failure` / `gateway_unavailable`.
    - Otherwise mint with alias `run-<agentRunId>`, models `[decision.model, smallModel]`, the decision's budget, and ttl `timeout + keyTtlMargin`.
    - `block(key)` always runs, in a `finally`.
  - **`agent_runs.metadata` at start.** `{tier, model, effort, auth, harness, gateway_key_alias?}`. It never contains a key or token.
  - **Events → `task_events`** (when `taskId != null`), with actor `req.actor()`:

    | Event | `event_type` | Payload |
    |---|---|---|
    | `Init` | `ready` | `{session_id, model}` |
    | `AssistantText` | `assistant_message` | `{text}` |
    | `ToolUse` | `tool_use` | `{id, tool, input}` |
    | `ToolResult` | `tool_result` | `{id, is_error, excerpt}` |
    | `Result` | `final` | `{is_error, subtype, cost_usd, num_turns}` |
    | `Stderr` | not persisted | only logged at debug |

    The task heartbeat is updated on every event.
  - **Budget watch (subscription runs).** Sum the `Usage` events with `CostEstimator`. When the sum is greater than `decision.budgetUsd()`, `kill("budget")`.
  - **Timeout.** Wait up to `req.timeout()`, then `kill("timeout")`.
  - **Outcome:**
    - `success` only when exit 0, a `Result` was seen and `!isError`;
    - `plan_limit` when the result text or the last assistant text matches `PlanLimit`;
    - otherwise the kill reason, `error_result`, `no_result` or `exit_<code>`.
  - **Cost.** Gateway: `gateway.info(key).spend()`. Subscription: `result.costUsd()` if present, else the estimate.
  - **Finish.** `agentRuns.finish(id, status, cost, inputTokens, outputTokens)`.
  - **Effort.** `RunSpec.effort` = `decision.effort()`.
  - **Tools and permissions.** `allowedTools`, `disallowedTools` and `permissionMode` come from the `Agent` row. `appendSystemPrompt` = `agent.systemPrompt()`. `tools = null`.

- [ ] **Step 1: Write failing tests** (each creates a task through `TaskService.createGraph`; seeded agents come from migrations):
  - `successPersistsEventsAndCost`: with the subscription token set, the fake's success scenario → status `success`; `task_events` include `ready`, `assistant_message` and `final`; `agent_runs.cost_usd` is 0.01; metadata `model` is `claude-sonnet-5-5` and `effort` is `high` (implementer M).
  - `missingSubscriptionTokenFailsFast`: token blank → `failure`/`subscription_token_missing`, and the fake was never started (no `runRoot/<id>` directory).
  - `budgetKill`: `[[fake:expensive]]` → `failure`/`budget` within 30 s.
  - `timeoutKill`: `[[fake:hang]]` with a 3 s timeout → `failure`/`timeout`.
  - `planLimitDetected`: `[[fake:plan-limit]]` → `failure`/`plan_limit`.
  - `gatewayTierMintsAndAlwaysBlocks`: with routing overridden so that `medium.auth = gateway` and the mocked `mint` returning `VirtualKey("run-x","sk-secret",…)`, the `[[fake:error]]` scenario → `verify(gateway).block("sk-secret")`. Neither `task_events` nor `agent_runs.metadata` contains `sk-secret`, and `fake-env.json` has `ANTHROPIC_AUTH_TOKEN=sk-secret`.
  - `gatewayDownFailsFast`: `healthy()` returns false → `failure`/`gateway_unavailable`, and `mint` is never called.
- [ ] **Step 2:** Run `./gradlew test --tests '*AgentRunServiceIT'`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(agent): AgentRunService — routed, credentialed, budget-watched Claude Code runs"`

### Task 9: Switch the pipeline and planner to AgentRunService; delete the Python runner

**Files:**
- Modify: `orange/src/main/java/com/ai/orange/workflow/PipelineActivitiesImpl.java`.
  - `runStage` calls `agentRunService.run(new AgentRunRequest(taskId, agentRole, agent, prompt, Path.of(worktreePath), difficultyFrom(task), riskFrom(task), Duration.ofMinutes(60), agent.name()))`.
  - `difficultyFrom` and `riskFrom` read `tasks.metadata.difficulty` (default `"M"`) and `tasks.metadata.risk` (default false).
  - It keeps the tester `report.md` verdict and reviewer `VERDICT` parsing, with `lastAssistantText` replacing the old `lastAssistant`.
  - It maps `AgentRunResult.status` to `StageOutcome.agentStatus`.
- Modify: `orange/src/main/java/com/ai/orange/planner/PlannerService.java`. `plan()` uses `agentRunService.run(...)` with `taskId=null`, `role="planner"`, `cwd=outputDir` and `timeout=props.runTimeout()`. It reads `plan.json` and `architecture.md` from `outputDir`.
- Delete:
  - `orange/src/main/java/com/ai/orange/agent/AgentRunnerLauncher.java`, `AgentRunnerProcess.java`, `AgentRunnerProperties.java`
  - `orange/src/main/java/com/ai/orange/agent/protocol/` (all three files)
  - `orange/src/test/java/com/ai/orange/agent/AgentRunnerProcessIT.java`
  - `orange/src/test/resources/stub_runner.py`, `stub_runner_simple.py`, `stub_tester.py`, `stub_planner.py`
  - top-level `agent-runner/` and `protocol/`
- Modify: `orange/src/main/resources/application.yaml`: remove `orange.agent-runner`.
- Modify the tests `PipelineActivitiesIT`, `PipelineRetryIT` and `PlannerServiceIT`.
  - They set `orange.harness.claude-code.command` to `FakeClaude.command()` and `orange.billing.subscription-token` to `test-token` through `@DynamicPropertySource`.
  - Their stub-script selection is replaced by fake markers in the agent rows' `system_prompt`. For example, PipelineRetryIT updates the `tester` agent's prompt to contain `[[fake:tester-fail]]` for the first attempt, then `[[fake:tester-pass]]`.
  - Assertions are otherwise unchanged.
- Modify `CLAUDE.md`:
  - Repository layout: remove `agent-runner/` and `protocol/`; describe the `harness`, `gateway`, `routing` and `billing` packages and the fake claude.
  - Commands: `./gradlew liveTest` and `docker compose up -d litellm`.
  - Secrets: `CLAUDE_CODE_OAUTH_TOKEN` comes from `claude setup-token`.
  - Replace the "Hooks"/"protocol" sections with a "Running Claude Code" section listing the env allowlist and the auth modes.

- [ ] **Step 1:** Update the three ITs to the fake (they now fail to compile against the deleted classes, which is the failing state).
- [ ] **Step 2: Implement** the `runStage` and `PlannerService` changes and the deletions.
- [ ] **Step 3:** Run `./gradlew test --continue`. Expected: every test passes except failures recorded in the Task 1 baseline.
- [ ] **Step 4:** Run `grep -rn "agent_runner\|AgentRunnerProcess\|protocol/" --include=*.java --include=*.md --include=*.yaml .`. Expected: no matches outside `docs/`.
- [ ] **Step 5: Commit.** `git commit -m "refactor!: pipeline and planner run Claude Code directly via AgentRunService; remove Python agent-runner and protocol"`

### Task 10: Live checks (opt-in) and PR

**Files:**
- Create: `orange/src/test/java/com/ai/orange/harness/claude/SubscriptionSmokeLiveIT.java`. It is `@Tag("live")` and `@EnabledIfEnvironmentVariable(named="CLAUDE_CODE_OAUTH_TOKEN", matches=".+")`.

- [ ] **Step 1: Write the smoke test.**
  - It runs `ClaudeCodeAdapter` on the real `claude` command with model `claude-haiku-5-5`, effort `low`, `tools = List.of()` (no tools), `Credentials.Subscription(System.getenv("CLAUDE_CODE_OAUTH_TOKEN"))`, and the prompt `Reply with exactly: ORANGE_OK`.
  - Asserts: exitCode 0, `result.isError()` false, the result text contains `ORANGE_OK`, and `result.costUsd()` ≥ 0.
- [ ] **Step 2: Run the live checks** if the user has supplied the token (`! claude setup-token`, then put it in `orange/.env`).
  - Gateway: `docker compose up -d postgres litellm`, then `LITELLM_MASTER_KEY=… ./gradlew liveTest --tests '*LiteLlmGatewayLiveIT'`. Expected: PASS, with no model calls.
  - Subscription: `./gradlew liveTest --tests '*SubscriptionSmokeLiveIT'`. Expected: PASS, one tool-less Haiku call on the subscription.
- [ ] **Step 3: Full verification.** Run `./gradlew build`. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 4:** Push the branch and open a PR against `orange-e2e-base`. The body summarises the change, links the spec and plan, states the Java version outcome, lists the deleted components and the live-check results, and says "Part 1 of 9".
- [ ] **Step 5: Review.** Run the user's standard flow: a fresh reviewer subagent (BLOCKING / NON-BLOCKING / NIT, `VERDICT: PASS|FAIL`), up to 3 fix rounds, then `gh pr merge --merge`, then worktree and branch cleanup.
