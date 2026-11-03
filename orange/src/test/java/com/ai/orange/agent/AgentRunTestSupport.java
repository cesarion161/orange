package com.ai.orange.agent;

import com.ai.orange.harness.FakeClaude;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEvent;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared wiring for the AgentRunService ITs: fake Claude Code, a private run root, no workflow auto-start. */
final class AgentRunTestSupport {

    private AgentRunTestSupport() {}

    static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void fakeHarness(DynamicPropertyRegistry r, Path runRoot) {
        FakeClaude.register(r, runRoot);
    }

    static Task newTask(TaskService taskService) {
        return taskService.createGraph(List.of(new TaskDef("agent-run-it-" + UUID.randomUUID(),
                "Agent run IT task", "exercise AgentRunService", "dev", "dev_only", null, null)), List.of()).get(0);
    }

    static String eventsText(TaskEventRepository events, UUID taskId) {
        return events.findByTaskSince(taskId, 0, 500).stream()
                .map(e -> e.eventType() + " " + e.payload().data())
                .collect(Collectors.joining("\n"));
    }

    static List<String> eventTypes(TaskEventRepository events, UUID taskId) {
        return events.findByTaskSince(taskId, 0, 500).stream().map(TaskEvent::eventType).toList();
    }

    /** Postgres normalises JSONB formatting, so compare parsed fields, not raw strings. */
    static com.fasterxml.jackson.databind.JsonNode json(org.jooq.JSONB value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(value.data());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
