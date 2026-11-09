package com.ai.orange.run;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Every {@link RunExecutor} bean, looked up by the kind it runs. */
@Component
public class RunExecutors {

    private final List<RunExecutor> executors;

    public RunExecutors(List<RunExecutor> executors) {
        this.executors = List.copyOf(executors);
    }

    /** The executor for {@code kind}; empty for kinds nothing runs yet (judge, brief). */
    public Optional<RunExecutor> forKind(RunKind kind) {
        // Asked each time rather than indexed once, so an executor's kinds are read when it is used.
        return executors.stream().filter(e -> e.kinds() != null && e.kinds().contains(kind)).findFirst();
    }
}
