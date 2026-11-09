package com.ai.orange.harness.claude;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.harness.RunEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamJsonParserTest {

    final StreamJsonParser parser = new StreamJsonParser();

    @Test
    void parsesSampleInOrder() throws Exception {
        List<RunEvent> events = new ArrayList<>();
        try (var in = getClass().getResourceAsStream("/harness/claude-stream-sample.jsonl");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) events.addAll(parser.parse(line));
        }

        assertThat(events).hasSize(6);

        assertThat(events.get(0)).isEqualTo(new RunEvent.Init("s-1", "claude-sonnet-5-5"));
        assertThat(events.get(1)).isEqualTo(new RunEvent.AssistantText("Reading files"));

        RunEvent.ToolUse use = (RunEvent.ToolUse) events.get(2);
        assertThat(use.id()).isEqualTo("toolu_01");
        assertThat(use.name()).isEqualTo("Read");
        assertThat(use.input().path("file_path").asText()).isEqualTo("/tmp/w/README.md");

        RunEvent.Usage usage = (RunEvent.Usage) events.get(3);
        assertThat(usage.messageId()).isEqualTo("msg_01");
        assertThat(usage.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(usage.inputTokens()).isEqualTo(1200);
        assertThat(usage.outputTokens()).isEqualTo(80);
        assertThat(usage.cacheReadTokens()).isEqualTo(900);
        assertThat(usage.cacheWriteTokens()).isZero();

        RunEvent.ToolResult result = (RunEvent.ToolResult) events.get(4);
        assertThat(result.toolUseId()).isEqualTo("toolu_01");
        assertThat(result.isError()).isTrue();
        assertThat(result.excerpt()).isEqualTo("File does not exist.");

        RunEvent.Result done = (RunEvent.Result) events.get(5);
        assertThat(done.isError()).isFalse();
        assertThat(done.subtype()).isEqualTo("success");
        assertThat(done.text()).isEqualTo("Done.");
        assertThat(done.costUsd()).isEqualByComparingTo(new BigDecimal("0.0123"));
        assertThat(done.inputTokens()).isEqualTo(2400);
        assertThat(done.outputTokens()).isEqualTo(160);
        assertThat(done.cacheReadTokens()).isEqualTo(1800);
        assertThat(done.cacheWriteTokens()).isEqualTo(100);
        assertThat(done.numTurns()).isEqualTo(3);
        assertThat(done.sessionId()).isEqualTo("s-1");
    }

    @Test
    void errorResultIsFlagged() {
        List<RunEvent> events = parser.parse(
                "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"num_turns\":1,"
                        + "\"session_id\":\"s-2\"}");
        assertThat(events).hasSize(1);
        RunEvent.Result r = (RunEvent.Result) events.get(0);
        assertThat(r.isError()).isTrue();
        assertThat(r.subtype()).isEqualTo("error_during_execution");
        assertThat(r.costUsd()).isNull();
        assertThat(r.text()).isNull();
    }

    @Test
    void toolResultContentBlocksAreJoined() {
        List<RunEvent> events = parser.parse(
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"t9\","
                        + "\"type\":\"tool_result\",\"content\":[{\"type\":\"text\",\"text\":\"line one\"},"
                        + "{\"type\":\"text\",\"text\":\"line two\"}]}]}}");
        RunEvent.ToolResult r = (RunEvent.ToolResult) events.get(0);
        assertThat(r.isError()).isFalse();
        assertThat(r.excerpt()).isEqualTo("line one\nline two");
    }

    @Test
    void unknownTypeBecomesUnparsed() {
        String line = "{\"type\":\"rate_limit_event\"}";
        assertThat(parser.parse(line)).containsExactly(new RunEvent.Unparsed(line));
    }

    @Test
    void garbageBecomesUnparsed() {
        assertThat(parser.parse("not json {")).containsExactly(new RunEvent.Unparsed("not json {"));
    }

    @Test
    void jsonWithoutTypeBecomesUnparsed() {
        assertThat(parser.parse("[1,2,3]")).containsExactly(new RunEvent.Unparsed("[1,2,3]"));
    }

    @Test
    void blankIsEmpty() {
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    void toolResultExcerptIsTruncated() {
        String big = "x".repeat(10_000);
        List<RunEvent> events = parser.parse(
                "{\"type\":\"user\",\"message\":{\"content\":[{\"tool_use_id\":\"t1\",\"type\":\"tool_result\","
                        + "\"content\":\"" + big + "\"}]}}");
        RunEvent.ToolResult r = (RunEvent.ToolResult) events.get(0);
        assertThat(r.excerpt()).hasSize(2000);
    }

    @Test
    void apiRetryIsParsed() {
        // Captured from claude 2.1.319 with a rejected token (ClaudeCliContractLiveIT).
        String line = "{\"type\":\"system\",\"subtype\":\"api_retry\",\"attempt\":1,\"max_retries\":10,"
                + "\"retry_delay_ms\":609,\"error_status\":401,\"error\":\"authentication_failed\","
                + "\"session_id\":\"10d5ea3e-9ee4-474b-b098-a8d131dbbe07\",\"uuid\":\"03ee28a6\"}";
        assertThat(parser.parse(line)).containsExactly(new RunEvent.ApiRetry(1, 10, 609, 401, "authentication_failed"));
    }

    @Test
    void apiRetryWithoutStatusHasNullStatus() {
        String line = "{\"type\":\"system\",\"subtype\":\"api_retry\",\"attempt\":2,\"max_retries\":10,"
                + "\"retry_delay_ms\":1164,\"error\":\"overloaded\"}";
        assertThat(parser.parse(line)).containsExactly(new RunEvent.ApiRetry(2, 10, 1164, null, "overloaded"));
    }
}
