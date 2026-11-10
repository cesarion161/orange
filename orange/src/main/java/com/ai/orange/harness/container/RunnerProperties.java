package com.ai.orange.harness.container;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where a task's runs execute ({@code orange.runner}).
 *
 * @param isolation {@code process}: an agent is a child process of orange and its Bash is not
 *                  confined (development). {@code container}: every run of a task (the agent, the
 *                  review of its diff, its evidence commands) runs in a container that holds one
 *                  directory of the host and none of orange's environment
 * @param container the container settings; unused in process mode
 */
@ConfigurationProperties(prefix = "orange.runner")
public record RunnerProperties(@DefaultValue("process") Isolation isolation, @DefaultValue Container container) {

    public enum Isolation { PROCESS, CONTAINER }

    public RunnerProperties {
        if (isolation == null) isolation = Isolation.PROCESS;
        if (container == null) container = Container.defaults();
    }

    public boolean contained() {
        return isolation == Isolation.CONTAINER;
    }

    /**
     * @param docker        argv prefix of the Docker CLI
     * @param image         the image every container starts from, built on this host
     *                      ({@code orange/worker/build.sh}); orange never pulls it
     * @param claudeCommand argv prefix that starts Claude Code inside the image; tests point it at a fake
     * @param network       the bridge online containers join; created when missing, without traffic
     *                      between containers. Never the host's network, never another container's
     * @param user          {@code uid[:gid]} inside the container; blank = orange's own, so what a
     *                      run writes is orange's to read. Never root
     * @param home          {@code HOME} inside the container
     * @param memory        {@code --memory} per container, e.g. {@code 6g}; blank = no limit
     * @param cpus          {@code --cpus} per container, e.g. {@code 2}; blank = no limit
     * @param pidsLimit     {@code --pids-limit} per container (it counts threads)
     * @param volumes       extra mounts of online containers, {@code source:target[:ro]}, e.g. a
     *                      named volume for a dependency cache; shared by every run. Never the Docker socket
     */
    public record Container(@DefaultValue("docker") List<String> docker,
                            @DefaultValue(Container.DEFAULT_IMAGE) String image,
                            @DefaultValue("claude") List<String> claudeCommand,
                            @DefaultValue(Container.DEFAULT_NETWORK) String network,
                            @DefaultValue("") String user,
                            @DefaultValue(Container.DEFAULT_HOME) String home,
                            @DefaultValue("") String memory,
                            @DefaultValue("") String cpus,
                            @DefaultValue("4096") int pidsLimit,
                            List<String> volumes) {

        static final String DEFAULT_IMAGE = "orange-worker:local";
        static final String DEFAULT_NETWORK = "orange-runs";
        static final String DEFAULT_HOME = "/home/agent";
        static final int DEFAULT_PIDS_LIMIT = 4096;
        private static final Pattern USER = Pattern.compile("\\d+(:\\d+)?");

        public Container {
            docker = docker == null || docker.isEmpty() ? List.of("docker") : List.copyOf(docker);
            // An empty variable in .env binds as an empty string, which must mean the default.
            image = image == null || image.isBlank() ? DEFAULT_IMAGE : image.strip();
            // The image is the first word docker does not read as an option: it must not look like one.
            if (image.startsWith("-") || image.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("orange.runner.container.image is not an image name: " + image);
            }
            claudeCommand = claudeCommand == null || claudeCommand.isEmpty() ? List.of("claude") : List.copyOf(claudeCommand);
            network = network == null || network.isBlank() ? DEFAULT_NETWORK : network.strip();
            // The host's network is the host; another container's is that container.
            if (network.equalsIgnoreCase("host") || network.toLowerCase().startsWith("container:")) {
                throw new IllegalArgumentException("orange.runner.container.network must be a bridge of orange's own, not "
                        + network);
            }
            user = user == null ? "" : user.strip();
            if (!user.isEmpty()) {
                if (!USER.matcher(user).matches()) {
                    throw new IllegalArgumentException("orange.runner.container.user must be uid or uid:gid in digits: " + user);
                }
                if (new BigInteger(user.split(":")[0]).signum() == 0) {
                    throw new IllegalArgumentException("orange.runner.container.user must not be root: " + user);
                }
            }
            home = home == null || home.isBlank() ? DEFAULT_HOME : home.strip();
            memory = memory == null ? "" : memory.strip();
            cpus = cpus == null ? "" : cpus.strip();
            if (pidsLimit < 1) throw new IllegalArgumentException("orange.runner.container.pids-limit must be positive");
            volumes = volumes == null ? List.of()
                    : volumes.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
            for (String volume : volumes) {
                // Whoever holds the socket is root on the host.
                if (volume.contains("docker.sock")) {
                    throw new IllegalArgumentException("orange.runner.container.volumes must not mount the Docker socket: "
                            + volume);
                }
            }
        }

        /** Every setting at its default. */
        public static Container defaults() {
            return new Container(null, null, null, null, null, null, null, null, DEFAULT_PIDS_LIMIT, null);
        }
    }
}
