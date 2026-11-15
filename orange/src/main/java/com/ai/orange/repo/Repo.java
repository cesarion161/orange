package com.ai.orange.repo;

import java.util.UUID;

/** A GitHub repository of a project (remote-first spec §7.1). orange keeps a mirror of it. */
public record Repo(UUID id, UUID projectId, RepoKey key, String defaultBranch) {}
