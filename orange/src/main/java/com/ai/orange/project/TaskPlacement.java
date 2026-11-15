package com.ai.orange.project;

import java.util.UUID;

/** Where a task's work is done: a project and one of its repos. {@link #NONE}: the legacy base repo. */
public record TaskPlacement(UUID projectId, UUID repoId) {

    public static final TaskPlacement NONE = new TaskPlacement(null, null);
}
