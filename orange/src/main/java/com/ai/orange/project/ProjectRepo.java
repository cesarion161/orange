package com.ai.orange.project;

import java.util.UUID;

/**
 * A checkout of a project: the main checkout's path on the node that reported it. {@code repoId} is
 * the GitHub repo it is a checkout of, null when its remote is not on GitHub.
 */
public record ProjectRepo(UUID id, UUID projectId, String nodeId, String repoRoot, String remoteUrl,
                          String defaultBranch, UUID repoId) {}
