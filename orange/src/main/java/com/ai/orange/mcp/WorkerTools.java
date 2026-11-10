package com.ai.orange.mcp;

import com.ai.orange.project.SpaceService;
import com.ai.orange.run.ReviewGap;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunOutcome;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunSecrets;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionRepository;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker MCP tools. A run sees only the tools of its kind, and every call writes the
 * outcome of the run its token belongs to and of no other. Handlers never throw: a bad call
 * comes back as an error result the agent can read and correct.
 */
public class WorkerTools {

    private static final Logger log = LoggerFactory.getLogger(WorkerTools.class);

    public static final String REPORT_RESULT = "report_result";
    public static final String REQUEST_HUMAN = "request_human";
    public static final String SUBMIT_REVIEW = "submit_review";
    public static final String JUDGE_CONTINUE = "judge_continue";
    public static final String JUDGE_VERIFY = "judge_verify";
    public static final String JUDGE_ESCALATE = "judge_escalate";
    public static final String JUDGE_IDLE = "judge_idle";
    public static final String PROPOSE_ITEM = "propose_item";
    /** The {@link McpTransportContext} key the token filter's run is stored under. */
    public static final String RUN_KEY = "run";

    private static final Map<RunKind, Set<String>> TOOLS_BY_KIND = Map.of(
            RunKind.IMPLEMENT, Set.of(REPORT_RESULT, REQUEST_HUMAN),
            RunKind.CI_FIX, Set.of(REPORT_RESULT, REQUEST_HUMAN),
            RunKind.DIFF_REVIEW, Set.of(SUBMIT_REVIEW),
            RunKind.PLAN, Set.of(REQUEST_HUMAN),
            RunKind.JUDGE, Set.of(JUDGE_CONTINUE, JUDGE_VERIFY, JUDGE_ESCALATE, JUDGE_IDLE, PROPOSE_ITEM));

    private static final String JUDGE_CONTINUE_SCHEMA = """
            {"type":"object","properties":{
              "message":{"type":"string","description":"What the session is told, as the user would write it."},
              "rationale":{"type":"string","description":"Why this is the answer; kept so the user can audit it."},
              "confidence":{"type":"number","minimum":0,"maximum":1,"description":"How sure you are that the user would give this same answer."}},
             "required":["message","rationale","confidence"]}""";
    private static final String JUDGE_VERIFY_SCHEMA = """
            {"type":"object","properties":{
              "rationale":{"type":"string","description":"What the session says it finished."}},
             "required":["rationale"]}""";
    private static final String JUDGE_ESCALATE_SCHEMA = """
            {"type":"object","properties":{
              "question":{"type":"string","description":"What the user must decide, readable without the transcript."},
              "proposed_answer":{"type":"string","description":"Your own answer, when you have one."},
              "urgency":{"type":"string","enum":["normal","urgent","critical"]},
              "rationale":{"type":"string","description":"Why you may not answer this yourself."}},
             "required":["question","urgency","rationale"]}""";
    private static final String JUDGE_IDLE_SCHEMA = """
            {"type":"object","properties":{
              "reason":{"type":"string","description":"Why nothing needs to happen."}},
             "required":["reason"]}""";
    private static final String PROPOSE_ITEM_SCHEMA = """
            {"type":"object","properties":{
              "kind":{"type":"string","enum":["decision","note"]},
              "text":{"type":"string"}},
             "required":["kind","text"]}""";

    private static final String REPORT_RESULT_SCHEMA = """
            {"type":"object","properties":{
              "status":{"type":"string","enum":["done","blocked","failed"],
                        "description":"done: the work is complete and committed. failed: you could not do it. blocked: you need a decision (prefer request_human for a question)."},
              "summary":{"type":"string","description":"What you changed, or why you could not."}},
             "required":["status","summary"]}""";
    private static final String REQUEST_HUMAN_SCHEMA = """
            {"type":"object","properties":{
              "question":{"type":"string","description":"The decision you need, with the options you see."}},
             "required":["question"]}""";
    private static final String SUBMIT_REVIEW_SCHEMA = """
            {"type":"object","properties":{
              "verdict":{"type":"string","enum":["pass","gaps"],"description":"pass: nothing blocks this change. gaps: something must be fixed first."},
              "summary":{"type":"string","description":"One paragraph on what the change does and how well it meets the intent."},
              "gaps":{"type":"array","description":"Each problem that must be fixed. Required when the verdict is gaps.",
                      "items":{"type":"object","properties":{
                        "path":{"type":"string"},"line":{"type":"integer"},"body":{"type":"string"}},
                       "required":["body"]}}},
             "required":["verdict","summary"]}""";

    private final RunRepository runs;
    private final RunSecrets secrets;
    private final McpJsonMapper jsonMapper;
    private final SessionRepository sessions;
    private final SpaceService space;

    public WorkerTools(RunRepository runs, RunSecrets secrets, McpJsonMapper jsonMapper, SessionRepository sessions,
                       SpaceService space) {
        this.sessions = sessions;
        this.space = space;
        this.runs = runs;
        this.secrets = secrets;
        this.jsonMapper = jsonMapper;
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                spec(REPORT_RESULT, "Report the result of your run: done (work complete and committed), "
                        + "failed, or blocked.", REPORT_RESULT_SCHEMA, this::reportResult),
                spec(REQUEST_HUMAN, "Ask a person for a decision you cannot make yourself. Your run ends and "
                        + "the task waits for the answer.", REQUEST_HUMAN_SCHEMA, this::requestHuman),
                spec(SUBMIT_REVIEW, "Submit your review of the diff: pass, or gaps with each problem to fix. "
                        + "Call it exactly once.", SUBMIT_REVIEW_SCHEMA, this::submitReview),
                spec(JUDGE_CONTINUE, "Answer the stopped session on the user's behalf. Only for routine questions "
                        + "whose answer follows from the project's goals, decisions and conventions.",
                        JUDGE_CONTINUE_SCHEMA, this::judgeContinue),
                spec(JUDGE_VERIFY, "The session says its work is finished and asks nothing.", JUDGE_VERIFY_SCHEMA,
                        (ctx, req) -> record(ctx, new RunOutcome.Verdict("verify", null, string(req, "rationale"), null,
                                null, null, null))),
                spec(JUDGE_ESCALATE, "The user must decide: file the question for them. The session stops.",
                        JUDGE_ESCALATE_SCHEMA, this::judgeEscalate),
                spec(JUDGE_IDLE, "The stop asks nothing and claims nothing: nothing happens.", JUDGE_IDLE_SCHEMA,
                        (ctx, req) -> record(ctx, new RunOutcome.Verdict("idle", null, string(req, "reason"), null, null,
                                null, null))),
                spec(PROPOSE_ITEM, "Propose a decision or a note for the project's space. It stays a proposal until "
                        + "the user accepts it.", PROPOSE_ITEM_SCHEMA, this::proposeItem));
    }

    /** True when {@code toolName} belongs to the kind of the run behind this request. */
    public boolean visible(McpTransportContext context, String toolName) {
        Run run = runOf(context);
        return run != null && TOOLS_BY_KIND.getOrDefault(run.kind(), Set.of()).contains(toolName);
    }

    private SyncToolSpecification spec(String name, String description, String schema,
                                       BiFunction<McpTransportContext, CallToolRequest, CallToolResult> handler) {
        Tool tool = Tool.builder(name, jsonMapper, schema).description(description).build();
        return SyncToolSpecification.builder().tool(tool).callHandler((ctx, req) -> guarded(name, ctx, req, handler)).build();
    }

    private CallToolResult guarded(String name, McpTransportContext ctx, CallToolRequest req,
                                   BiFunction<McpTransportContext, CallToolRequest, CallToolResult> handler) {
        try {
            if (!visible(ctx, name)) {
                Run run = runOf(ctx);
                return error(name + " is not available to " + (run == null ? "this caller" : run.kind().dbValue() + " runs"));
            }
            return handler.apply(ctx, req);
        } catch (RuntimeException e) {
            log.warn("worker tool {} failed: {}", name, e.toString());
            return error(name + " failed: " + e.getClass().getSimpleName());
        }
    }

    private CallToolResult reportResult(McpTransportContext ctx, CallToolRequest req) {
        String status = string(req, "status");
        String summary = string(req, "summary");
        if (!Set.of("done", "blocked", "failed").contains(status)) {
            return error("status must be done, blocked or failed");
        }
        if (summary.isBlank()) return error("summary must not be blank");
        return record(ctx, new RunOutcome.Result(status, summary, "blocked".equals(status) ? summary : null));
    }

    private CallToolResult requestHuman(McpTransportContext ctx, CallToolRequest req) {
        String question = string(req, "question");
        if (question.isBlank()) return error("question must not be blank");
        return record(ctx, new RunOutcome.Result("blocked", question, question));
    }

    private CallToolResult submitReview(McpTransportContext ctx, CallToolRequest req) {
        String verdict = string(req, "verdict");
        String summary = string(req, "summary");
        if (!Set.of("pass", "gaps").contains(verdict)) return error("verdict must be pass or gaps");
        if (summary.isBlank()) return error("summary must not be blank");
        List<ReviewGap> gaps = new ArrayList<>();
        Object raw = req.arguments() == null ? null : req.arguments().get("gaps");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> gap)) return error("each gap must be an object");
                Object body = gap.get("body");
                if (!(body instanceof String b) || b.isBlank()) return error("each gap needs a body");
                Object path = gap.get("path");
                Object line = gap.get("line");
                gaps.add(new ReviewGap(path instanceof String p && !p.isBlank() ? p : null,
                        line instanceof Number n ? n.intValue() : null, b));
            }
        } else if (raw != null) {
            return error("gaps must be an array");
        }
        if ("gaps".equals(verdict) && gaps.isEmpty()) return error("a gaps verdict needs at least one gap");
        return record(ctx, new RunOutcome.Review(verdict, summary, gaps));
    }

    private CallToolResult judgeContinue(McpTransportContext ctx, CallToolRequest req) {
        String message = string(req, "message");
        if (message.isBlank()) return error("message must not be blank");
        Object confidence = req.arguments() == null ? null : req.arguments().get("confidence");
        if (!(confidence instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 1) {
            return error("confidence must be a number from 0 to 1");
        }
        return record(ctx, new RunOutcome.Verdict("continue", message, string(req, "rationale"), n.doubleValue(), null,
                null, null));
    }

    private CallToolResult judgeEscalate(McpTransportContext ctx, CallToolRequest req) {
        String question = string(req, "question");
        String urgency = string(req, "urgency");
        if (question.isBlank()) return error("question must not be blank");
        if (!Set.of("normal", "urgent", "critical").contains(urgency)) {
            return error("urgency must be normal, urgent or critical");
        }
        String proposed = string(req, "proposed_answer");
        return record(ctx, new RunOutcome.Verdict("escalate", null, string(req, "rationale"), null, question,
                proposed.isBlank() ? null : proposed, urgency));
    }

    /** Not an outcome: the run goes on. The item is the run's own proposal, in its session's project. */
    private CallToolResult proposeItem(McpTransportContext ctx, CallToolRequest req) {
        Run run = runOf(ctx);
        String kind = string(req, "kind");
        if (!Set.of("decision", "note").contains(kind)) return error("kind must be decision or note");
        Session session = run.attendedSessionId() == null ? null : sessions.findById(run.attendedSessionId()).orElse(null);
        if (session == null || session.projectId() == null) {
            return error("this session belongs to no project; there is no space to propose to");
        }
        try {
            space.add(session.projectId(), kind, secrets.redact(run.id(), string(req, "text")),
                    SpaceService.Author.agent("run:" + run.id()), null, null, null);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        return CallToolResult.builder().addTextContent("proposed").isError(false).build();
    }

    private CallToolResult record(McpTransportContext ctx, RunOutcome outcome) {
        Run run = runOf(ctx);
        // An agent can echo its credential or token into a summary; outcomes are stored and fed into later
        // prompts. The secrets contain no JSON metacharacters, so redacting the serialised form is safe.
        JSONB redacted = JSONB.valueOf(secrets.redact(run.id(), outcome.toJsonb().data()));
        if (!runs.recordOutcome(run.id(), redacted)) {
            return error("this run is no longer running; nothing was recorded");
        }
        log.info("run {} reported {}", run.id(), outcome.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT));
        return CallToolResult.builder().addTextContent("recorded").isError(false).build();
    }

    private static Run runOf(McpTransportContext ctx) {
        return ctx == null ? null : ctx.get(RUN_KEY) instanceof Run r ? r : null;
    }

    private static String string(CallToolRequest req, String key) {
        Object v = req.arguments() == null ? null : req.arguments().get(key);
        return v instanceof String s ? s.trim() : "";
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
