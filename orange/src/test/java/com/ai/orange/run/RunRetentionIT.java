package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.run.RunTestSupport.runningTask;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Finished runs' directories (stream logs, configs) are deleted once they are older than the retention. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RunRetentionIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("retention-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("retention-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
    }

    @Autowired RunRetention retention;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired DSLContext dsl;

    Run run(boolean finished, OffsetDateTime finishedAt) throws Exception {
        Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", runningTask(taskService, tasks, "x").id(),
                props.workerId(), props.leaseTtl(), JSONB.valueOf("{}"));
        Path dir = RUN_ROOT.resolve(run.id().toString()).resolve("claude-config");
        Files.createDirectories(dir);
        Files.writeString(RUN_ROOT.resolve(run.id() + "/stream.jsonl"), "{}\n");
        Files.writeString(dir.resolve("fake-env.json"), "{}");
        if (finished) {
            runs.finish(run.id(), RunStatus.SUCCEEDED, null, BigDecimal.ZERO, 0, 0, 0, null);
            dsl.update(RUNS).set(RUNS.FINISHED_AT, finishedAt).where(RUNS.ID.eq(run.id())).execute();
        }
        return run;
    }

    @Test
    void oldRunDirsAreDeletedRecentOnesKept() throws Exception {
        Run old = run(true, OffsetDateTime.now().minusDays(15));
        Run recent = run(true, OffsetDateTime.now().minusDays(1));
        Run live = run(false, null);
        Path stray = Files.createDirectories(RUN_ROOT.resolve("not-a-run"));

        retention.sweep();

        assertThat(RUN_ROOT.resolve(old.id().toString())).doesNotExist();
        assertThat(RUN_ROOT.resolve(recent.id().toString())).exists();
        assertThat(RUN_ROOT.resolve(live.id().toString())).exists();
        assertThat(stray).as("only run directories are touched").exists();
        assertThat(runs.findById(old.id())).as("the run row stays").isPresent();
    }
}
