package com.ai.orange.task;

import static com.ai.orange.run.RunTestSupport.runningTask;
import static com.ai.orange.run.RunTestSupport.start;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunResult;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.run.TaskCancellation;
import com.ai.orange.run.TaskReactor;
import com.ai.orange.run.WorkerRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.ai.orange.db.jooq.Tables.ATTENTION_ITEMS;
import static com.ai.orange.db.jooq.Tables.TASKS;

/**
 * A stuck task asks for a person (remote-first spec §7.5): a blocked report, a {@code request_human} and
 * the reactor's give-up each file one urgent item about the task; the user's answer reopens the task with
 * that answer as its note; a reopen over REST or a cancel resolves the item instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TaskAttentionIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("task-attention-runs-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, RunTestSupport.baseRepo(), RunTestSupport.tempDir("task-attention-wt-"));
    }

    @Value("${local.server.port}") int port;

    @Autowired WorkerRunner runner;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskReactor reactor;
    @Autowired TaskCancellation cancellation;
    @Autowired AttentionService attention;
    @Autowired AttentionRepository items;
    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;

    @BeforeEach
    void unpause() {
        pause.resume();
    }

    RunResult implement(Task task) {
        return runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, task.role(), "{}"));
    }

    List<AttentionItem> itemsOf(UUID taskId) {
        return dsl.selectFrom(ATTENTION_ITEMS).where(ATTENTION_ITEMS.TASK_ID.eq(taskId))
                .orderBy(ATTENTION_ITEMS.CREATED_AT.asc(), ATTENTION_ITEMS.ID.asc())
                .fetch().map(r -> items.findById(r.getId()).orElseThrow());
    }

    AttentionItem theItem(UUID taskId) {
        List<AttentionItem> all = itemsOf(taskId);
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    Task stuckTask() {
        Task task = runningTask(taskService, tasks, "[[fake:blocked]]");
        assertThat(implement(task).status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_HUMAN);
        return tasks.findById(task.id()).orElseThrow();
    }

    @Test
    void aBlockedReportFilesAnUrgentItem() {
        Task task = runningTask(taskService, tasks, "[[fake:report-blocked]]");
        implement(task);

        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_HUMAN);
        AttentionItem item = theItem(task.id());
        assertThat(item.kind()).isEqualTo(AttentionItem.TASK_NEEDS_HUMAN);
        assertThat(item.urgency()).isEqualTo(AttentionItem.URGENT);
        assertThat(item.status()).isEqualTo(AttentionItem.OPEN);
        assertThat(item.title()).isEqualTo("Add a greeting needs you");
        assertThat(item.dedupeKey()).startsWith("task_needs_human:" + task.id() + ":");
        assertThat(item.body())
                .contains("The mail service needs an API key nobody gave me.")
                .contains("no project")
                .contains(Task.shortId(task.id()))
                .contains("run_started");
    }

    @Test
    void requestHumanFilesAnUrgentItem() {
        Task task = stuckTask();

        AttentionItem item = theItem(task.id());
        assertThat(item.urgency()).isEqualTo(AttentionItem.URGENT);
        assertThat(item.taskId()).isEqualTo(task.id());
        assertThat(item.body()).contains("Which database should I use, Postgres or SQLite?");
    }

    @Test
    void tooManyFailuresFileAnItem() {
        Task task = runningTask(taskService, tasks, "Add a greeting.");
        dsl.update(TASKS).set(TASKS.FAILURES, 3).where(TASKS.ID.eq(task.id())).execute();

        reactor.react(task.id());

        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_HUMAN);
        AttentionItem item = theItem(task.id());
        assertThat(item.urgency()).isEqualTo(AttentionItem.URGENT);
        assertThat(item.body()).contains("failed 3 times");
    }

    @Test
    void resolvingWithAnAnswerReopensTheTaskWithThatNote() throws Exception {
        Task task = stuckTask();
        AttentionItem item = theItem(task.id());

        attention.resolve(item.id(), "Use Postgres, it is what we deploy.", "user");

        Task reopened = tasks.findById(task.id()).orElseThrow();
        assertThat(reopened.status()).isEqualTo(TaskStatus.READY);
        AttentionItem resolved = items.findById(item.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(AttentionItem.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo("Use Postgres, it is what we deploy.");

        // The next implement run's prompt carries the answer as the operator's note.
        tasks.transitionStatus(task.id(), TaskStatus.READY, TaskStatus.RUNNING);
        RunResult next = implement(reopened);
        String prompt = Files.readString(RUN_ROOT.resolve(next.runId() + "/claude-config/fake-prompt.txt"));
        assertThat(prompt).contains("Note from the operator").contains("Use Postgres, it is what we deploy.");
    }

    @Test
    void resolvingBlankDismisses() {
        Task task = stuckTask();
        AttentionItem item = theItem(task.id());

        attention.resolve(item.id(), "  ", "user");

        assertThat(items.findById(item.id()).orElseThrow().status()).isEqualTo(AttentionItem.RESOLVED);
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_HUMAN);
    }

    @Test
    void reopeningOverRestResolvesTheItem() throws Exception {
        Task task = stuckTask();
        AttentionItem item = theItem(task.id());

        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/tasks/" + task.id() + "/reopen"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"try SQLite\",\"actor\":\"operator\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        AttentionItem resolved = items.findById(item.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(AttentionItem.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo("reopened");
        assertThat(resolved.resolvedBy()).isEqualTo("operator");
    }

    @Test
    void cancellingResolvesTheItem() {
        Task task = stuckTask();
        AttentionItem item = theItem(task.id());

        cancellation.cancel(task.id());

        AttentionItem resolved = items.findById(item.id()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(AttentionItem.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo("cancelled");
    }

    @Test
    void stuckAgainAfterAReopenFilesANewItem() {
        Task task = stuckTask();
        AttentionItem first = theItem(task.id());
        attention.resolve(first.id(), "Pick whichever you like.", "user");

        tasks.transitionStatus(task.id(), TaskStatus.READY, TaskStatus.RUNNING);
        implement(tasks.findById(task.id()).orElseThrow());

        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_HUMAN);
        List<AttentionItem> all = itemsOf(task.id());
        assertThat(all).hasSize(2);
        assertThat(all.get(0).status()).isEqualTo(AttentionItem.RESOLVED);
        assertThat(all.get(1).status()).isEqualTo(AttentionItem.OPEN);
        assertThat(all.get(1).dedupeKey()).isNotEqualTo(first.dedupeKey());
    }

    @Test
    void resolveForTaskResolvesOnlyThatTasksOpenItems() {
        Task a = stuckTask();
        Task b = stuckTask();

        assertThat(items.resolveForTask(a.id(), "user", "done")).isEqualTo(1);
        assertThat(items.resolveForTask(a.id(), "user", "again")).isZero();

        assertThat(theItem(a.id()).resolution()).isEqualTo("done");
        assertThat(theItem(b.id()).status()).isEqualTo(AttentionItem.OPEN);
    }
}
