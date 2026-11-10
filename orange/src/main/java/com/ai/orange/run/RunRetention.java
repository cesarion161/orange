package com.ai.orange.run;

import com.ai.orange.harness.HarnessProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the directories of runs that finished longer ago than {@code orange.runs.retention}
 * (stream log, stderr, the per-run Claude config). The run rows stay.
 */
@Component
public class RunRetention {

    private static final Logger log = LoggerFactory.getLogger(RunRetention.class);

    private final RunRepository runs;
    private final HarnessProperties harness;
    private final RunsProperties props;

    public RunRetention(RunRepository runs, HarnessProperties harness, RunsProperties props) {
        this.runs = runs;
        this.harness = harness;
        this.props = props;
    }

    public void sweep() {
        Path root = harness.runRoot().toAbsolutePath().normalize();
        int deleted = 0;
        for (Run run : runs.findFinishedBefore(OffsetDateTime.now().minus(props.retention()))) {
            // The directory is named by the run's UUID, so this can never leave the run root.
            Path dir = root.resolve(run.id().toString());
            if (!Files.isDirectory(dir)) continue;
            try {
                // What a contained run left when its clone could not be discarded may hold read-only directories.
                RunWorkspaces.delete(dir);
                deleted++;
            } catch (IOException | UncheckedIOException e) {
                log.warn("could not delete run dir {}: {}", dir, e.getMessage());
            }
        }
        if (deleted > 0) log.info("run retention: deleted {} run dir(s) older than {}", deleted, props.retention());
    }

    @Scheduled(fixedDelayString = "6h", initialDelayString = "10m")
    void scheduled() {
        if (props.loopEnabled()) sweep();
    }
}
