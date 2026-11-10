package com.ai.orange.harness.claude;

import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.HarnessAdapter;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.ProcessRunHandle;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunHandle;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.harness.container.ContainerRuntime;
import com.ai.orange.harness.container.ContainerSpec;
import com.ai.orange.harness.container.ContainerUrls;
import com.ai.orange.harness.container.DockerCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs Claude Code headless ({@code claude -p --output-format stream-json}). Each run gets its own
 * directory under {@code orange.harness.run-root}: the raw stream, stderr, and a private
 * {@code CLAUDE_CONFIG_DIR} so the user's own Claude Code login, plugins and hooks never load.
 *
 * <p>A run with a {@link com.ai.orange.harness.Sandbox} starts the same command line in a
 * container. The process orange supervises is then the docker client, which carries stdin to the
 * container and its stdout, stderr and exit code back, so everything after the start is the same.
 */
@Component
public class ClaudeCodeAdapter implements HarnessAdapter {

    public static final String ID = "claude-code";

    private final HarnessProperties props;
    private final Supplier<Map<String, String>> hostEnv;
    /** Null when this adapter only starts host processes. */
    private final ContainerRuntime containers;
    private final StreamJsonParser parser = new StreamJsonParser();

    @Autowired
    public ClaudeCodeAdapter(HarnessProperties props, ContainerRuntime containers) {
        this(props, System::getenv, containers);
    }

    public ClaudeCodeAdapter(HarnessProperties props) {
        this(props, System::getenv);
    }

    public ClaudeCodeAdapter(HarnessProperties props, Supplier<Map<String, String>> hostEnv) {
        this(props, hostEnv, null);
    }

    private ClaudeCodeAdapter(HarnessProperties props, Supplier<Map<String, String>> hostEnv,
                              ContainerRuntime containers) {
        this.props = props;
        this.hostEnv = hostEnv;
        this.containers = containers;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RunHandle start(RunSpec spec, Consumer<RunEvent> sink) throws IOException {
        if (spec.sandbox() != null) return startContained(spec, sink);
        Path runDir = props.runRoot().resolve(spec.runId().toString());
        Path configDir = runDir.resolve("claude-config");
        Files.createDirectories(configDir);

        ProcessBuilder pb = new ProcessBuilder(ClaudeCodeCommand.argv(spec, props.claudeCode().command()));
        pb.directory(spec.cwd().toFile());
        pb.environment().clear();
        pb.environment().putAll(ClaudeCodeCommand.env(spec, hostEnv.get(), props.claudeCode().passEnv(), configDir));

        return ProcessRunHandle.start(pb, spec.prompt(), runDir, parser::parse, sink,
                redactor(spec.credentials(), spec.redact()));
    }

    /**
     * The same command line in a container that holds the run's working directory, its Claude
     * config directory and its MCP config, each at its own path, and nothing of the host's
     * environment: not even {@code PATH}, the image has its own. The credential is passed by name;
     * its value is only in the docker client's environment.
     */
    private RunHandle startContained(RunSpec spec, Consumer<RunEvent> sink) throws IOException {
        if (containers == null) {
            throw new IllegalStateException("run " + spec.runId()
                    + " asks for a container, but this adapter has no container runtime");
        }
        Path runDir = props.runRoot().resolve(spec.runId().toString()).toAbsolutePath();
        Path configDir = runDir.resolve("claude-config");
        Files.createDirectories(configDir);

        Map<String, String> env = new TreeMap<>(ClaudeCodeCommand.env(spec, Map.of(), List.of(), configDir));
        Map<String, String> secretEnv = new TreeMap<>();
        for (String name : ClaudeCodeCommand.SECRET_ENV) {
            String value = env.remove(name);
            if (value != null) secretEnv.put(name, value);
        }
        // The gateway on orange's own host is not "localhost" from inside a container.
        env.computeIfPresent("ANTHROPIC_BASE_URL", (name, url) -> ContainerUrls.fromContainer(url));
        env.put("HOME", containers.settings().home());

        List<ContainerSpec.Mount> mounts = new ArrayList<>();
        mounts.add(new ContainerSpec.Mount(spec.cwd(), spec.sandbox().readOnly()));
        mounts.add(new ContainerSpec.Mount(configDir, false));
        if (spec.mcpConfig() != null) mounts.add(new ContainerSpec.Mount(spec.mcpConfig(), true));

        ContainerSpec container = new ContainerSpec(DockerCommand.runName(spec.runId()), spec.sandbox().labels(),
                spec.cwd(), mounts, env, secretEnv, true,
                ClaudeCodeCommand.argv(spec, containers.settings().claudeCommand()));
        return ProcessRunHandle.start(containers.command(container), spec.prompt(), runDir, parser::parse, sink,
                redactor(spec.credentials(), spec.redact()), () -> containers.remove(container.name()));
    }

    /** Replaces the run's own credential wherever it appears in the harness's output. */
    static UnaryOperator<String> redactor(Credentials credentials) {
        return redactor(credentials, List.of());
    }

    /** Replaces the run's credential and every {@code extra} secret wherever they appear in the output. */
    static UnaryOperator<String> redactor(Credentials credentials, List<String> extra) {
        String credential = switch (credentials) {
            case Credentials.Subscription s -> s.oauthToken();
            case Credentials.Gateway g -> g.virtualKey();
        };
        List<String> secrets = new ArrayList<>();
        if (credential != null && !credential.isBlank()) secrets.add(credential);
        for (String s : extra) {
            if (s != null && !s.isBlank()) secrets.add(s);
        }
        if (secrets.isEmpty()) return UnaryOperator.identity();
        return line -> {
            String out = line;
            for (String secret : secrets) {
                if (out.contains(secret)) out = out.replace(secret, "***");
            }
            return out;
        };
    }
}
