# v2 PR 6: container isolation

2026-11-10 · Spec: `docs/superpowers/specs/2026-11-02-orange-factory-v2-design.md` §16, §19 item 6 (with §3, §8, §17; project-space spec decision 13, §6.1, §12 V) · Branch: `feat/v2-pr6-containers` (stacked on `feat/project-space-phase-d`)

With `orange.runner.isolation=container`, everything that executes what a task's agent wrote (the
agent itself, the review of its diff, the evidence commands on its head) runs in a container that
holds one directory of the host and none of orange's environment. Process mode stays the default
and is untouched.

## Tasks

- [x] **C1. The container runtime: settings, the docker command, the test image.**
- [x] **C2. Agent runs in containers: the launcher, the clone, the way home.**
- [x] **C3. Evidence commands, the reaper, the network guard, the loop end to end.**
- [x] **C4. The worker image, the docs, the live checks' code.**
- [x] **C5. Live gate** (one real implement run in a container; recorded under "Live check" at
  the end).

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
    public int removeByRun(UUID runId);                                         // by label; never throws; -1 when docker cannot say they are gone (review)
    public List<String> subnets();                                              // the run network's, as last seen
    public boolean networkSeen();                                               // whether docker has answered for the network since the start (review)
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
  and returns exit code -1. When the client ended by itself the container is removed once as well
  (review: a client can die without its container). A docker binary that cannot be started is a
  `ContainerUnavailableException` here; from `command(c)` of an offline spec it is the
  `IOException` of whoever starts the `ProcessBuilder`.
- `remove(name)` and `removeByRun(id)` (`listByRun`, then one `docker rm -f` of what it printed)
  get 15 s per docker command, and are not cut short by a pending interrupt of their thread.
  As built after the review, `removeByRun` answers -1 when the list or the removal failed and a
  second list does not come back empty: its callers delete a run's clone only on 0 or more.
- At the start, when `enabled()`: `ensureNetwork()` in a `@PostConstruct` (review: before the web
  server takes a request, so the guard knows the subnets from the first one; while it fails it is
  tried again every 30 s) and, on `ApplicationReadyEvent`, `docker image inspect <image>`; a
  failure of either is one WARN line each (what is missing and `orange/worker/build.sh`), never a
  failed start.

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
- While building C3: a contained evidence command takes about a second and a half with its clone
  (the fake's JVM is not in it); the whole loop in containers (implement, verify, diff review,
  integrate, the dependent promoted) takes about seven seconds with the fake; `docker rm -f` of a
  command's container ends its client at once, and a second removal of a container that is gone
  exits 0; `InetAddress.ofLiteral` reads `172.18.0` and a bare number as IPv4 addresses, so the
  guard asks for a dotted quad first; a `MockHttpServletRequest` with a remote address in the run
  network's subnet goes through the real `springSecurityFilterChain` and is refused there.
- While building C4: the installer's layout in `debian:bookworm-slim` and in `ubuntu:24.04`
  (linux/arm64) is what the Dockerfile expects (`/root/.local/bin/claude` links to one binary of
  about 340 MB); the image is about 780 MB on disk, built in about a minute, and
  `claude --version` in it prints `2.1.322 (Claude Code)`, `id -u` the builder's uid (501 here,
  with gid 20, which Debian calls `dialout`; `--non-unique` takes both). Claude Code 2.1.322 in
  the image, as uid 501 with `--cap-drop ALL` and `no-new-privileges`, accepts orange's whole
  command line with `--permission-mode bypassPermissions`, connects to the coordinator's
  `/mcp/worker` as `host.docker.internal` and lists the run's tools; an invalid token ends it
  with `Failed to authenticate. API Error: 401 …` after two `api_retry` events, in about four
  seconds, with an empty stderr. The image's git (2.39.5 on bookworm) commits in a clone made by
  the host's git 2.54.0 and bundles from it when it is mounted read-only; the host fetches that
  bundle. An empty `ORANGE_RUNNER_ISOLATION` binds as `process`: Spring reads an empty string as
  no enum constant, and the default steps in.
- While building C5: a coordinator started by hand (`bootTestRun`, the shipped
  `application.yaml`) takes container mode, the image, the image's Claude command and one
  evidence command from environment variables alone (`ORANGE_RUNNER_ISOLATION`,
  `ORANGE_WORKER_IMAGE`, `ORANGE_RUNNER_CONTAINER_CLAUDECOMMAND_0` and `_1`,
  `ORANGE_EVIDENCE_COMMANDS_0_KIND` and `_COMMAND`); a run root and a worktree root under
  `/private/tmp` mount at their own paths (Docker Desktop shares it); with the fake, the clone
  takes about 0.15 s and the export with its import about 0.4 s.
- Not checked: source addresses on a Linux bridge. A model call from inside the container was
  checked by the live gate of C5 ("Live check" at the end).

### The launcher (`harness`)

The choice sits behind the adapter: `HarnessAdapter` and `RunHandle` do not change, and
`WorkerRunner` keeps supervising a `ProcessRunHandle`. For a contained run the process is the
docker client, which carries stdin to the container and its stdout and stderr back, so the prompt
on stdin, stream-json parsing, redaction, the lease, and the budget and timeout kills work as they
do today.

- `harness/Sandbox.java` (new): `public record Sandbox(boolean readOnly, Map<String, String> labels) {}`,
  "the run's process lives in a container that holds its cwd (read-only or not), its Claude config
  directory and its MCP config".
- `RunSpec` gets a 16th component `Sandbox sandbox` (null = a host process) and, after the
  review, a 17th, `boolean projectSettings` (ruling 20). The 14-argument constructor stays and
  passes `List.of(), null, true`.
- `ProcessRunHandle.start(pb, stdin, runDir, parser, sink, redact, Runnable alsoKill)` (new
  overload; the old one passes null). `kill(reason)`: set the reason, run `alsoKill` (exceptions
  logged), then destroy the process tree as today. When a reason was set, the wait thread runs
  `alsoKill` once more after the process has exited and before the exit is reported (whoever
  waits for the exit reads the workspace next), and a virtual thread runs it a last time 5 s
  later: a kill can arrive while the daemon is still creating the container. As built after the
  review, the wait thread also runs `alsoKill` once when no reason was set: a docker client that
  is killed or loses its stdout ends without its container (ruling 21).
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
- After the review: `ToolPolicy` has a fifth component `projectSettings`, false for `diff_review`
  (a task's and a session's) and true otherwise, and `ClaudeCodeCommand.argv` writes
  `--setting-sources user` instead of `project,local` when the spec's is false (ruling 20).
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
cannot overflow orange's. As built after the review there is `discard(UUID runId, Path runDir)`
for a run's own directories: `containers.removeByRun(runId)` first, and on -1 nothing is deleted
(ruling 22). The plain `discard(runDir)` is for directories no container holds, and
`RunRetention` deletes a run's directory with the same walk (it could not delete what a build
left read-only).

### `WorkerRunner`

New constructor parameters: `ContainerRuntime containers`, `RunWorkspaces workspaces`. In
`run(Run, PreparedRun, Duration)`:

- `contained = containers.enabled() && run.taskId() != null`;
  `coding = kind is IMPLEMENT or CI_FIX`; the policy becomes `policy.inContainer()` when contained,
  and the run directory is made absolute (a mount needs it, and `--mcp-config` then names the
  same file inside).
- After the credentials, when `contained && coding`: `start = Git.head(prep.cwd())` and
  `cwd = workspaces.cloneOf(run.taskId(), runDir)`; a failure ends the run `workspace_failed`.
  The clone's time is logged at INFO (`cloned the task's branch for its container in … ms`).
- `mcpConfigs.write(runDir, mcpToken, contained)`.
- `sandbox = contained ? new Sandbox(!coding, DockerCommand.labels(run.id(), run.kind().dbValue(), props.workerId())) : null`;
  the `RunSpec` gets `cwd` and the sandbox.
- In the `finally`: `workspaces.discard(run.id(), runDir)` when `contained && coding` (the plan
  had `discard(runDir)`; see the workspace section).

In `supervise`:

- `harness.start` throwing `ContainerUnavailableException` ends the run `container_unavailable`
  (caught before the existing `IOException` branch).
- After the "finished elsewhere" check and before the result is built, for a contained coding run
  whose `exit.killReason()` is null, `timeout` or `budget`: `workspaces.bringHome(run, spec.cwd(), runDir, start)`.
  `CONTAINER_UNAVAILABLE` with no other reason makes the reason `container_unavailable`. Nothing
  comes home from a cancelled run, a lost lease or a stopping coordinator, and nothing is asked
  of docker when it could not even start the agent's container (the reason below). The INFO
  line `its commits: imported (… ms)` carries the time of the export and the import. As built
  after the review: `UNREADABLE` with a `done` report and no other reason makes the reason
  `no_commits`, so `afterCoding` never sends an earlier attempt's head to verification as this
  run's work.
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
  `containers != null && containers.enabled()` and there is at least one command:
  `where = workspaces.cloneOf(run.taskId(), runDir)`
  (`runDir = harnessProps.runRoot().resolve(run.id().toString())`, made absolute), the commands
  run there, and a `finally` discards it. The clone's head must be the queued head (the worktree
  is on the task's branch, so it is; anything else ends the run `workspace_failed`), and the lease
  is renewed once after the clone. A session's verify (`verifySession`) is not touched.
- `runCommand(run, dir, command, timeout, container)` (`container` = the container's name, null
  on the host) starts through one of two small methods returning
  `record Started(Process process, Runnable kill)`; `live` becomes a set of `Started`:
  - host: today's `sh -c` with the pass-through environment;
  - contained: `containers.command(new ContainerSpec(runName + "-c" + n, labels(run, "verify"), dir, [dir writable], {HOME}, {}, true, ["sh", "-c", command]))`
    with stderr merged; `kill` = `containers.remove(name)` then the client's process tree. A
    contained command that was killed (its timeout, a lost lease, a shutdown) has its container
    removed once more after the client has gone, at most 5 s later: the kill may have come before
    the daemon created the container. As built after the review, a command that ended without a
    kill has its container removed once too, and before its result is recorded the lease is
    renewed: a lease that is gone (the reaper took it, and the container with it) ends the run
    without a row for the command (ruling 23).
- A contained command that ends with exit code 125, or whose docker client cannot be started (a
  `ContainerUnavailableException` or any other `IOException`), is no evidence: the run ends
  `container_unavailable`, with no row for that command and none for the commands after it (a
  private exception caught in `execute`, next to `LeaseLost`, logged at ERROR with docker's last
  line and the image). 126 and 127 stay evidence here: `sh` returns them for the user's own
  command.

### `RunReaper`

New parameter `ContainerRuntime containers`. In `failOrphans` (after the pid kill) and in
`reapExpired`, before the row is finished: `if (containers.enabled()) containers.removeByRun(r.id())`,
with one WARN line when something was removed. The pid kill stays; for a contained run the pid is
the docker client's.

As built after the review (two more parameters, `RunWorkspaces` and `HarnessProperties`):
`reapExpired` finishes the row first and removes the containers after (ruling 23), and both
paths then discard `<run-root>/<run id>/workspace` and `export` unless `removeByRun` answered -1:
nobody else deletes the clone of a run whose executor is gone.

### The network

Built:

- Every online container is on `orange.runner.container.network` (default `orange-runs`, a bridge
  orange creates with traffic between containers off) and gets
  `--add-host host.docker.internal:host-gateway`. No host network, no socket.
- The worker MCP URL and a gateway's base URL are rewritten for contained runs when they name the
  loopback address; a URL that names anything else is used as it is.
- `config/RunNetworkGuard` (a bean; a second constructor takes a `Supplier<List<String>>` of
  subnets for tests): `boolean fromRunNetwork(String remoteAddr)`, true when the address lies in
  one of `containers.subnets()` (IPv4 and IPv6 CIDR; false in process mode, when there are none or
  when the address does not parse), and `boolean refuses(HttpServletRequest)`: from the run
  network, and `getRequestURI()` is not exactly `WorkerMcpConfig.PATH`. Addresses are parsed as
  literals only (`InetAddress.ofLiteral`, never a name lookup), an IPv4 address must be a dotted
  quad, and an IPv4-mapped IPv6 address counts as its IPv4 address. A subnet that is not CIDR
  notation is left out with a WARN. In `SecurityConfig` a `OncePerRequestFilter` built there (not
  a bean, so Boot does not register it twice), added before `UsernamePasswordAuthenticationFilter`
  and ahead of the HMAC filter: a request the guard refuses gets 403
  `{"error":"runs reach only /mcp/worker"}`. Equality on the raw URI: any other spelling is
  refused. The address is the connection's (`getRemoteAddr()`); orange sets no
  `server.forward-headers-strategy`, and must not without revisiting this. At start, in container
  mode with `orange.api.token` blank, the guard logs one WARN.
- As built after the review: the supplier answers null while container mode has not seen the
  network (`!containers.networkSeen()`), and the guard then refuses everything but
  `/mcp/worker` to every address that is not the loopback address, with 403
  `{"error":"container mode, and Docker has not said yet where runs call from: …"}`
  (`refusal(request)` gives the filter the message; `refuses` stays). Ruling 24.

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

No migration (next free stays V23). `.env.example` gets `ORANGE_RUNNER_ISOLATION=` and
`ORANGE_WORKER_IMAGE=`, both blank: an empty isolation binds as `process` (checked in C4 and kept
as an assertion in `RunnerPropertiesTest`; the plan first had it commented out, expecting an empty
value to stop the start as `ORANGE_BRIEF_ENABLED` does).

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
As built, the script also refuses to run as root (the image's user would be root, which orange
refuses at start), answers an unknown option with its usage and exit code 2, and ends with one
line naming the tag, the version, the base and the uid.

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
also logs `POST <url>` to `fake-mcp.log`, before the replies. Two more after the review:
`commit-hang` (implements, writes `fake-committed.txt`, then hangs without reporting) and
`rewind` (resets its branch to the commit before its start and reports `done`).

| test | kind | what it proves |
|---|---|---|
| `DockerCommandTest` | unit | the exact argv of a coding run; a secret's value appears nowhere; a read-only mount; `--network none` without `--add-host` and without volumes; limits only when set; never `--privileged`, never a socket; a mount path or a variable name that would split its argument is refused; `dockerFailed` |
| `RunnerPropertiesTest` | unit | defaults (bare, and as the shipped `application.yaml` binds them, also with its variables empty); `host` and `container:` networks, a `docker.sock` volume, a root user and an image that reads as an option are refused |
| `ContainerUrlsTest` | unit | loopback spellings are rewritten, port and path kept; other hosts untouched |
| `RunNetworkGuardTest` | unit | inside and outside a subnet, a prefix that ends inside a byte, IPv6, an IPv4-mapped address, no subnets, an unparsable address or subnet, subnets that change; only `/mcp/worker` for a run, every other spelling refused, everyone else untouched; (review) while the network is unknown only the loopback address and `/mcp/worker` get through |
| `ToolPolicyTest` (2 more) | unit | `inContainer()`: implement and ci_fix get `bypassPermissions`, a review keeps `dontAsk` |
| `ContainerFailureTest` | unit | `failureReason`: exit 125/126/127 with no result is `container_unavailable` only when contained, never when there is a result or a kill reason; the reason pauses open-ended and does not count |
| `ContainerRuntimeIT` | Docker | `run` as the caller's uid with `HOME=/home/agent`, a secret from the client's environment and nothing of the host's; the file it wrote is the caller's; the network exists afterwards (a bridge, icc off) and `subnets()` is not empty; an offline container has no route; the exit code and the end of the output; the timeout removes the container; `removeByRun` removes a run's containers and no other run's; a missing image is exit 125, a missing command 127; a missing docker binary is `ContainerUnavailableException` and never a failed start; (review) `removeByRun` is -1 without docker; the network is known after the `@PostConstruct` look; `run` removes the container of a client that was SIGKILLed |
| `ContainerAdapterIT` | Docker | the adapter with a sandbox: Init … Result, exit 0, the fake's commit in the mounted repo by `orange[bot]`, its cwd the repo's own path; `fake-env.json` has the token and `HOME=/home/agent`, and nothing of a host environment that holds `ORANGE_GITHUB_TOKEN` (no `DOCKER_HOST`, `USER`, `TMPDIR`, not the host's `PATH`); the token the agent echoes is redacted; `kill` during `[[fake:hang]]` ends the run and leaves no container, also when it comes before the container exists; a read-only sandbox cannot write its cwd; a missing image is exit 125 with docker's reason on stderr; a sandbox without a runtime is refused; (review) a client that is SIGKILLed leaves no container when its exit is reported |
| `ContainerRunIT` | Spring + Docker | see C2 and C3; also `RunWorkspaces` by itself (what the clone holds, what `bringHome` refuses, what `discard` deletes) and the security chain with a request from the run network's subnet; (review) a review's `--setting-sources user`; a commit comes home after a timeout and not after a cancel; an agent's and a command's container do not outlive a SIGKILLed client; `done` with commits that do not come home is `no_commits`; an agent without its image is `container_unavailable`, pauses and counts nothing; a clone is discarded only when docker says the run's containers are gone; a lease the reaper takes from a live verify run records no evidence; the reaper discards an orphan's clone |
| `RunLoopE2EIT.InContainers` | Spring + Docker | ready → integrated → dependent promoted, all in containers, with an evidence command that fails if it can see a host file |
| `ContainerLiveIT` | live | the real image: see C4; (review) a `SessionStart` hook in the reviewed tree's `.claude/settings.json` runs with `project,local` and does not with a review's `user` (invalid token) |

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
  `test -f hello.txt && test ! -e <canary> && touch built.marker` (kind `tests`); the numbers go
  on from C2's eight:
  9. a verify run of `verifyingTask(…, "hello.txt", …)` passes with a passing `tests` row; the
     task's worktree has no `built.marker`; no container and no clone are left;
  10. a run row owned by `previous-instance`, with two containers started by hand
      (`docker run -d --rm --network none --label orange.run=<id> <test image> sleep 600`):
      `reaper.failOrphans()` removes them and fails the run `coordinator_restart`; the same for
      an expired lease and `reapExpired()` (two tests). In both, the container of a run this
      coordinator still supervises stays;
  11. (added, with an `EvidenceRunner` built by hand around its own commands) a command's uid is
      not 0, its cwd is the clone, its `HOME` is `/home/agent` and no variable of the test JVM
      reaches it with its value; exit 3 and `sh`'s own 127 are gaps with evidence rows, and the
      task goes back counted;
  12. (added) a command that hangs is a gap at its timeout and leaves no container; `shutdown()`
      ends a running command `coordinator_restart`, with no evidence and no container;
  13. (added) a runtime with a missing image, and one with a missing docker binary: the run ends
      `container_unavailable`, with no evidence row and nothing counted, the task stays
      `verifying`, and the scheduler is paused open-ended;
  14. (added) the application's security chain (`springSecurityFilterChain`) with a request whose
      remote address lies in the run network's subnet: `/tasks`, `/scheduler/resume`,
      `/sessions`, `/mcp/steering`, `/actuator/health` and `/mcp/worker/` get 403 with the guard's
      body and go no further; `/mcp/worker` passes, and so does `/tasks` from 127.0.0.1.
- `e2e/RunLoopE2EIT`: `HappyPath`'s body moves into `Loop.readyToIntegrated(String title)` (it
  returns A, integrated); a third nested class `InContainers` registers like `HappyPath` plus
  `ContainerTestSupport.register`, with the evidence command
  `test -f orange-fake.txt && test ! -e <canary>`, calls it, and then asserts that none of A's
  runs has a container left. `CAP` becomes 120 s.

Run: `./gradlew test --tests '*RunLoopE2EIT' --tests '*ContainerRunIT' --tests '*RunReaperIT' --tests '*EvidenceRunnerIT' --tests '*RunNetworkGuardTest'`, then `./gradlew test`.

### C4. The worker image, the docs, the live checks' code

Build:

- `orange/worker/Dockerfile`, `orange/worker/build.sh`. Check by hand: `./worker/build.sh`, then
  `docker run --rm orange-worker:local claude --version` prints `2.1.322 (Claude Code)` and
  `docker run --rm orange-worker:local id -u` prints your uid. If the installer's layout is not
  what the Dockerfile expects, any pinned install that leaves `/usr/local/bin/claude` at that
  version is right; record what was needed under Verified.
- `orange/compose.yaml`: Postgres publishes `127.0.0.1:5432:5432` (ruling 16).
- `orange/.env.example`: the two variables, both blank (see Settings).
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
     result is an auth failure; stderr has no `error:`; no container is left. As built, the
     spec's run id is the run row's, so `mcp.json`, `claude-config` and the `workspace` directory
     lie in one run directory as `WorkerRunner` lays them out, and the policy is
     `ToolPolicy.forKind(IMPLEMENT).inContainer()`. It also asserts that `Bash` is among the
     tools and `mcp__orange__submit_review` is not, that only built-in plugins load, that no tool
     was used, that the run has no outcome and that the directory stayed empty.
  2. `oneRealImplementRunInAContainer` (`@EnabledIfEnvironmentVariable CLAUDE_CODE_OAUTH_TOKEN`):
     a task with role `chore` (small tier, low effort) asking for a file `HELLO.md` with one given
     line and a file `probe.txt` holding the output of
     `id -u; test -e <canary> && echo CANARY_VISIBLE || echo CANARY_HIDDEN`, both committed;
     `WorkerRunner.execute` on its implement run. Asserts: the run succeeded with a `done`
     outcome; the task is `verifying`; the worktree's head has both files, `probe.txt` says
     `CANARY_HIDDEN` and a uid that is not 0; `run_events` has `init`, `tool_use` and `result` and
     none contains the token; no container is left. As built, it also asserts that the uid is
     the one of `ContainerRuntime.user()`, that the worktree is clean on the task's branch at the
     recorded head, that the token is in neither `stream.jsonl` nor `stderr.log`, that the init
     line has `bypassPermissions` and the clone as its cwd, and that the clone, the export and
     `mcp.json` are gone. It prints the image, the model, the cost estimate and the seconds the
     run took, for the record C5 asks for.

  The class sets `orange.mcp.worker-url` to blank (this coordinator's own port, whatever
  `orange/.env` says about a deployment's) and the implement timeout to 10 minutes. Test 1 was
  run while building C4 (it needs no secret); test 2 was only compiled.

Run: `./gradlew compileJava compileTestJava`, `./gradlew test`.

### C5. Live gate

`./worker/build.sh`, then `./gradlew liveTest --tests '*ContainerLiveIT'` with
`CLAUDE_CODE_OAUTH_TOKEN` in `orange/.env` (`liveTest` reads it). That is the gate: one real
implement run in a container on a throwaway local repo. The test prints the image, the model,
the cost estimate and the seconds; the log has the clone's time
(`cloned the task's branch for its container in … ms`) and the export's
(`its commits: imported (… ms)`).

Then once by hand, the whole loop: a throwaway repo (`git init`, one commit, no origin), and
`./gradlew bootRun` with `ORANGE_RUNNER_ISOLATION=container`, `WORKTREES_BASE_REPO` at that repo
(in `orange/.env`, which wins over the shell), `ORANGE_EVIDENCE_COMMANDS_0_KIND=tests` and
`ORANGE_EVIDENCE_COMMANDS_0_COMMAND='test -f HELLO.md'`; one task through `POST /tasks` asking
for `HELLO.md`; watch it reach `integrated` (implement, verify and the diff review each in a
container). Record under "Live check" in this file: the Claude Code version in the image, the
Docker version, the times of the clone, the run and the export, the cost estimate, and anything
that had to change.

Two things to know before that hand run, found while rehearsing it:

- `bootRun` works on the compose Postgres, which is the development database: its run loop takes
  up whatever tasks are waiting there. `./gradlew bootTestRun` starts a Postgres of its own
  instead, but reads no `orange/.env` (the variables have to be in the shell) and the test
  configuration on its classpath switches the loop off, so it also needs
  `ORANGE_RUNS_LOOPENABLED=true`.
- Set `ORANGE_API_TOKEN` for it: on Docker Desktop a container reaches the whole open API (known
  limits), and orange says so in a WARN at start.

As built (2026-11-10): the engineer of C5 worked under the instruction to make no model call and
to put no real credential anywhere, so the gate itself is still open. Done instead, and recorded
under Verified: the image rebuilt and checked, `ContainerLiveIT` without a token (the
invalid-token test passes, the real run is skipped), the hand loop above with the suite's fake in
the test image in place of Claude Code, the whole suite, and the two log lines that give the
clone's and the export's time.

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
20. (Review.) A `diff_review` loads no settings of the tree it reads: `--setting-sources user`,
    with the run's own empty `CLAUDE_CONFIG_DIR`, in container mode and in process mode. Reason:
    Claude Code runs a project's hooks without asking, the tree is what the reviewed agent
    committed, and a hook there reads the reviewer's `mcp.json` (mounted next to the config
    directory, and in the argv) and can post `submit_review` itself; the last report wins. This
    amends ruling 3's reason, not its rule: the review stays in a container, because the
    reviewer's own Read still opens whatever a prompt in that tree talks it into, and there it
    finds one read-only directory. `recordOutcome` stays "the last report wins": the first
    report would be the hook's, at `SessionStart`.
21. (Review.) The end of a docker client is not the end of its container, so the container is
    removed at every end of the client, not only after orange's own kill: in `ProcessRunHandle`
    before the exit is reported, in `EvidenceRunner.runCommand` and in `ContainerRuntime.run`.
    `docker rm -f` of a container that is gone exits 0 in about 20 ms.
22. (Review.) A run's clone is deleted only when docker says that none of the run's containers
    is left (`removeByRun` ≥ 0, which removes what is there first); otherwise it stays until
    the run's directory goes. Reason: the delete walks by path, and a container that still
    holds the clone can turn a directory into a link between two looks. The price: with docker
    gone (no binary, no daemon) a clone stays for the retention time.
23. (Review.) The reaper finishes an expired run's row before it removes the run's containers,
    and a contained evidence command's result is recorded only under a lease that still renews.
    Reason: a lease can expire under a live supervisor (the database away, a laptop asleep),
    and the supervisor must find the run ended, not a docker client with exit 137.
24. (Review.) While container mode does not know the run network's subnets, the guard lets
    through only the loopback address and `/mcp/worker`. The network is looked up before the
    web server starts and again every 30 s while Docker does not answer. Reason: a container
    that outlived the previous coordinator may be calling from the first request on. The
    price: with Docker down at the start, nobody but the host itself reaches orange's API (a
    node's session reporter included) until Docker answers.

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
- An evidence command of a task that itself exits 125 reads as docker failing to start its
  container: the run ends `container_unavailable` and the scheduler pauses, every time. (125 is
  what `docker run` returns for its own errors; `git bisect run` and `timeout` use it too.)
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
  brings nothing home; if it reported `done`, that is `no_commits`. So is an export that takes
  longer than its 2 minutes, or a bundle that fails git's object check.
- A kill that arrives while the daemon is still creating the container is retried twice (at the
  client's exit and 5 s later). A container that outlives that, or whose run row is gone, runs
  until its command ends; `--rm` then removes it. There is no timer inside the container.
- A docker client that ends without orange's kill (the OOM killer, a lost daemon connection, a
  full disk under the run root that closes its stdout) loses its container at once, but the run
  reads as the agent's own failure: `no_result` for an agent, a failing evidence row with the
  client's exit code for a command. Both count against the task.
- When docker cannot say that a run's containers are gone, the run's clone stays under its
  directory until `RunRetention` deletes that (14 days). Retention does not ask docker again
  and walks the tree by path: a container still alive and swapping directories for links at
  that moment could make orange delete what a link names.
- A coordinator restarted in process mode does not remove the containers of runs it started in
  container mode, does not delete their clones before retention does, and its guard is off for
  them.
- With Docker down when orange starts in container mode, the guard refuses every address but
  the loopback address until Docker answers (it is asked every 30 s): a remote node's session
  events and a remote steering client get 403 meanwhile.
- A reviewer still reads what the reviewed agent wrote: the diff in its prompt and the files it
  opens, so that text can argue with the reviewer; it can no longer run beside it. Not checked:
  whether Claude Code still puts the reviewed tree's `CLAUDE.md` into the prompt with
  `--setting-sources user` (seeing the prompt takes a model call). The same holds in process
  mode.
- `container_unavailable` pauses everything, a session's judge included, as a missing `claude`
  does today.
- A reviewer in a container reads the worktree as orange checked it out; it cannot run git there
  (the worktree's `.git` points at a path that is not mounted), and needs none.
- The gateway path in containers (the rewritten base URL) is unit-tested only; no gateway run is
  made (no API spend).
- The pids limit counts threads; a build that needs more than 4096 has to raise it.
- The first test run needs the network to build the test image, and the Docker CLI on the PATH.
- The worker image holds Debian's git (2.39 on bookworm), older than a host's may be. A
  repository format the host's git writes and the image's cannot read (a later git's defaults)
  would fail the run inside the container; with git 2.54 on the host it works.
- A contained diff review and a contained verify of real work have not run with real Claude
  Code; the live gate made one implement run.

## Review (2026-11-10, fresh-context reviewers, each finding checked by a second one)

Two blocking findings. A task's diff review loaded the settings of the tree it reads, so a hook
the reviewed agent had committed ran beside the reviewer with the reviewer's run token in reach
and could submit `pass` itself: in a container, and in process mode on the host. And a docker
client that ended without orange's kill (SIGKILL, a lost daemon, a closed stdout) left its
container running while orange exported from the clone it was writing and then deleted it under
it. Fixed: a `diff_review` starts with `--setting-sources user` (ruling 20; with the real Claude
Code in the worker image and an invalid token, a committed `SessionStart` hook runs under
`project,local` and does not under `user`); a container is removed at every end of its client,
before the exit is reported (ruling 21). Also fixed: a run's clone is deleted only when docker
says that the run's containers are gone, and the reaper deletes the clone of a run it reaps
(ruling 22); the reaper finishes an expired run's row before it takes the containers, and a
contained command's result is recorded only under a lease that still renews, so a lease lost
under a live supervisor is no failed evidence (ruling 23); the run network is looked up before
the web server starts, and while it is unknown the guard lets through only the loopback address
and `/mcp/worker` (ruling 24); a `done` run whose commits could not be brought home fails as
`no_commits` instead of sending an earlier attempt's head to verification; `RunRetention`
deletes what a build left read-only. Tests added for each, and for the contained kill paths that
had none: a commit comes home after a timeout and not after a cancel, and an agent without its
image is `container_unavailable`, pauses the scheduler and counts nothing.
`aKillBeforeTheContainerExistsStillLeavesNone` failed once in a full run: it looked one second
after the kill, and the daemon had finished creating the container in between; it now looks
after the handle's last removal. Left as known limits above: a client's death reads as the
agent's or the command's own failure and counts; a clone docker could not vouch for waits for
retention, which walks it by path; with Docker down at the start only the host itself reaches
orange; what the reviewed agent wrote still reaches the reviewer as text, and whether its
`CLAUDE.md` does was not checked. Not done: a test that kills a contained agent for its budget
or at a shutdown (both take the handle's `kill`, like the timeout and the cancel).

## Verified

### The worker image (C4, 2026-11-10)

Docker 29.7.2 (Docker Desktop, linux/arm64), built as uid 501, gid 20.

- `./worker/build.sh` built `orange-worker:local` on `debian:bookworm-slim` with the Dockerfile
  as planned: nothing about the installer's layout had to change. `docker run --rm
  orange-worker:local claude --version` prints `2.1.322 (Claude Code)`, `id -u` prints `501`,
  `/home/agent` is the agent's and writable, `/root` is closed to it. `--base ubuntu:24.04` builds
  the same way (git 2.43, ripgrep 14.1).
- `./gradlew liveTest --tests '*ContainerLiveIT'` without a token: the invalid-token test passes
  (init line: `bypassPermissions`, cwd the mounted directory, `orange` connected, tools `Bash`,
  `Edit`, `Glob`, `Grep`, `Read`, `Write`, `mcp__orange__report_result`,
  `mcp__orange__request_human`; result `Failed to authenticate. API Error: 401 OAuth access token
  is invalid.`; no container left); the real implement run is skipped.
- By hand, the export path with the real image: a clone made by the host's git, a commit made in
  a container as `orange[bot]`, the bundle written by a second container with no network from the
  clone mounted read-only, fetched and reset into a linked worktree on the host.

### The whole suite (C5, and again after the review, 2026-11-10)

`./gradlew test` on the code after the review's fixes: 88 suites, 677 tests, 0 failures, 0
errors, 0 skipped, 6m22s (Docker 29.7.2, Docker Desktop, linux/arm64). The container tests among
them, all against the real daemon with the test image: `ContainerRuntimeIT` 14,
`DockerCommandTest` 12, `RunnerPropertiesTest` 9, `ContainerUrlsTest` 2, `ContainerAdapterIT` 9,
`ContainerRunIT` 27, `ContainerFailureTest` 5, `RunNetworkGuardTest` 13, `RunLoopE2EIT` 3
(`InContainers` one of them). No model call and no credential. Afterwards no fake Claude Code
process and no container with an `orange.run` label is left. (Before the review, C5: 663 tests,
5m38s.)

The review's fixes, checked the other way round: with the fix lines taken out again (the
setting sources, the removal at a client's end in the handle and in `ContainerRuntime.run`, the
lease check before a command's result, `no_commits` for commits that did not come home), exactly
these fail: `ClaudeCodeCommandTest.aRunThatMustNotLoadItsDirectorysSettingsNamesOnlyTheUsers`,
`ContainerRunIT.aDiffReviewReadsTheWorktreeAndCannotWriteIt`, `DiffReviewIT.passVerifiesTheTask`,
`ContainerAdapterIT.aClientThatEndsByItselfTakesItsContainerWithItBeforeItsExitIsReported`,
`ContainerRuntimeIT.theClientsEndIsNotTheContainersSoRunRemovesIt`,
`ContainerRunIT.aLeaseTheReaperTakesFromALiveVerifyRunIsNoVerdictOnTheWork` and
`ContainerRunIT.doneWithCommitsThatDoNotComeHomeIsNoCommits`. The two `ContainerRunIT` tests that
SIGKILL a docker client pass either way at that level, because the discard now removes a run's
containers by label before it deletes anything.

`./gradlew liveTest --tests 'com.ai.orange.e2e.ContainerLiveIT'` without a token, after the
review: 3 tests, 0 failures, 1 skipped (the real implement run).
`aReviewRunsNoHookOfTheTreeItReads` (7.4 s, Claude Code 2.1.322 in `orange-worker:local`, an
invalid token, Anthropic's 401 and no model call): the reviewer's command line on a read-only
tree whose `.claude/settings.json` holds a `SessionStart` hook; the hook leaves its mark with
`--setting-sources project,local` and leaves none with a review's `user`.

The docs the plan names are in place: `CLAUDE.md` (layout, the worker image, the tests and the
live tests, the contained path under "Running Claude Code", the "Isolation" section, the reaper,
the pause, the evidence runner, `probe`, the `orange.runner` knobs, the two variables) and
`orange/.env.example` (`ORANGE_RUNNER_ISOLATION`, `ORANGE_WORKER_IMAGE`).

### The loop in containers by hand, with the fake (C5, 2026-11-10)

The hand loop of C5 with the suite's fake in the test image where the live check has Claude Code
in the worker image: no model, no credential (a made-up token in the shell). It checks what the
suite does not: a coordinator started from the command line with the shipped `application.yaml`,
configured by environment variables alone, and a task that comes in through `POST /tasks`.

- A throwaway repository (`git init -b main`, one commit, no origin) and a canary file next to
  it, all under `/private/tmp`.
- `./gradlew bootTestRun` (a Postgres of its own, not the development database) with
  `SERVER_PORT=18473`, `ORANGE_RUNNER_ISOLATION=container`,
  `ORANGE_WORKER_IMAGE=orange-worker-test:local`,
  `ORANGE_RUNNER_CONTAINER_CLAUDECOMMAND_0=java`,
  `ORANGE_RUNNER_CONTAINER_CLAUDECOMMAND_1=/opt/fake-claude/FakeClaude.java`,
  `WORKTREES_BASE_REPO`, `WORKTREES_ROOT`, `WORKTREES_BASE_BRANCH=main`, `ORANGE_RUN_ROOT`,
  `ORANGE_EVIDENCE_COMMANDS_0_KIND=tests`,
  `ORANGE_EVIDENCE_COMMANDS_0_COMMAND='test -f orange-fake.txt && test ! -e <canary> && id -u'`
  and `ORANGE_RUNS_LOOPENABLED=true` (the test configuration on that classpath switches the loop
  off). Started in 6.7 s; orange warned that container mode runs without `ORANGE_API_TOKEN`.
- `POST /tasks` with role `chore`: `integrated` 5.8 s later, every run at its first attempt.
  - implement (small tier): clone 143 ms, the fake in its container about 1.4 s until it
    reported, export and import 370 ms, 2.7 s in all. Its MCP calls went to
    `http://host.docker.internal:18473/mcp/worker`.
  - verify: 0.6 s; the evidence row has exit code 0 and the output `501`, so the command ran as
    orange's uid in a container that does not hold the canary.
  - diff review (medium tier, the worktree read-only): 2.1 s, `pass`.
  - integrate: 0.3 s; `main` of the throwaway repository is at the task's commit, its checkout
    clean with the new file in it, and the task's worktree is gone.
- Afterwards: no container with an `orange.run` label; the implement run's directory holds
  `stream.jsonl`, `stderr.log` and `claude-config` and no clone, export or `mcp.json`; the
  made-up token is in no `stream.jsonl`, no `stderr.log` and not in the coordinator's log. (It
  is in `claude-config/fake-env.json`: the fake writes down the environment it was given, for
  the suite to read.)

Nothing had to change for it. Added on the way: the two INFO lines of `WorkerRunner` that give
the clone's and the export's time, which the record below asks for.

### Live check (2026-11-10, after the review's fixes)

`./gradlew liveTest --rerun --tests 'com.ai.orange.e2e.ContainerLiveIT'` with the subscription
token in `orange/.env` (Docker 29.7.2, Docker Desktop, linux/arm64; `orange-worker:local` with
Claude Code 2.1.322 on `debian:bookworm-slim`, agent user 501:20): 3 tests, 0 failures, none
skipped.

- `oneRealImplementRunInAContainer`: Claude Code in the worker image answered from Anthropic and
  worked through a real implement run on a throwaway repository: the clone took 159 ms, the run
  9 s on the small tier (Haiku, low effort, an estimated $0.0015), the export and its import
  542 ms. The run succeeded, its commit is on the task's branch in the worktree, the worktree is
  clean, no container is left and the token is in none of the run's files nor in the test's
  log. The same test on the code before the review: 10 s, $0.0028, 159 ms and 399 ms.
- `aReviewRunsNoHookOfTheTreeItReads` and
  `realClaudeInTheImageTakesOrangesCommandLineAndReachesTheWorkerMcp` pass (an invalid token, no
  model call).
- Nothing had to change for the real run.

Not run: the whole loop by hand (implement, verify, diff review, integrate) with the real image
and real Claude Code; only its rehearsal with the fake was (above). A contained diff review has
therefore not made a model call. The run network guard's source address on a Linux bridge waits
for the VPS (Phase V).
