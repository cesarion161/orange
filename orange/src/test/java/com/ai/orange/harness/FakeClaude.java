package com.ai.orange.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Test utility: the command prefix that launches the fake Claude Code
 * ({@code src/test/resources/fake-claude/FakeClaude.java}) as a single-file Java program on the
 * test JVM's own {@code java} binary. Plug it into {@code orange.harness.claude-code.command}.
 */
public final class FakeClaude {

    private static volatile Path source;

    private FakeClaude() {}

    public static List<String> command() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return List.of(java, source().toString());
    }

    private static Path source() {
        if (source == null) {
            synchronized (FakeClaude.class) {
                if (source == null) {
                    try (InputStream in = FakeClaude.class.getResourceAsStream("/fake-claude/FakeClaude.java")) {
                        if (in == null) throw new IllegalStateException("fake-claude/FakeClaude.java not on the test classpath");
                        Path dir = Files.createTempDirectory("fake-claude-");
                        Path file = dir.resolve("FakeClaude.java");
                        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                        file.toFile().deleteOnExit();
                        dir.toFile().deleteOnExit();
                        source = file;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }
        return source;
    }
}
