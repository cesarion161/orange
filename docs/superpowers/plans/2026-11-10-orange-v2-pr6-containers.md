# v2 PR 6: container isolation

2026-11-10 · Spec: `docs/superpowers/specs/2026-11-02-orange-factory-v2-design.md` §16, §19 item 6 (with §3, §8, §17; project-space spec decision 13, §6.1, §12 V) · Branch: `feat/v2-pr6-containers` (stacked on `feat/project-space-phase-d`)

With `orange.runner.isolation=container`, everything that executes what a task's agent wrote (the
agent itself, the review of its diff, the evidence commands on its head) runs in a container that
holds one directory of the host and none of orange's environment. Process mode stays the default
and is untouched.

## Tasks

- [x] **C1. The container runtime: settings, the docker command, the test image.**
- [x] **C2. Agent runs in containers: the launcher, the clone, the way home.**
- [ ] **C3. Evidence commands, the reaper, the network guard, the loop end to end.**
- [ ] **C4. The worker image, the docs, the live checks' code.**
- [ ] **C5. Live gate** (one real implement run in a container; recorded below).

## Design

### The rule everything follows

No container ever gets write access to a directory in which orange runs git, and orange never
runs git in a directory a container wrote. The only thing that crosses back is one bundle file.

Why it has to be that strict: orange's git reads the repository's `.git/config`, and git executes
what it finds there (`core.fsmonitor` on `git status`, `core.sshCommand` on a push, `diff.external`
on the diff a review is built from). `Git.exec` already switches hooks off; config it cannot
switch off. A worktree's `.git` is a file inside the worktree, so whoever writes the worktree can
replace it with a directory of their own.

### What runs where

| run | process mode (today) | container mode |
|---|---|---|
| `implement`, `ci_fix` | `claude` in the task's worktree | `claude` in a container, on a clone of the task's branch under the run's directory; its commits come home as a bundle |
| `diff_review` of a task | `claude` (Read, Glob, Grep) in the worktree | `claude` in a container, the worktree mounted read-only |
| `verify` of a task | `sh -c` in the worktree | each command in a container, on a clone of the head under the run's directory |
| `integrate` | orange's git in the worktree | the same (git only, in orange's own checkout) |
| `plan`, `brief`, `judge`, a session's `diff_review` and `verify` | host | host, unchanged (ruling 1) |

In code: a run is contained when `orange.runner.isolation=container` and `run.taskId() != null`.

The task's worktree (`orange.worktrees.root/<task>`, a linked worktree of the base repo) stays
what it is: orange's own checkout of `orange/<task>`. In container mode only orange writes it
(the import below, the integrator's rebase), so `PostRunSteps`, `Integrator`, `TaskReactor`,
`RunPreparer` and `WorktreeService` do not change.

### Packages and classes

New package `com.ai.orange.harness.container`:

```java
@ConfigurationProperties(prefix = "orange.runner")
public record RunnerProperties(@DefaultValue("process") Isolation isolation, @DefaultValue Container container) {
    public enum Isolation { PROCESS, CONTAINER }
    public record Container(@DefaultValue("docker") List<String> docker,
                            @DefaultValue("orange-worker:local") String image,
                            @DefaultValue("claude") List<String> claudeCommand,
                            @DefaultValue("orange-runs") String network,
                            @DefaultValue("") String user,          // "uid:gid"; blank = orange's own
                            @DefaultValue("/home/agent") String home,
                            @DefaultValue("") String memory,        // e.g. 6g; blank = no --memory
                            @DefaultValue("") String cpus,          // e.g. 2; blank = no --cpus
                            @DefaultValue("4096") int pidsLimit,
                            List<String> volumes) { … }             // "source:target[:ro]"; null → empty
    public boolean contained() { return isolation == Isolation.CONTAINER; }
}
```

The `Container` constructor refuses, with a message naming the setting: `network` = `host` or
`container:<name>` (another container's network is that container); a `volumes` entry that
mentions `docker.sock`; a `user` that is not `\d+(:\d+)?` or whose uid is 0; an `image` that
starts with `-` or holds a blank (it is the first word of the command line docker does not read
as an option); a `pids-limit` below 1. A blank `image`, `network` or `home` becomes the default
(an empty variable in `.env` binds as an empty string). `Container.defaults()` is every setting at
its default.

```java
/** One command in a container of its own. Every mount has the same path inside and outside. */
public record ContainerSpec(String name, Map<String, String> labels, Path workdir, List<Mount> mounts,
                            Map<String, String> env,        // in the argv as --env NAME=value
                            Map<String, String> secretEnv,  // in the argv as --env NAME; the value only in the docker client's environment
                            boolean online,                 // false = --network none
                            List<String> command) {
    public record Mount(Path path, boolean readOnly) {}             // absolute, like the workdir
}

/** Pure: a ContainerSpec as a docker command line. */
public final class DockerCommand {
    public static final String LABEL_RUN = "orange.run", LABEL_KIND = "orange.kind", LABEL_WORKER = "orange.worker";
    public static String runName(UUID runId);                                   // "orange-run-<runId>"
    public static Map<String, String> labels(UUID runId, String kind, String workerId);
    public static List<String> run(ContainerSpec c, RunnerProperties.Container p, String user);
    public static List<String> remove(List<String> docker, List<String> namesOrIds);   // docker rm -f …
    public static List<String> listByRun(List<String> docker, UUID runId);      // docker ps -aq --filter label=orange.run=<id>
    public static boolean dockerFailed(int exitCode);                           // 125, 126, 127: docker could not start the command
}

/** Thrown when docker itself cannot be used (no binary, no daemon, no network). */
public class ContainerUnavailableException extends IOException { … }

@Component
public class ContainerRuntime {
    @Autowired public ContainerRuntime(RunnerProperties props);                 // host env = System::getenv
    public ContainerRuntime(RunnerProperties props, Supplier<Map<String, String>> hostEnv);
    public boolean enabled();                                                   // props.contained()
    public RunnerProperties.Container settings();
    public String user();                                                       // the setting, else this process's uid:gid
    public ProcessBuilder command(ContainerSpec c) throws ContainerUnavailableException;
    public Result run(ContainerSpec c, Duration timeout) throws ContainerUnavailableException;  // to completion
    public void remove(String name);                                            // docker rm -f; never throws
    public int removeByRun(UUID runId);                                         // by label; never throws; 0 when docker is unreachable
    public List<String> subnets();                                              // the run network's, as last seen
    public record Result(int exitCode, String output) {}                        // output: the last 64 KiB of stdout+stderr
}

/** A loopback URL as a container has to spell it. */
public final class ContainerUrls {
    public static final String HOST = "host.docker.internal";
    public static String fromContainer(String url);   // host localhost | 127.0.0.1 | [::1] | 0.0.0.0 → HOST; anything else unchanged
}
```

`ContainerRuntime` details:

- `user()`: the setting when set; otherwise `new com.sun.security.auth.module.UnixSystem()`'s
  `getUid() + ":" + getGid()`. When `enabled()` and that uid is 0, the constructor throws
  `IllegalStateException("container runs must not be root: run orange as a normal user, or set orange.runner.container.user")`.
- `command(c)`: `ensureNetwork()` when `c.online()`, then a `ProcessBuilder` of
  `DockerCommand.run(c, settings(), user())` whose environment is cleared and set to the docker
  client's own needs, `ClaudeCodeCommand.passThrough(hostEnv, List.of("PATH", "HOME", "DOCKER_HOST",
  "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY", "XDG_RUNTIME_DIR"))`,
  plus `c.secretEnv()`. Nothing else of orange's environment reaches even the docker client.
- `ensureNetwork()` (synchronized): `docker network inspect --format '{{range .IPAM.Config}}{{println .Subnet}}{{end}}' <network>`;
  on a non-zero exit `docker network create --driver bridge --opt com.docker.network.bridge.enable_icc=false --label orange.managed=true <network>`
  and inspect again (whatever the create said: another coordinator on the daemon may have made
  it in between). The subnets it prints are kept for `subnets()`. A missing binary, a first
  inspect that does not answer in 15 s, or a second failure is a `ContainerUnavailableException`
  carrying docker's last stderr line.
- `run(c, timeout)`: starts `command(c)`, closes stdin, merges stderr into stdout, waits; at the
  timeout (or when its thread is interrupted) it calls `remove(c.name())`, destroys the client,
  removes once more (the first removal may have come before the daemon created the container)
  and returns exit code -1. A docker binary that cannot be started is a
  `ContainerUnavailableException` here; from `command(c)` of an offline spec it is the
  `IOException` of whoever starts the `ProcessBuilder`.
- `remove(name)` and `removeByRun(id)` (`listByRun`, then one `docker rm -f` of what it printed)
  get 15 s per docker command, and are not cut short by a pending interrupt of their thread.
- On `ApplicationReadyEvent`, when `enabled()`: `ensureNetwork()` and
  `docker image inspect <image>`; a failure of either is one WARN line each (what is missing and
  `orange/worker/build.sh`), never a failed start.

### The docker command line

`DockerCommand.run` produces exactly this, in this order (an `implement` run shown; `<d>` is
`<run-root>/<run id>`):

```
docker run --rm -i --pull never --init
  --name orange-run-<run id>
  --label orange.kind=implement --label orange.run=<run id> --label orange.worker=<worker id>
  --user <uid>:<gid> --cap-drop ALL --security-opt no-new-privileges --pids-limit 4096
  [--memory <memory>] [--cpus <cpus>]
  --network orange-runs --add-host host.docker.internal:host-gateway        | --network none
  --workdir <d>/workspace
  --mount type=bind,source=<d>/workspace,target=<d>/workspace
  --mount type=bind,source=<d>/claude-config,target=<d>/claude-config
  --mount type=bind,source=<d>/mcp.json,target=<d>/mcp.json,readonly
  [--volume <entry>]…                                  (orange.runner.container.volumes; only when online)
  --env ANTHROPIC_DEFAULT_HAIKU_MODEL=… --env CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
  --env CLAUDE_CONFIG_DIR=<d>/claude-config --env DISABLE_AUTOUPDATER=1
  --env GIT_AUTHOR_EMAIL=… --env GIT_AUTHOR_NAME=orange[bot] --env GIT_COMMITTER_EMAIL=… --env GIT_COMMITTER_NAME=orange[bot]
  --env HOME=/home/agent
  --env CLAUDE_CODE_OAUTH_TOKEN                        (name only)
  orange-worker:local
  claude -p --output-format stream-json --verbose --model … --effort … --session-id <run id>
    --no-session-persistence --setting-sources project,local --strict-mcp-config
    --permission-mode bypassPermissions --append-system-prompt … --tools … --allowedTools …
    --mcp-config <d>/mcp.json
```

Labels, `env` and `secretEnv` are written sorted by name (labels and env from a `TreeMap`), mounts
in the spec's order. A mount path containing a comma, a double quote or a line break is an
`IllegalArgumentException` (`--mount` is one CSV record). So is a variable name that is not
`[A-Za-z_][A-Za-z0-9_]*`: `--env` splits at the first `=`, and a secret's "name" holding one would
put a value in the argv. `--mount`, not `-v`: `-v` creates a missing source as a root-owned
directory, `--mount` fails. Never in the argv: `--privileged`, the Docker socket, a secret's value.

Names: the agent's container `orange-run-<run id>`, a verify run's n-th command
`orange-run-<run id>-c<n>`, the export `orange-run-<run id>-export`. All carry
`orange.run=<run id>`, so one label filter finds everything a run started.

Checked while planning (2026-11-10, Docker 29.7.2 on Docker Desktop, linux/arm64, git 2.54.0;
Claude Code 2.1.322 is what this laptop runs):

- `docker run -i` carries a piped stdin to the command and its end; `--env NAME` takes the value
  from the client's environment; with `--user 501:20` and a bind mount at its own path the files
  are the caller's inside and stay the user's outside; nothing else of the host is there.
- With `--pull never` a missing image ends `docker run` with exit code 125 and
  `No such image: …` on stderr; a command the image lacks ends it with 127.
- `docker rm -f <name>` ends an attached client with exit code 137 within a second, and exits 0
  when there is no such container.
- The network command above creates the bridge, and the inspect format prints its subnet.
- The git steps of the workspace section, by hand: the clone holds only the task's branch and the
  base ref; `git bundle create <f> <start>..HEAD` lists `HEAD` and refuses an empty range; the
  fetch and the reset move a linked worktree's branch.
- While building C1: a `--network none` container still lists the kernel's tunnel devices under
  `/sys/class/net` (Docker Desktop), but has no `eth0` and no route; an online one has two
  `host.docker.internal` lines in `/etc/hosts` (IPv4 and IPv6); `FakeClaude.java` runs on the test
  image's JDK 21; a `@TempDir` (`/var/folders/…`) mounts at its own path.
- While building C2: git in a container bundles from a workspace mounted read-only (it writes
  only the bundle and its lock, in the export directory); the bundle of an amended or longer
  branch fetches into a linked worktree and `reset --hard FETCH_HEAD` moves its branch; a
  container on the run network reaches the coordinator's random test port as
  `host.docker.internal` (MCP answers 200, and 202 for the notification); a missing image prints
  `docker: Error response from daemon: No such image: …` and then, as its last stderr line,
  `Run 'docker run --help' for more information`; the fake starts in a container in about a
  second, an export takes about half a second.
- Not checked: source addresses on a Linux bridge; the installer inside a Linux image; Claude
  Code in a container (C4 and C5).

### The launcher (`harness`)

The choice sits behind the adapter: `HarnessAdapter` and `RunHandle` do not change, and
`WorkerRunner` keeps supervising a `ProcessRunHandle`. For a contained run the process is the
docker client, which carries stdin to the container and its stdout and stderr back, so the prompt
on stdin, stream-json parsing, redaction, the lease, and the budget and timeout kills work as they
do today.

- `harness/Sandbox.java` (new): `public record Sandbox(boolean readOnly, Map<String, String> labels) {}`,
  "the run's process lives in a container that holds its cwd (read-only or not), its Claude config
  directory and its MCP config".
- `RunSpec` gets a 16th component `Sandbox sandbox` (null = a host process). The 14-argument
  constructor stays and passes `List.of(), null`.
- `ProcessRunHandle.start(pb, stdin, runDir, parser, sink, redact, Runnable alsoKill)` (new
  overload; the old one passes null). `kill(reason)`: set the reason, run `alsoKill` (exceptions
  logged), then destroy the process tree as today. When a reason was set, the wait thread runs
  `alsoKill` once more after the process has exited and before the exit is reported (whoever
  waits for the exit reads the workspace next), and a virtual thread runs it a last time 5 s
  later: a kill can arrive while the daemon is still creating the container.
- `ClaudeCodeCommand`: `public static final Set<String> SECRET_ENV = Set.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_AUTH_TOKEN")`.
- `ClaudeCodeAdapter`: the Spring constructor becomes `(HarnessProperties, ContainerRuntime)`; the
  two existing constructors stay and leave the runtime null (all three end in one private
  constructor). `start`:
  - `spec.sandbox() == null`: as today.
  - otherwise (an `IllegalStateException` without a runtime, never a host process instead), with
    the run directory made absolute:
    `env = ClaudeCodeCommand.env(spec, Map.of(), List.of(), configDir)` (nothing of the host's
    environment, not even `PATH`: the image has its own); the `SECRET_ENV` entries move to
    `secretEnv`; `ANTHROPIC_BASE_URL`, when there, becomes `ContainerUrls.fromContainer(…)`;
    `HOME` = `settings().home()`. Mounts: `spec.cwd()` (read-only per the sandbox), `configDir`,
    and `spec.mcpConfig()` read-only when not null. Command:
    `ClaudeCodeCommand.argv(spec, settings().claudeCommand())`. Then
    `ProcessRunHandle.start(containers.command(c), spec.prompt(), runDir, parser::parse, sink, redactor(…), () -> containers.remove(c.name()))`.
- `ToolPolicy.inContainer()`: the same policy with `permissionMode` `bypassPermissions` when its
  tools include `Bash`, otherwise itself (a reviewer keeps `dontAsk`).
- `WorkerMcpUrl.url(boolean contained)`: `contained ? ContainerUrls.fromContainer(url()) : url()`.
  `WorkerMcpConfigWriter.write(Path runDir, String token, boolean contained)`; the two-argument
  form stays and passes false.

### The workspace (`run/RunWorkspaces`)

```java
@Component
public class RunWorkspaces {
    public RunWorkspaces(WorktreeService worktrees, ContainerRuntime containers, RunsProperties runsProps);
    /** <runDir>/workspace: a clone of orange/<taskId> that shares nothing with the base repo. */
    public Path cloneOf(UUID taskId, Path runDir) throws IOException;   // and GitException
    /** Brings what the run committed into the task's worktree. */
    public Home bringHome(Run run, Path workspace, Path runDir, String startSha);
    /** Deletes <runDir>/workspace and <runDir>/export. Never throws. */
    public void discard(Path runDir);
    public enum Home { IMPORTED, NOTHING_NEW, UNREADABLE, CONTAINER_UNAVAILABLE }
}
```

`cloneOf`, all with `Git.run` (so without hooks), before any container has seen the directory:

```
git clone --quiet --no-local --no-tags --single-branch --branch orange/<task> <base repo> <runDir>/workspace     (cwd: the base repo, Git.SLOW)
git remote remove origin                                                                                         (cwd: the clone)
git fetch --quiet --no-tags <base repo> +<ref>:<ref>                                                             (cwd: the clone, Git.SLOW)
```

`<ref>` is `refs/remotes/origin/<base branch>` when `worktrees.hasOrigin()`, else
`refs/heads/<base branch>`: the clone then knows the base under the name the coding prompt uses
(`worktrees.baseRef()`), which a fix run after a conflict rebases onto. `--no-local` sends only
what the branch reaches through git's own transport: no hardlinks into the base repo's object
store, no other task's objects.

`bringHome` (the agent's container has exited by now):

1. `mkdir <runDir>/export`; `containers.run` of this spec, 2 minutes: name
   `orange-run-<run id>-export`, labels of the run with kind `export`, workdir and one read-only
   mount = the workspace, one writable mount = `<runDir>/export`, `env` `HOME`, no secrets,
   `online` false, command `sh -c <script> export <startSha> <runDir>/export/head.bundle` with

   ```sh
   head=$(git rev-parse --verify --quiet HEAD) || exit 3
   [ "$head" = "$1" ] && exit 4
   git bundle create "$2" "$1..HEAD" >/dev/null 2>&1 || exit 5
   ```

   Exit 4 → `NOTHING_NEW`. `DockerCommand.dockerFailed` or a `ContainerUnavailableException` →
   `CONTAINER_UNAVAILABLE`. Any other non-zero exit → `UNREADABLE` (logged with the output).
2. The bundle must be a regular file and not a link (`Files.isRegularFile(b, NOFOLLOW_LINKS)`),
   and the worktree must still be at `startSha`; otherwise `UNREADABLE`.
3. In the task's worktree (`worktrees.resolve(run.taskId())`):
   `git -c transfer.fsckObjects=true fetch --quiet --no-tags <bundle> HEAD` (Git.SLOW), then
   `git reset --quiet --hard FETCH_HEAD`. A `GitException` → `UNREADABLE`. Otherwise `IMPORTED`.

git that reads what the agent left (its `.git`, its config, whatever its links point at) runs only
inside a container with no network; the host parses one regular file as a pack. `reset --hard`
rather than a fast-forward: the branch becomes what the agent's `HEAD` is, as in process mode,
where the agent may amend.

`discard` deletes the two directories without following links; when a delete is refused it tries
once more, giving the owner every right on each directory as it comes to it (build tools leave
read-only trees), then logs. The walk keeps its own stack, so a tree an agent made very deep
cannot overflow orange's.

### `WorkerRunner`

New constructor parameters: `ContainerRuntime containers`, `RunWorkspaces workspaces`. In
`run(Run, PreparedRun, Duration)`:

- `contained = containers.enabled() && run.taskId() != null`;
  `coding = kind is IMPLEMENT or CI_FIX`; the policy becomes `policy.inContainer()` when contained,
  and the run directory is made absolute (a mount needs it, and `--mcp-config` then names the
  same file inside).
- After the credentials, when `contained && coding`: `start = Git.head(prep.cwd())` and
  `cwd = workspaces.cloneOf(run.taskId(), runDir)`; a failure ends the run `workspace_failed`.
- `mcpConfigs.write(runDir, mcpToken, contained)`.
- `sandbox = contained ? new Sandbox(!coding, DockerCommand.labels(run.id(), run.kind().dbValue(), props.workerId())) : null`;
  the `RunSpec` gets `cwd` and the sandbox.
- In the `finally`: `workspaces.discard(runDir)` when `contained && coding`.

In `supervise`:

- `harness.start` throwing `ContainerUnavailableException` ends the run `container_unavailable`
  (caught before the existing `IOException` branch).
- After the "finished elsewhere" check and before the result is built, for a contained coding run
  whose `exit.killReason()` is null, `timeout` or `budget`: `workspaces.bringHome(run, spec.cwd(), runDir, start)`.
  `CONTAINER_UNAVAILABLE` with no other reason makes the reason `container_unavailable`. Nothing
  comes home from a cancelled run, a lost lease or a stopping coordinator, and nothing is asked
  of docker when it could not even start the agent's container (the reason below).
- `failureReason(RunExit, String lastAssistant, boolean contained)`: after the kill reason and the
  success check, `contained && exit.result() == null && DockerCommand.dockerFailed(exit.exitCode())`
  → `container_unavailable`. `RunWatch` keeps the last stderr line that is not docker's pointer
  to its own help (`Run 'docker run --help' …` comes last), and that reason logs it at ERROR with
  the image and `orange/worker/build.sh` named (a missing image says
  `docker: Error response from daemon: No such image: …`).

`FailureReasons.PAUSING` gains `container_unavailable` (open-ended, like `start_failed`: a person
builds the image or starts Docker, then `POST /scheduler/resume`). It does not count against the
task.

### `EvidenceRunner`

New parameters `ContainerRuntime containers`, `RunWorkspaces workspaces` on the Spring constructor
and on the full one; the test constructor (a host environment, no session checks), which
`EvidenceRunnerIT` uses, keeps its signature and passes null for both.

- `verify(run, head)`: the stale and dirty checks stay on the task's worktree. When
  `containers != null && containers.enabled()`: `where = workspaces.cloneOf(run.taskId(), runDir)`
  (`runDir = harnessProps.runRoot().resolve(run.id().toString())`), the commands run there, and a
  `finally` discards it. A session's verify (`verifySession`) is not touched.
- `runCommand` starts through one of two small methods returning
  `record Started(Process process, Runnable kill)`; `live` becomes a set of `Started`:
  - host: today's `sh -c` with the pass-through environment;
  - contained: `containers.command(new ContainerSpec(runName + "-c" + n, labels(run, "verify"), dir, [dir writable], {HOME}, {}, true, ["sh", "-c", command]))`
    with stderr merged; `kill` = `containers.remove(name)` then the client's process tree.
- A contained command that ends with exit code 125, or a `ContainerUnavailableException`, is no
  evidence: the run ends `container_unavailable`, with no row for that command (a private
  exception caught in `execute`, next to `LeaseLost`). 126 and 127 stay evidence here: `sh`
  returns them for the user's own command.

### `RunReaper`

New parameter `ContainerRuntime containers`. In `failOrphans` and in `reapExpired`, before the row
is finished: `if (containers.enabled()) containers.removeByRun(r.id())`. The pid kill stays; for a
contained run the pid is the docker client's.

### The network

Built:

- Every online container is on `orange.runner.container.network` (default `orange-runs`, a bridge
  orange creates with traffic between containers off) and gets
  `--add-host host.docker.internal:host-gateway`. No host network, no socket.
- The worker MCP URL and a gateway's base URL are rewritten for contained runs when they name the
  loopback address; a URL that names anything else is used as it is.
- `config/RunNetworkGuard` (a bean): `boolean fromRunNetwork(String remoteAddr)`, true when the
  address lies in one of `containers.subnets()` (IPv4 and IPv6 CIDR; false when there are none or
  the address does not parse). In `SecurityConfig` a `OncePerRequestFilter` built there (not a
  bean, so Boot does not register it twice), added before `UsernamePasswordAuthenticationFilter`:
  a request from the run network whose `getRequestURI()` is not exactly `WorkerMcpConfig.PATH`
  gets 403 `{"error":"runs reach only /mcp/worker"}`. Equality on the raw URI: any other spelling
  is refused. At start, in container mode with `orange.api.token` blank, the guard logs one WARN.

Not built: an allowlist for the internet (known limits).

### Settings (`application.yaml`, after `harness:`)

```yaml
  runner:
    # process   = an agent is a child process of orange: its Bash is not confined (development).
    # container = every run of a task (the agent, the review of its diff, its evidence commands)
    #             runs in a container that holds one directory and none of orange's environment.
    isolation: ${ORANGE_RUNNER_ISOLATION:process}
    container:
      docker: [docker]
      # Built on this host with orange/worker/build.sh. orange never pulls it.
      image: ${ORANGE_WORKER_IMAGE:orange-worker:local}
      # How the image starts Claude Code.
      claude-command: [claude]
      # Created when missing: a bridge without traffic between containers.
      network: ${ORANGE_RUNNER_NETWORK:orange-runs}
      # uid:gid inside the container. Blank = orange's own, so what a run writes is orange's to read.
      user: ${ORANGE_RUNNER_USER:}
      home: /home/agent
      # Per container, e.g. 6g and 2. Blank = no limit.
      memory: ${ORANGE_RUNNER_MEMORY:}
      cpus: ${ORANGE_RUNNER_CPUS:}
      pids-limit: 4096
      # Extra mounts for agent and evidence containers, "source:target[:ro]", e.g. a named volume
      # for a dependency cache. Whatever is mounted here is shared by every run.
      volumes: []
```

No migration (next free stays V23). `.env.example` gets `ORANGE_WORKER_IMAGE=` and, commented
out, `# ORANGE_RUNNER_ISOLATION=container` (an empty value does not bind to the enum and stops
the start, as with `ORANGE_BRIEF_ENABLED`).

### The image

`orange/worker/Dockerfile`:

```dockerfile
# The image every contained run starts from: Claude Code, git, ripgrep and a user that is not root.
# A repo's toolchain is the base image: any Debian or Ubuntu image that has it (build.sh --base).
ARG BASE_IMAGE=debian:bookworm-slim
FROM ${BASE_IMAGE}
ARG CLAUDE_CODE_VERSION=2.1.322
ARG AGENT_UID=1000
ARG AGENT_GID=1000
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git ripgrep \
 && rm -rf /var/lib/apt/lists/*
# One self-contained binary, pinned; the build fails when the version is not the one asked for.
RUN curl -fsSL https://claude.ai/install.sh | bash -s -- "${CLAUDE_CODE_VERSION}" \
 && install -m 0755 "$(readlink -f /root/.local/bin/claude)" /usr/local/bin/claude \
 && rm -rf /root/.local /root/.claude /root/.claude.json \
 && claude --version | grep -F "${CLAUDE_CODE_VERSION}"
RUN groupadd --non-unique --gid "${AGENT_GID}" agent \
 && useradd --non-unique --uid "${AGENT_UID}" --gid "${AGENT_GID}" --create-home --home-dir /home/agent --shell /bin/bash agent
ENV HOME=/home/agent LANG=C.UTF-8 DISABLE_AUTOUPDATER=1
USER agent
WORKDIR /home/agent
```

`orange/worker/build.sh` (POSIX sh, executable): options `--base <image>` (default
`debian:bookworm-slim`), `--tag <name>` (default `orange-worker:local`), `--claude <version>`
(default `2.1.322`); runs `docker build` in its own directory with `BASE_IMAGE`,
`CLAUDE_CODE_VERSION`, `AGENT_UID=$(id -u)`, `AGENT_GID=$(id -g)`. The agent user gets the uid of
whoever builds, which must be the user orange runs as: build the image on the host that uses it.
A deployment picks its toolchain with `--base` and names the result in `ORANGE_WORKER_IMAGE`.

### Tests (no model, process mode stays the default everywhere else)

The fake runs inside a container from a test image, `orange/src/test/resources/worker-test/Dockerfile`:

```dockerfile
# What orange's container tests run the fake Claude Code in: a JDK (FakeClaude.java is a
# single-file program) and git.
FROM eclipse-temurin:21-jdk-alpine
RUN apk add --no-cache git && mkdir -p /home/agent && chmod 1777 /home/agent
COPY FakeClaude.java /opt/fake-claude/FakeClaude.java
```

`src/test/java/com/ai/orange/harness/container/ContainerTestSupport.java`:

```java
public static final String IMAGE = "orange-worker-test:local";
public static synchronized String image();                 // once per JVM: docker build -q -t IMAGE <temp dir with the Dockerfile and FakeClaude.java from the classpath>
public static void register(DynamicPropertyRegistry r);    // isolation=container, image=image(), claude-command=[java, /opt/fake-claude/FakeClaude.java]
public static RunnerProperties.Container settings();        // the same, for tests without Spring
public static List<String> containersOf(UUID runId);       // docker ps -aq --filter label=orange.run=<id>
public static String docker(String... args);               // runs the CLI, returns stdout, throws on a non-zero exit
```

`FakeClaude.java` gets one scenario, `probe`: for every prompt line `probe: <path>` (indented or
not: the test sends the paths as a feedback item's detail, because a task's worktree path is not
known before the task exists) it writes `<path>=visible` or `<path>=hidden` (`Files.exists`) to
`fake-probe.txt` under `$CLAUDE_CONFIG_DIR`, then does what `implement` does. Every tool call now
also logs `POST <url>` to `fake-mcp.log`, before the replies.

| test | kind | what it proves |
|---|---|---|
| `DockerCommandTest` | unit | the exact argv of a coding run; a secret's value appears nowhere; a read-only mount; `--network none` without `--add-host` and without volumes; limits only when set; never `--privileged`, never a socket; a mount path or a variable name that would split its argument is refused; `dockerFailed` |
| `RunnerPropertiesTest` | unit | defaults (bare, and as the shipped `application.yaml` binds them, also with its variables empty); `host` and `container:` networks, a `docker.sock` volume, a root user and an image that reads as an option are refused |
| `ContainerUrlsTest` | unit | loopback spellings are rewritten, port and path kept; other hosts untouched |
| `RunNetworkGuardTest` | unit | inside and outside a subnet, IPv6, no subnets, an unparsable address |
| `ToolPolicyTest` (2 more) | unit | `inContainer()`: implement and ci_fix get `bypassPermissions`, a review keeps `dontAsk` |
| `ContainerFailureTest` | unit | `failureReason`: exit 125/126/127 with no result is `container_unavailable` only when contained, never when there is a result or a kill reason; the reason pauses open-ended and does not count |
| `ContainerRuntimeIT` | Docker | `run` as the caller's uid with `HOME=/home/agent`, a secret from the client's environment and nothing of the host's; the file it wrote is the caller's; the network exists afterwards (a bridge, icc off) and `subnets()` is not empty; an offline container has no route; the exit code and the end of the output; the timeout removes the container; `removeByRun` removes a run's containers and no other run's; a missing image is exit 125, a missing command 127; a missing docker binary is `ContainerUnavailableException` and never a failed start |
| `ContainerAdapterIT` | Docker | the adapter with a sandbox: Init … Result, exit 0, the fake's commit in the mounted repo by `orange[bot]`, its cwd the repo's own path; `fake-env.json` has the token and `HOME=/home/agent`, and nothing of a host environment that holds `ORANGE_GITHUB_TOKEN` (no `DOCKER_HOST`, `USER`, `TMPDIR`, not the host's `PATH`); the token the agent echoes is redacted; `kill` during `[[fake:hang]]` ends the run and leaves no container, also when it comes before the container exists; a read-only sandbox cannot write its cwd; a missing image is exit 125 with docker's reason on stderr; a sandbox without a runtime is refused |
| `ContainerRunIT` | Spring + Docker | see C2 and C3; also `RunWorkspaces` by itself: what the clone holds, what `bringHome` refuses, what `discard` deletes |
| `RunLoopE2EIT.InContainers` | Spring + Docker | ready → integrated → dependent promoted, all in containers, with an evidence command that fails if it can see a host file |
| `ContainerLiveIT` | live | the real image: see C4 |

The first run builds the test image (pulls `eclipse-temurin:21-jdk-alpine`, installs git: needs
the network once, like the first `postgres:18-alpine`); later runs hit the build cache. Two new
Spring contexts; expected cost about a minute of suite time.

## Task details

Commands run from `orange/`. Every task ends with `./gradlew compileJava compileTestJava` passing.

### C1. The container runtime: settings, the docker command, the test image

Build:

- `src/main/java/com/ai/orange/harness/container/`: `RunnerProperties`, `ContainerSpec`,
  `DockerCommand`, `ContainerUnavailableException`, `ContainerRuntime`, `ContainerUrls`, as in the
  design. `@ConfigurationPropertiesScan` on `OrangeApplication` picks the properties up. Short
  docker commands inside `ContainerRuntime` go through one private helper (argv, timeout →
  exit code, stdout, last stderr line) that uses the same cleared client environment as `command`.
- `src/main/resources/application.yaml`: the `runner:` block.
- `src/test/resources/worker-test/Dockerfile`, `ContainerTestSupport`.

Tests: `DockerCommandTest`, `RunnerPropertiesTest`, `ContainerUrlsTest`, `ContainerRuntimeIT`
(plain JUnit, `new ContainerRuntime(new RunnerProperties(CONTAINER, ContainerTestSupport.settings()))`;
its spec mounts a `@TempDir`, runs `sh -c 'id -u; echo "$HOME"; env'` and asserts the uid is not
0, the home, and that `DOCKER_HOST`, `USER` and `TMPDIR` are absent).

Run: `./gradlew test --tests 'com.ai.orange.harness.container.*'`.

Nothing uses the runtime yet; process mode cannot have changed.

### C2. Agent runs in containers: the launcher, the clone, the way home

Build:

- `harness/Sandbox.java`; `RunSpec` (16th component, 14-argument constructor kept);
  `ProcessRunHandle` (the `alsoKill` overload); `ClaudeCodeCommand.SECRET_ENV`;
  `ClaudeCodeAdapter` (the contained branch; `RunHandle.pid()`'s comment: for a contained run,
  the docker client's).
- `roles/ToolPolicy.inContainer()`; `mcp/WorkerMcpUrl.url(boolean)`;
  `mcp/WorkerMcpConfigWriter.write(Path, String, boolean)`.
- `run/RunWorkspaces`; `run/WorkerRunner` (the 16-argument `RunSpec`, the clone, the sandbox,
  `bringHome`, `failureReason` with `contained`, the last stderr line);
  `run/FailureReasons` (`container_unavailable` in `PAUSING` and in the class comment).
- `src/test/resources/fake-claude/FakeClaude.java`: the `probe` scenario and its line in the
  class comment.

Tests:

- `ToolPolicyTest` (2 more), `ContainerFailureTest`, `ContainerAdapterIT` (modelled on
  `ClaudeCodeAdapterIT`: a `@TempDir` repo, `new ClaudeCodeAdapter(props, new ContainerRuntime(…, hostEnv))`
  with a host environment that holds `ORANGE_GITHUB_TOKEN`, a spec with `new Sandbox(false, labels)`).
- `run/ContainerRunIT` (`@SpringBootTest(RANDOM_PORT)`, `RunTestSupport.register` plus
  `ContainerTestSupport.register`, `orange.runs.timeouts.ci_fix=6s`, a static canary file in a
  temp directory of its own):
  1. an implement run succeeds; the task is `verifying`; the task's worktree is at a head whose
     subject is the fake's commit and holds `orange-fake.txt`; `<run-root>/<id>/workspace` and
     `export` are gone; `containersOf(run)` is empty; `fake-args.json` has
     `"--permission-mode","bypassPermissions"`; the MCP URL the fake logged in `fake-mcp.log`
     answered 200;
  2. `[[fake:probe]]` with probe lines for the canary, the base repo, the task's worktree, the
     worktree root, `System.getProperty("user.home")`, `/var/run/docker.sock` and `.`: all hidden
     but `.`; and no variable of the test JVM's environment has the same name and value in
     `fake-env.json` (`LANG` and `LC_ALL` aside), whose `HOME` is `/home/agent` and which has no
     `DOCKER_HOST`, `USER` or `TMPDIR`;
  3. `[[fake:hang]]` as a `ci_fix` run ends `timeout` and `containersOf(run)` is empty;
  4. `[[fake:no-commit]]` ends `no_commits` (the export said nothing new), and a second implement
     run of a task whose first run committed starts from that commit (`orange-fake.txt` in the
     worktree then has two lines). As built, the first run commits and then reports `failed`, so
     the same test shows that a failed run's commit comes home;
  5. a task's `diff_review` run passes, its evidence row is there, and
     `git status --porcelain` of the worktree is empty; its cwd is the worktree and its permission
     mode `dontAsk`;
  6. (added) `cloneOf`: the clone's refs are the task's branch and the base, it has no remote, no
     alternates and no other task's commit, and its config does not name the base repo;
  7. (added) `bringHome`: a `HEAD` behind its start and a worktree that moved are `UNREADABLE` and
     leave the task's branch alone; the same clone with the right start is `IMPORTED`; an amended
     start commit comes home as the branch's one commit;
  8. (added) `discard` deletes read-only trees, removes a link without following it and leaves
     the run's other files.

  In test 1 the fake's `fake-mcp.log` names `http://host.docker.internal:<port>/mcp/worker` and
  every reply is 200 or 202 (the notification). In test 2 the comparison with the test JVM's
  environment leaves out `LANGUAGE` as well (the image's own locale) and reports names only: a
  value there may be a credential of whoever runs the suite.

Run: `./gradlew test --tests '*ContainerAdapterIT' --tests '*ContainerRunIT' --tests '*ToolPolicyTest' --tests '*ContainerFailureTest' --tests '*WorkerRunnerIT' --tests '*ClaudeCodeAdapterIT'`.

### C3. Evidence commands, the reaper, the network guard, the loop end to end

Build:

- `run/EvidenceRunner` (constructors, `Started`, the contained start, the clone around a task's
  commands, exit 125).
- `run/RunReaper` (`removeByRun` in both sweeps).
- `config/RunNetworkGuard`; `config/SecurityConfig` (the filter, and `RunNetworkGuard` as a
  parameter of `securityFilterChain`).

Tests:

- `RunNetworkGuardTest`.
- `ContainerRunIT`, with `orange.evidence.commands[0]` =
  `test -f hello.txt && test ! -e <canary> && touch built.marker` (kind `tests`):
  6. a verify run of `verifyingTask(…, "hello.txt", …)` passes with a passing `tests` row; the
     task's worktree has no `built.marker`; no container and no clone are left;
  7. a run row owned by `previous-instance`, with a container started by hand
     (`docker run -d --label orange.run=<id> <test image> sleep 600`): `reaper.failOrphans()`
     removes the container and fails the run `coordinator_restart`; the same for an expired lease
     and `reapExpired()`.
- `e2e/RunLoopE2EIT`: `HappyPath`'s body moves into `Loop.readyToIntegrated(String title)`; a
  third nested class `InContainers` registers like `HappyPath` plus `ContainerTestSupport.register`,
  with the evidence command `test -f orange-fake.txt && test ! -e <canary>`, and calls it. `CAP`
  becomes 120 s.

Run: `./gradlew test --tests '*RunLoopE2EIT' --tests '*ContainerRunIT' --tests '*RunReaperIT' --tests '*EvidenceRunnerIT' --tests '*RunNetworkGuardTest'`, then `./gradlew test`.

### C4. The worker image, the docs, the live checks' code

Build:

- `orange/worker/Dockerfile`, `orange/worker/build.sh`. Check by hand: `./worker/build.sh`, then
  `docker run --rm orange-worker:local claude --version` prints `2.1.322 (Claude Code)` and
  `docker run --rm orange-worker:local id -u` prints your uid. If the installer's layout is not
  what the Dockerfile expects, any pinned install that leaves `/usr/local/bin/claude` at that
  version is right; record what was needed under Verified.
- `orange/compose.yaml`: Postgres publishes `127.0.0.1:5432:5432` (ruling 16).
- `orange/.env.example`: the two variables.
- `CLAUDE.md`: the repository layout (`orange/worker/`, `harness/container/`, `RunWorkspaces`);
  under "Running Claude Code" the contained path; the isolation paragraph rewritten (what runs
  where, the rule, what the network does and does not hold); the commands
  (`./worker/build.sh`, the live check); the `orange.runner` knobs; `probe` in the fake's
  scenarios; the test paragraph (Docker CLI on the PATH, the test image).
- `src/test/java/com/ai/orange/e2e/ContainerLiveIT.java` (`@Tag("live")`,
  `@SpringBootTest(RANDOM_PORT)`, container mode with the image from `ORANGE_WORKER_IMAGE` or
  `orange-worker:local`, a throwaway base repo from `RunTestSupport.baseRepo()`; both tests are
  skipped with "build it: orange/worker/build.sh" when `docker image inspect` fails):
  1. `realClaudeInTheImageTakesOrangesCommandLineAndReachesTheWorkerMcp`: no secret, no model
     call. As `WorkerMcpRealClaudeLiveIT` does it (a run row, a token hash, the config writer with
     `contained` true), through the Spring `ClaudeCodeAdapter` with a writable sandbox on an
     empty directory under the run root and an invalid token. Asserts on the init line of
     `stream.jsonl`: `permissionMode` is `bypassPermissions`, the `orange` MCP server is
     `connected`, the tools include `mcp__orange__report_result`, `cwd` is that directory; the
     result is an auth failure; stderr has no `error:`; no container is left.
  2. `oneRealImplementRunInAContainer` (`@EnabledIfEnvironmentVariable CLAUDE_CODE_OAUTH_TOKEN`):
     a task with role `chore` (small tier, low effort) asking for a file `HELLO.md` with one given
     line and a file `probe.txt` holding the output of
     `id -u; test -e <canary> && echo CANARY_VISIBLE || echo CANARY_HIDDEN`, both committed;
     `WorkerRunner.execute` on its implement run. Asserts: the run succeeded with a `done`
     outcome; the task is `verifying`; the worktree's head has both files, `probe.txt` says
     `CANARY_HIDDEN` and a uid that is not 0; `run_events` has `init`, `tool_use` and `result` and
     none contains the token; no container is left.

Run: `./gradlew compileJava compileTestJava`, `./gradlew test`.

### C5. Live gate

`./worker/build.sh`, then `./gradlew liveTest --tests '*ContainerLiveIT'` with
`CLAUDE_CODE_OAUTH_TOKEN` in `orange/.env` (`liveTest` reads it). That is the gate: one real
implement run in a container on a throwaway local repo.

Then once by hand, the whole loop: a throwaway repo (`git init`, one commit, no origin), and
`./gradlew bootRun` with `ORANGE_RUNNER_ISOLATION=container`, `WORKTREES_BASE_REPO` at that repo
(in `orange/.env`, which wins over the shell), `ORANGE_EVIDENCE_COMMANDS_0_KIND=tests` and
`ORANGE_EVIDENCE_COMMANDS_0_COMMAND='test -f HELLO.md'`; one task through `POST /tasks` asking
for `HELLO.md`; watch it reach `integrated` (implement, verify and the diff review each in a
container). Record under "Live check" in this file: the Claude Code version in the image, the
Docker version, the times of the clone, the run and the export, the cost estimate, and anything
that had to change.

## Builder's rulings

1. Only a task's runs are contained (`run.taskId() != null`); `plan`, `brief`, `judge` and a
   session's `diff_review` and `verify` stay on the host (spec §16 says "one `docker run` per
   run"). Reason: project-space decision 13 already places those runs: no tools, an empty
   directory, and for a session's evidence the user's own commands on the user's own code. A
   judge in a container would also make every session stop wait on Docker. The rule is one line
   in `WorkerRunner`; Phase V can widen it.
2. A task's evidence commands run in the container image (spec §16 speaks of agent runs only;
   decision 13 says evidence commands run on the host). Reason: for a task they execute what the
   agent wrote (`./gradlew test` runs its tests). On the host, as orange's user, that is the agent
   outside its container, and the exit would be false.
3. A task's diff review runs in a container too, although it has no Bash. Reason: Claude Code
   loads the project settings of its working directory (`--setting-sources project,local`), hooks
   included, and that directory is what the agent committed.
4. Workspace: a clone per run under the run's directory, and commits back as a bundle made in a
   container (spec §16 offers per-task clones fetched from GitHub, or the base `.git` mounted at
   its absolute path). The base `.git` mounted writable hands the agent orange's git config,
   which orange's git then executes on the host, plus every other branch and the remote's URL;
   mounted read-only, git in a worktree cannot commit. A per-task clone that orange runs git in
   has the same config problem. So the agent's repository is never read by host git: the task's
   worktree stays orange's, and one bundle file crosses. A clone per run, not per task, because
   there is then no state to keep in step: every run starts from the branch.
5. What the agent left uncommitted does not survive its run (in process mode it stays in the
   worktree, and verify reports it as a gap). Commits come home from runs that failed, timed out
   or went over budget, as in process mode; not from cancelled runs.
6. The process/container choice is a `Sandbox` on `RunSpec` and a `ContainerRuntime` behind
   `ClaudeCodeAdapter`; `HarnessAdapter`, `RunHandle` and the supervision in `WorkerRunner` do
   not change. Reason: the docker client is a process with the same stdin, stdout and exit code,
   so the lease, the budget and the timeout need no second implementation.
7. The Docker CLI, not a client library. Reason: no new dependency in the coordinator, a child
   process is what `ProcessRunHandle` supervises, and `--env NAME` keeps a secret's value out of
   every argv.
8. Credentials go as `--env NAME` with the value only in the docker client's environment, not as
   an env file. Reason: a file puts the token on disk; either way the value is in the container's
   config, which orange never inspects and only someone with the Docker socket can read.
9. Every mount has the same absolute path inside and outside. Reason: the adapter's argv and
   environment (`--mcp-config`, `CLAUDE_CONFIG_DIR`, the cwd) hold as they are, and a path in an
   event means the same file on the host. The price is in the known limits.
10. `--user` is orange's own uid and gid, and the image is built per host with that uid (spec:
    "non-root"). Reason: on Linux a bind mount keeps numeric owners, so only the same uid lets
    orange read, import and delete what a run wrote, and lets git accept the directory. A
    coordinator running as root is refused at start.
11. `bypassPermissions` only for `implement` and `ci_fix`; a contained review keeps `dontAsk`.
12. Network (spec: "a Docker network that reaches only LiteLLM and the coordinator's worker
    MCP"): built are a bridge of orange's own without traffic between containers, no host
    network, and a guard that lets the run network call nothing on the coordinator but
    `/mcp/worker`. The internet stays open. Reason: since the spec's sentence was written, runs
    bill to the subscription, so the agent talks to Anthropic directly, and implement and verify
    fetch dependencies; an allowlist is an egress proxy plus a list per toolchain, a PR of its
    own.
13. A missing image, a stopped daemon or a missing `docker` is `container_unavailable`: not
    counted, and the scheduler pauses until resumed. `--pull never`: orange never pulls an image
    by itself, so a typo in the setting cannot run someone else's image.
14. Containers are removed by the `orange.run` label of run rows this database calls orphaned or
    expired, not "every orange container" (spec: "kills containers by label on reap or
    restart"). Reason: two coordinators can share a Docker daemon (the test suite next to a dev
    instance); the run id is the only name that is surely ours.
15. No migration. A run's containers are found by a name and a label derived from its id;
    `runs.pid` holds the docker client's pid.
16. Beyond the spec: `--cap-drop ALL`, `no-new-privileges`, `--init`, a pids limit, optional
    memory and cpu limits, `volumes` for a dependency cache (without one, every run downloads its
    toolchain's dependencies again), and Postgres in `compose.yaml` published on the loopback
    address only, because on Linux a container reaches whatever the host serves on all
    interfaces.
17. A repo's "toolchain layer" (spec §16) is the image the worker image is built on
    (`build.sh --base`), and one image per deployment (`ORANGE_WORKER_IMAGE`). The image is built
    by a script, not by gradle: it belongs to the host that runs it (its uid), not to the jar.
18. The tests' image holds a JDK and the fake, not Claude Code, and is built by the suite with
    the Docker CLI. The real image is checked only by the live tests.
19. Deferred: an egress allowlist; an image per project; the coordinator itself in a container;
    rootless Docker and user namespaces; Docker for a run's own tests; containers for `plan`,
    `brief` and `judge`; the Playwright image (PR 8); another harness in the container (PR 9).

## Known limits

- The internet is open to every online container: a run can send what it holds (the repository,
  its own subscription token or virtual key) anywhere. On a cloud host the instance metadata
  address is reachable too unless the host's firewall blocks it.
- The subscription token is in the agent's environment, as in process mode: the agent can read
  it. It is redacted from everything orange stores. Anyone with access to the Docker socket can
  read it from the running container's config.
- On Linux a container reaches every service the host serves on all interfaces. The coordinator
  has to listen where the run network reaches it (the default does), so keep its port closed to
  the outside as before; the guard keeps containers to `/mcp/worker`.
- On Docker Desktop (macOS) a container reaches every service on the Mac's loopback address, and
  its requests arrive from 127.0.0.1, so the guard cannot tell them apart: a contained agent can
  call orange's whole open API there, Postgres included. Container mode on the laptop is for
  development and the live gate; set `ORANGE_API_TOKEN`. Checked on this laptop.
- The guard on Linux rests on Docker's routing (a container's request arrives from the run
  network's subnet). A unit test covers the rule; the routing is to be checked on the VPS in
  Phase V.
- Evidence commands that need Docker cannot run: no socket in the container. orange's own
  `./gradlew test` (Testcontainers) is such a command.
- One image per deployment: tasks on two repos with different toolchains need one image holding
  both.
- The image must be built for the uid orange runs as, on that host. With another uid the agent
  user has no passwd entry and no writable home; whether Claude Code runs then is not checked.
- Run root and worktree root must be paths the Docker daemon sees as orange does: under a shared
  folder on Docker Desktop; the same paths if the coordinator ever runs in a container itself
  (not supported here). The paths are visible inside the container, the user's home directory
  name among them unless `ORANGE_RUN_ROOT` and `WORKTREES_ROOT` point elsewhere.
- A clone per coding run and per verify run: git packs the task's branch each time, and nothing
  is cached between runs unless `volumes` says so. A volume shared by runs lets one run leave
  something for the next.
- A run that moves its `HEAD` behind where it started, or leaves a repository git cannot read,
  brings nothing home; if it reported `done`, that is `no_commits`.
- A kill that arrives while the daemon is still creating the container is retried twice (at the
  client's exit and 5 s later). A container that outlives that, or whose run row is gone, runs
  until its command ends; `--rm` then removes it. There is no timer inside the container.
- A coordinator restarted in process mode does not remove the containers of runs it started in
  container mode.
- `container_unavailable` pauses everything, a session's judge included, as a missing `claude`
  does today.
- A reviewer in a container reads the worktree as orange checked it out; it cannot run git there
  (the worktree's `.git` points at a path that is not mounted), and needs none.
- The gateway path in containers (the rewritten base URL) is unit-tested only; no gateway run is
  made (no API spend).
- The pids limit counts threads; a build that needs more than 4096 has to raise it.
- The first test run needs the network to build the test image, and the Docker CLI on the PATH.
- Not checked before C4: the installer's layout in a Linux image, and Claude Code under
  `bypassPermissions` as a non-root user in a container.

## Review

## Verified
