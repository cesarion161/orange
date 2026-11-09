package com.ai.orange.run;

import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cancels a task and everything it has in flight. The task moves to {@code cancelled} first,
 * under its row lock and together with its queued runs, so the reactor (which takes the same lock)
 * can never queue a fresh run in between; then a running agent is killed. Executors that pick up
 * a run of an ended task finish it {@code cancelled} without doing its work.
 */
@Component
public class TaskCancellation {

    private final TaskRepository tasks;
    private final RunRepository runs;
    private final WorkerRunner runner;
    private final TransactionTemplate tx;

    public TaskCancellation(TaskRepository tasks, RunRepository runs, WorkerRunner runner,
                            PlatformTransactionManager txManager) {
        this.tasks = tasks;
        this.runs = runs;
        this.runner = runner;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * @return the task as it was before the cancel (empty when it does not exist); a task that was
     *         already terminal is left as it is
     */
    public Optional<Task> cancel(UUID taskId) {
        Optional<Task> before = tx.execute(status -> tasks.lockForUpdate(taskId).map(t -> {
            if (!t.status().isTerminal()) {
                tasks.transitionStatus(t.id(), t.status(), TaskStatus.CANCELLED);
                runs.cancelQueued(t.id());
            }
            return t;
        }));
        if (before != null && before.isPresent() && !before.get().status().isTerminal()) {
            runner.cancel(taskId);
        }
        return before == null ? Optional.empty() : before;
    }
}
