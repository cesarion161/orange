package com.ai.orange.harness.claude;

import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.HarnessAdapter;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.ProcessRunHandle;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunHandle;
import com.ai.orange.harness.RunSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs Claude Code headless ({@code claude -p --output-format stream-json}). Each run gets its own
 * directory under {@code orange.harness.run-root}: the raw stream, stderr, and a private
 * {@code CLAUDE_CONFIG_DIR} so the user's own Claude Code login, plugins and hooks never load.
 */
@Component
public class ClaudeCodeAdapter implements HarnessAdapter {

    public static final String ID = "claude-code";

    private final HarnessProperties props;
    private final Supplier<Map<String, String>> hostEnv;
    private final StreamJsonParser parser = new StreamJsonParser();

    @Autowired
    public ClaudeCodeAdapter(HarnessProperties props) {
        this(props, System::getenv);
    }

    public ClaudeCodeAdapter(HarnessProperties props, Supplier<Map<String, String>> hostEnv) {
        this.props = props;
        this.hostEnv = hostEnv;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RunHandle start(RunSpec spec, Consumer<RunEvent> sink) throws IOException {
        Path runDir = props.runRoot().resolve(spec.runId().toString());
        Path configDir = runDir.resolve("claude-config");
        Files.createDirectories(configDir);

        ProcessBuilder pb = new ProcessBuilder(ClaudeCodeCommand.argv(spec, props.claudeCode().command()));
        pb.directory(spec.cwd().toFile());
        pb.environment().clear();
        pb.environment().putAll(ClaudeCodeCommand.env(spec, hostEnv.get(), props.claudeCode().passEnv(), configDir));

        return ProcessRunHandle.start(pb, spec.prompt(), runDir, parser::parse, sink, redactor(spec.credentials()));
    }

    /** Replaces the run's own credential wherever it appears in the harness's output. */
    static UnaryOperator<String> redactor(Credentials credentials) {
        String secret = switch (credentials) {
            case Credentials.Subscription s -> s.oauthToken();
            case Credentials.Gateway g -> g.virtualKey();
        };
        if (secret == null || secret.isBlank()) return UnaryOperator.identity();
        return line -> line.contains(secret) ? line.replace(secret, "***") : line;
    }
}
