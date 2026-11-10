package com.ai.orange.harness.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pure: a {@link ContainerSpec} as a docker command line.
 *
 * <p>What a container gets is what is written here and nothing else: the mounts of its spec at
 * their own paths, no capabilities, no new privileges, a user that is not root, and either the
 * run network or no network. Never in the argv: {@code --privileged}, the Docker socket, the
 * value of a secret (a secret is {@code --env NAME}; docker takes its value from the client's
 * environment).
 */
public final class DockerCommand {

    public static final String LABEL_RUN = "orange.run";
    public static final String LABEL_KIND = "orange.kind";
    public static final String LABEL_WORKER = "orange.worker";

    /** {@code NAME=value} splits at the first {@code =}: a name that held one would put part of itself in the value. */
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private DockerCommand() {}

    /** The name of a run's first container; its further ones append a suffix. */
    public static String runName(UUID runId) {
        return "orange-run-" + runId;
    }

    /** The labels of everything a run starts, so one filter on {@link #LABEL_RUN} finds it all. */
    public static Map<String, String> labels(UUID runId, String kind, String workerId) {
        Map<String, String> labels = new TreeMap<>();
        labels.put(LABEL_RUN, runId.toString());
        if (kind != null && !kind.isBlank()) labels.put(LABEL_KIND, kind);
        if (workerId != null && !workerId.isBlank()) labels.put(LABEL_WORKER, workerId);
        return labels;
    }

    /**
     * {@code docker run} for {@code c}.
     *
     * @param user {@code uid[:gid]} the command runs as
     */
    public static List<String> run(ContainerSpec c, RunnerProperties.Container p, String user) {
        List<String> a = new ArrayList<>(p.docker());
        // --pull never: orange never fetches an image by itself, so a typo cannot run someone else's.
        // --init: a reaper for what the command leaves behind, and signals reach it.
        a.addAll(List.of("run", "--rm", "-i", "--pull", "never", "--init"));
        a.add("--name");
        a.add(c.name());
        new TreeMap<>(c.labels()).forEach((name, value) -> {
            a.add("--label");
            a.add(name + "=" + value);
        });
        a.addAll(List.of("--user", user, "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--pids-limit", Integer.toString(p.pidsLimit())));
        if (!p.memory().isBlank()) {
            a.add("--memory");
            a.add(p.memory());
        }
        if (!p.cpus().isBlank()) {
            a.add("--cpus");
            a.add(p.cpus());
        }
        a.add("--network");
        if (c.online()) {
            a.add(p.network());
            a.add("--add-host");
            a.add(ContainerUrls.HOST + ":host-gateway");
        } else {
            a.add("none");
        }
        a.add("--workdir");
        a.add(c.workdir().toString());
        for (ContainerSpec.Mount m : c.mounts()) {
            // --mount, not -v: -v creates a missing source as a directory owned by root; --mount fails.
            a.add("--mount");
            a.add(mount(m));
        }
        if (c.online()) {
            for (String volume : p.volumes()) {
                a.add("--volume");
                a.add(volume);
            }
        }
        new TreeMap<>(c.env()).forEach((name, value) -> {
            a.add("--env");
            a.add(envName(name) + "=" + value);
        });
        for (String name : new TreeMap<>(c.secretEnv()).keySet()) {
            a.add("--env");
            a.add(envName(name));
        }
        a.add(p.image());
        a.addAll(c.command());
        return a;
    }

    /** {@code docker rm -f}: kills and removes; exit code 0 when there is no such container. */
    public static List<String> remove(List<String> docker, List<String> namesOrIds) {
        List<String> a = new ArrayList<>(docker);
        a.add("rm");
        a.add("-f");
        a.addAll(namesOrIds);
        return a;
    }

    /** Prints the ids of every container of the run, running or not. */
    public static List<String> listByRun(List<String> docker, UUID runId) {
        List<String> a = new ArrayList<>(docker);
        a.addAll(List.of("ps", "-aq", "--filter", "label=" + LABEL_RUN + "=" + runId));
        return a;
    }

    /**
     * Whether {@code docker run} ended with one of its own exit codes: 125 (the daemon refused, e.g.
     * no such image), 126 (the command cannot be invoked) or 127 (the image has no such command).
     * A shell inside the container returns 126 and 127 for its own commands too.
     */
    public static boolean dockerFailed(int exitCode) {
        return exitCode == 125 || exitCode == 126 || exitCode == 127;
    }

    private static String mount(ContainerSpec.Mount m) {
        String path = m.path().toString();
        // --mount is one CSV record: a comma would start the next field, a quote would open a quoted one.
        if (path.indexOf(',') >= 0 || path.indexOf('"') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("a mounted path must not contain a comma, a quote or a line break: " + path);
        }
        return "type=bind,source=" + path + ",target=" + path + (m.readOnly() ? ",readonly" : "");
    }

    private static String envName(String name) {
        if (name == null || !ENV_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a variable name: " + name);
        }
        return name;
    }
}
