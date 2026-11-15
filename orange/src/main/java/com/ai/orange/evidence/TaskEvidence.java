package com.ai.orange.evidence;

import com.ai.orange.project.WorkConventions;
import com.ai.orange.task.Task;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The evidence commands for a task's work. A task of a project uses the project's {@code done} and
 * {@code evidence} conventions, like a session of it (remote-first spec §7.1); a task of no project
 * uses {@code orange.evidence.commands}.
 */
@Component
public class TaskEvidence {

    private final EvidenceProperties props;
    private final WorkConventions conventions;

    public TaskEvidence(EvidenceProperties props, WorkConventions conventions) {
        this.props = props;
        this.conventions = conventions;
    }

    /** @throws IllegalArgumentException when the project's {@code evidence} convention is not a list of commands */
    public List<EvidenceProperties.Command> commands(Task t) {
        if (t.projectId() == null) return props.commands();
        Set<String> done = conventions.done(t.projectId(), null);
        return done.contains(WorkConventions.EVIDENCE) ? conventions.evidence(t.projectId(), null) : List.of();
    }

    /**
     * The task's project asks for evidence and has no commands in force: orange can implement but
     * not verify. A broken convention is not missing: the verify run fails and says why.
     */
    public boolean missing(Task t) {
        if (t.projectId() == null) return false;
        if (!conventions.done(t.projectId(), null).contains(WorkConventions.EVIDENCE)) return false;
        try {
            return conventions.evidence(t.projectId(), null).isEmpty();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
