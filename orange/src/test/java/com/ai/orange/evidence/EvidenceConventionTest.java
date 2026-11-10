package com.ai.orange.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code evidence} convention as commands, and what a checkout suggests when it is unset. */
class EvidenceConventionTest {

    static List<EvidenceProperties.Command> parse(String json) throws Exception {
        return EvidenceCommands.parse(Json.mapper().readTree(json));
    }

    @Test
    void commandsAreStringsOrObjects() throws Exception {
        assertThat(parse("[\"./gradlew test\", {\"command\": \"npm run lint\", \"kind\": \"lint\", \"timeout\": \"PT5M\"}]"))
                .containsExactly(
                        new EvidenceProperties.Command("tests", "./gradlew test", Duration.ofMinutes(20)),
                        new EvidenceProperties.Command("lint", "npm run lint", Duration.ofMinutes(5)));
        assertThat(parse("\"make check\"")).singleElement()
                .satisfies(c -> assertThat(c.kind()).isEqualTo("custom"));
        assertThat(parse("[]")).isEmpty();
    }

    @Test
    void anythingElseIsRefused() {
        assertThatThrownBy(() -> parse("{\"command\": \"x\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("[42]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("[{\"command\": \"x\", \"kind\": \"vibes\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("[{\"command\": \"x\", \"timeout\": \"soon\"}]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parse("[\"  \"]")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildFilesSuggestCommands(@TempDir Path dir) throws Exception {
        assertThat(EvidenceDetector.detect(dir)).isEmpty();
        Files.writeString(dir.resolve("gradlew"), "");
        Files.writeString(dir.resolve("package.json"), "{\"scripts\": {\"test\": \"vitest run\"}}");
        Files.writeString(dir.resolve("Makefile"), "test:\n\ttrue\n");
        assertThat(EvidenceDetector.detect(dir)).containsExactly("./gradlew test", "npm test");
    }

    @Test
    void aPlaceholderNpmTestScriptIsNotEvidence(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package.json"),
                "{\"scripts\": {\"test\": \"echo \\\"Error: no test specified\\\" && exit 1\"}}");
        assertThat(EvidenceDetector.detect(dir)).isEmpty();
        Files.writeString(dir.resolve("Makefile"), "build:\n\ttrue\ntest: build\n\ttrue\n");
        assertThat(EvidenceDetector.detect(dir)).containsExactly("make test");
    }
}
