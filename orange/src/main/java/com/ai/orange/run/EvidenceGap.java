package com.ai.orange.run;

/**
 * A failed check from a verify run: what failed and the end of its output.
 *
 * @param source   the evidence kind ({@code tests}, {@code build}, {@code lint}, {@code custom})
 * @param exitCode -1 when the command timed out
 */
public record EvidenceGap(String source, String command, Integer exitCode, String outputTail) {}
