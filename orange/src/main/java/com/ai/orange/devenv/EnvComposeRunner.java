package com.ai.orange.devenv;

import com.ai.orange.workflow.EnvInfo;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Brings up / tears down the per-env docker-compose stack so testers see a
 * running app at the URLs surfaced in their prompt. Opt-in via
 * {@code orange.envs.compose-file} — when blank, this is a no-op and the
 * tester is on its own (back-compat with the lease-only auto-env-prep mode).
 *
 * <p>Convention: each env gets its own docker-compose project name
 * ({@code orange-<env-name>-<short-task>}) so parallel envs don't collide on
 * container names or networks. Env vars are exported to the compose process
 * so the project's compose template can interpolate {@code ${ORANGE_PORT_WEB}}
 * etc.
 *
 * <p>Failures during {@code up} bubble as {@link IOException} so the
 * orchestrator marks the stage failed and releases the lease.
 */
@Service
public class EnvComposeRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvComposeRunner.class);

    private final String composeFile;
    private final Duration startTimeout;
    private final Duration downTimeout;

    public EnvComposeRunner(
            @Value("${orange.envs.compose-file:}") String composeFile,
            @Value("${orange.envs.compose-start-timeout:5m}") Duration startTimeout,
            @Value("${orange.envs.compose-down-timeout:1m}") Duration downTimeout) {
        this.composeFile = composeFile;
        this.startTimeout = startTimeout;
        this.downTimeout = downTimeout;
    }

    public boolean enabled() {
        return composeFile != null && !composeFile.isBlank();
    }

    /** Brings up the env's compose stack. No-op when compose-file is unset. */
    public void up(EnvInfo env) throws IOException, InterruptedException {
        if (!enabled()) {
            log.debug("compose up skipped (no orange.envs.compose-file)");
            return;
        }
        List<String> cmd = composeCommand(env);
        cmd.addAll(List.of("up", "-d", "--wait", "--remove-orphans"));
        log.info("compose up for env {}: {}", env.name(), cmd);
        run(cmd, env, startTimeout);
    }

    /**
     * Tears down the env's compose stack. Idempotent: a missing stack returns 0
     * from {@code docker compose down}, so this is safe to call from release
     * paths even after a failed {@link #up}.
     */
    public void down(EnvInfo env) throws IOException, InterruptedException {
        if (!enabled()) return;
        List<String> cmd = composeCommand(env);
        cmd.addAll(List.of("down", "-v", "--remove-orphans"));
        log.info("compose down for env {}", env.name());
        try {
            run(cmd, env, downTimeout);
        } catch (IOException e) {
            // Don't propagate — down failures leave only stray containers,
            // not a correctness problem. Log and continue.
            log.warn("compose down for env {} failed: {}", env.name(), e.getMessage());
        }
    }

    // ─────────────────────────── internals ──────────────────────────────

    private List<String> composeCommand(EnvInfo env) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("compose");
        cmd.add("-f");
        cmd.add(composeFile);
        cmd.add("-p");
        cmd.add(projectName(env));
        return cmd;
    }

    /** docker-compose project name. Lowercased, only safe chars. */
    private static String projectName(EnvInfo env) {
        return ("orange-" + env.name()).toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    private void run(List<String> cmd, EnvInfo env, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putAll(envVarsFor(env));
        Process p = pb.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("compose command timed out after " + timeout + ": "
                    + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            String tail = new String(p.getInputStream().readAllBytes());
            int max = 1024;
            if (tail.length() > max) tail = tail.substring(tail.length() - max);
            throw new IOException("compose exit " + p.exitValue() + ": " + tail);
        }
    }

    private static Map<String, String> envVarsFor(EnvInfo env) {
        Map<String, String> vars = new HashMap<>();
        vars.put("ORANGE_ENV_NAME", env.name());
        if (env.ports() != null) {
            env.ports().forEach((key, port) ->
                    vars.put("ORANGE_PORT_" + key.toUpperCase(), String.valueOf(port)));
        }
        if (env.dataDir() != null) vars.put("ORANGE_DATA_DIR", env.dataDir());
        if (env.fixtureSet() != null) vars.put("ORANGE_FIXTURE_SET", env.fixtureSet());
        if (env.authBypassToken() != null) vars.put("ORANGE_AUTH_BYPASS_TOKEN", env.authBypassToken());
        return vars;
    }
}
