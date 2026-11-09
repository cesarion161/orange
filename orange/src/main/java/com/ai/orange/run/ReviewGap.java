package com.ai.orange.run;

/**
 * One problem a diff review found. {@code path} and {@code line} are null when the gap is not
 * about one place (for example "no tests cover the new endpoint").
 */
public record ReviewGap(String path, Integer line, String body) {
}
