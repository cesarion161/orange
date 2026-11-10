package com.ai.orange.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Guesses a repo's evidence commands from its build files (project-space spec §8: "auto-detected").
 * The guess is only ever proposed: evidence commands run on the user's machine as the user, so
 * they come into force only when the user accepts them (spec decision 13).
 */
public final class EvidenceDetector {

    private static final Pattern NPM_TEST = Pattern.compile("\"test\"\\s*:\\s*\"(?!echo \\\\?\"Error: no test specified)");
    private static final Pattern MAKE_TEST = Pattern.compile("(?m)^test\\s*:");
    /** Build files are small; anything larger is not read. */
    private static final long MAX_READ = 1_000_000;

    private EvidenceDetector() {}

    /** The commands a checkout suggests, most specific first; empty when nothing is recognised. */
    public static List<String> detect(Path root) {
        List<String> commands = new ArrayList<>();
        if (Files.isRegularFile(root.resolve("gradlew"))) commands.add("./gradlew test");
        else if (exists(root, "build.gradle", "build.gradle.kts")) commands.add("gradle test");
        if (Files.isRegularFile(root.resolve("mvnw"))) commands.add("./mvnw -q test");
        else if (exists(root, "pom.xml")) commands.add("mvn -q test");
        if (NPM_TEST.matcher(read(root.resolve("package.json"))).find()) commands.add("npm test");
        if (exists(root, "Cargo.toml")) commands.add("cargo test");
        if (exists(root, "go.mod")) commands.add("go test ./...");
        if (exists(root, "pytest.ini", "tox.ini", "conftest.py") || read(root.resolve("pyproject.toml")).contains("pytest")) {
            commands.add("pytest");
        }
        if (commands.isEmpty() && MAKE_TEST.matcher(read(root.resolve("Makefile"))).find()) commands.add("make test");
        return commands;
    }

    private static boolean exists(Path root, String... names) {
        for (String n : names) {
            if (Files.isRegularFile(root.resolve(n))) return true;
        }
        return false;
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) <= MAX_READ ? Files.readString(file) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
