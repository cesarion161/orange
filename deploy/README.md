# Deploying orange to the VPS

orange runs on one always-on Linux host (remote-first spec §6, R3). The laptop runs only the Claude
Code plugin. Everything here is idempotent: rerun it for every new version.

```
deploy/deploy.sh [host]          # build, copy, send the secrets, set up, check (default host: orange-vps)
deploy/push-secrets.sh [host]    # only send the secrets again, then restart orange
```

`host` is an SSH alias for root on the VPS. The host needs Ubuntu 24.04 with Docker (and Compose),
a JDK 27 as `/usr/bin/java`, and Tailscale logged in with MagicDNS and HTTPS certificates on.
Nothing host-specific is in this repo: the tailnet name and addresses are read on the host.

## What `setup.sh` does there

- **The user `orange`** (system user, home `/var/lib/orange`, in the `docker` group). orange runs
  as it, never as root.
- **`orange.service`** (systemd): `java -jar /opt/orange/orange.jar` with
  `/etc/orange/orange.env`, in container mode. A new jar that does not get healthy is swapped back
  for the previous one.
- **Postgres 18 and ntfy** (`compose.yaml`, project `orange`), published on 127.0.0.1 only.
  ntfy needs an account for everything: the user `orange` publishes, the user `phone` reads.
- **The run network** `orange-runs`: a fixed subnet, bridge `orange-runs0`, ICC off. orange listens
  on 127.0.0.1 and on that bridge's gateway, and runs call the gateway `host.docker.internal`.
- **Claude Code** at the worker image's pinned version as `/usr/local/bin/claude`, for the runs
  that stay on the host (plan, brief, judge). **The worker image** `orange-worker:local`, built as
  `orange` whenever `orange/worker/` changes.
- **The tailnet entry**: `tailscale serve` gives HTTPS on the host's MagicDNS name: port 443 is
  orange, port 8443 is ntfy.
- **Firewall and SSH**: ufw denies everything incoming but SSH, Tailscale (the `tailscale0`
  interface and its UDP port) and the run bridge to orange's port (not to sshd). sshd takes keys
  only, and only once root is known to have a key.
- **What runs may not reach** (`run-egress.sh`, kept by `orange-run-egress.service`): the tailnet
  (100.64.0.0/10), link-local addresses (a provider's metadata service) and the private ranges,
  dropped in the raw table as they come off the run bridge. Filtering in FORWARD would not hold:
  Tailscale's chain there accepts everything bound for `tailscale0` first, and Docker forwards
  bridge traffic past ufw. The internet stays open to runs.
- **Checks**:
  - the API wants the token;
  - a container on the run network gets 403 everywhere but `/mcp/worker`, and cannot reach
    Postgres, ntfy, sshd, the tailnet or link-local addresses, but does reach the internet;
  - ntfy wants an account;
  - sshd and ufw are as above.

## Where things are

| What | Where |
| --- | --- |
| The secrets you rotate: `CLAUDE_CODE_OAUTH_TOKEN`, `ORANGE_GITHUB_TOKENS` | the laptop's `orange/.env`; then `deploy/push-secrets.sh` |
| orange's whole environment (generated passwords, the API token, ntfy's token and topic) | `/etc/orange/orange.env` on the VPS (root, 600) |
| The API token the plugin needs | `ORANGE_API_TOKEN` in that file |
| The phone's ntfy login | user `phone`, password `NTFY_PHONE_PASSWORD` in that file |
| Logs | `journalctl -u orange` and `/var/lib/orange/logs/orange.log` |
| Mirrors, worktrees, runs | `/var/lib/orange/{repos,worktrees,runs}` |

## On the laptop

The plugin reads `ORANGE_URL` (the `public-url` the deploy prints) and `ORANGE_API_TOKEN` from
Claude Code's environment, e.g. the `env` block of `~/.claude/settings.json`. The phone subscribes in
the ntfy app to the attention topic at the `ntfy-url` the deploy prints, logged in as `phone`. The
steering tab is a Claude Code session started with `ORANGE_STEERING=1` or in `~/.orange/steering`
(the alias in `plugin/README.md`).
