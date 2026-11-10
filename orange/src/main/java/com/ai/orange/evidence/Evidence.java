package com.ai.orange.evidence;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One check orange ran on a head of a task or of a session, or one diff review of it.
 *
 * @param kind     {@code tests}, {@code build}, {@code lint}, {@code custom}, {@code diff_review},
 *                 {@code screenshot} or {@code manual}
 * @param command  the shell command, for command evidence
 * @param exitCode -1 when the command timed out
 */
public record Evidence(UUID id, UUID taskId, UUID runId, String headSha, String kind, String command,
                       Integer exitCode, String outputTail, boolean passed, OffsetDateTime createdAt, UUID sessionId) {}
