package com.ai.orange.harness.container;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One command in a container of its own. Every mount has the same path inside and outside, so a
 * path in an argument, a variable or an event means the same file on the host.
 *
 * @param name      the container's name ({@link DockerCommand#runName}, plus a suffix for a run's further containers)
 * @param labels    {@link DockerCommand#labels}: how a run's containers are found again
 * @param workdir   the command's working directory; one of the mounts, or below one
 * @param mounts    the only directories and files of the host the container holds
 * @param env       variables whose values may be read by anyone: they are in the argv as {@code --env NAME=value}
 * @param secretEnv variables that are in the argv as {@code --env NAME}; their values exist only in
 *                  the docker client's environment
 * @param online    false = {@code --network none}
 * @param command   the argv inside the container
 */
public record ContainerSpec(String name, Map<String, String> labels, Path workdir, List<Mount> mounts,
                            Map<String, String> env, Map<String, String> secretEnv, boolean online,
                            List<String> command) {

    public ContainerSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("a container needs a name");
        if (workdir == null || !workdir.isAbsolute()) throw new IllegalArgumentException("workdir must be absolute: " + workdir);
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("a container needs a command");
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        mounts = mounts == null ? List.of() : List.copyOf(mounts);
        env = env == null ? Map.of() : Map.copyOf(env);
        secretEnv = secretEnv == null ? Map.of() : Map.copyOf(secretEnv);
        command = List.copyOf(command);
    }

    /** A bind mount of {@code path} at {@code path}. */
    public record Mount(Path path, boolean readOnly) {
        public Mount {
            if (path == null || !path.isAbsolute()) throw new IllegalArgumentException("a mount must be absolute: " + path);
        }
    }
}
