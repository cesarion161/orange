package com.ai.orange.project;

import java.util.UUID;

/** A repo of a project: the main checkout's path on the node that reported it. */
public record ProjectRepo(UUID id, UUID projectId, String nodeId, String repoRoot, String remoteUrl,
                          String defaultBranch) {}
