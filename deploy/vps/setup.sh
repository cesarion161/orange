#!/usr/bin/env bash
# Sets the VPS up for orange, or brings it up to date: idempotent, safe to rerun.
# Run as root on the VPS by deploy/deploy.sh, which has copied the jar, these files and the worker
# image's files under /opt/orange. Nothing host-specific is in this repo: the tailnet name and
# addresses are read from the host itself.
#
# Environment (from deploy.sh): ORANGE_ZONE, the user's time zone for quiet hours and briefs.
set -euo pipefail

OPT=/opt/orange
HOME_DIR=/var/lib/orange
ETC=/etc/orange
ENV_FILE=$ETC/orange.env
SECRETS=$ETC/secrets.env
PORT=8080
NTFY_PORT=2586
NTFY_IMAGE=binwiederhier/ntfy:v2.38.1
# The run network: a bridge of orange's own with a fixed subnet, so orange can listen on its gateway
# and nowhere else. Outside Docker's default address pools.
RUN_NETWORK=orange-runs
RUN_BRIDGE=orange-runs0
RUN_SUBNET=10.213.0.0/24
RUN_GATEWAY=10.213.0.1
CLAUDE_CODE_VERSION=$(sed -n 's/^ARG CLAUDE_CODE_VERSION=//p' "$OPT/worker/Dockerfile")

say() { printf '\n== %s\n' "$*"; }
fail() { printf 'setup: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || fail "run me as root"
[ -f "$OPT/orange.jar.new" ] || [ -f "$OPT/orange.jar" ] || fail "no jar under $OPT"
command -v docker >/dev/null && command -v java >/dev/null && command -v tailscale >/dev/null \
  || fail "docker, java and tailscale must be installed"

TS_NAME=$(tailscale status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
[ -n "$TS_NAME" ] || fail "tailscale has no name for this host (is it logged in, with MagicDNS on?)"
PUBLIC_URL="https://$TS_NAME"
NTFY_PUBLIC_URL="https://$TS_NAME:8443"

say "the orange user"
if ! id orange >/dev/null 2>&1; then
  useradd --system --create-home --home-dir "$HOME_DIR" --shell /usr/sbin/nologin orange
fi
usermod -aG docker orange
install -d -o orange -g orange -m 0750 "$HOME_DIR" "$HOME_DIR/runs" "$HOME_DIR/worktrees" "$HOME_DIR/repos" "$HOME_DIR/logs"
install -d -o root -g root -m 0700 "$ETC"
install -d -o root -g root -m 0755 "$ETC/ntfy"

say "the run network"
if ip -4 route | grep -v "dev $RUN_BRIDGE" | grep -q "^${RUN_SUBNET%.*}\."; then
  fail "$RUN_SUBNET is already routed on this host; pick another RUN_SUBNET"
fi
if docker network inspect "$RUN_NETWORK" >/dev/null 2>&1; then
  seen=$(docker network inspect "$RUN_NETWORK" --format '{{range .IPAM.Config}}{{.Subnet}} {{.Gateway}}{{end}} {{index .Options "com.docker.network.bridge.enable_icc"}}')
  [ "$seen" = "$RUN_SUBNET $RUN_GATEWAY false" ] \
    || fail "the network $RUN_NETWORK exists as '$seen'; remove it (no run may be using it) and rerun"
else
  docker network create --driver bridge --subnet "$RUN_SUBNET" --gateway "$RUN_GATEWAY" \
    --opt com.docker.network.bridge.name="$RUN_BRIDGE" \
    --opt com.docker.network.bridge.enable_icc=false \
    --label orange.managed=true "$RUN_NETWORK" >/dev/null
fi
# orange binds the gateway's address; after a reboot it may start before Docker has the bridge up.
echo 'net.ipv4.ip_nonlocal_bind = 1' > /etc/sysctl.d/60-orange.conf
sysctl -q -p /etc/sysctl.d/60-orange.conf

say "the environment file"
# Kept: whatever was generated before. Replaced: the secrets from push-secrets.sh and the fixed
# settings below. Added: whatever is missing. Mode 600, root's: systemd reads it before it drops to orange.
NTFY_TOKEN_IN_FILE=$(sed -n 's/^ORANGE_NTFY_TOKEN=//p' "$ENV_FILE" 2>/dev/null || true)
ENV_FILE="$ENV_FILE" SECRETS="$SECRETS" PUBLIC_URL="$PUBLIC_URL" PORT="$PORT" NTFY_PORT="$NTFY_PORT" \
RUN_GATEWAY="$RUN_GATEWAY" HOME_DIR="$HOME_DIR" ORANGE_ZONE="${ORANGE_ZONE:-}" NTFY_IMAGE="$NTFY_IMAGE" \
python3 - <<'PY'
import os, secrets
path, sec = os.environ["ENV_FILE"], os.environ["SECRETS"]

def read(p):
    out = {}
    if os.path.exists(p):
        for line in open(p):
            line = line.rstrip("\n")
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k] = v
    return out

env = read(path)
env.update({k: v for k, v in read(sec).items() if v})
generated = {
    "POSTGRES_PASSWORD": lambda: secrets.token_hex(24),
    "ORANGE_API_TOKEN": lambda: secrets.token_urlsafe(32),
    "ORANGE_NTFY_ATTENTION_TOPIC": lambda: "orange-" + secrets.token_hex(8),
    "NTFY_PHONE_PASSWORD": lambda: secrets.token_urlsafe(18),
    "NTFY_ORANGE_PASSWORD": lambda: secrets.token_urlsafe(24),
}
for k, make in generated.items():
    if not env.get(k):
        env[k] = make()
h, gw = os.environ["HOME_DIR"], os.environ["RUN_GATEWAY"]
env.update({
    "SPRING_DATASOURCE_URL": "jdbc:postgresql://127.0.0.1:5432/orange",
    "SPRING_DATASOURCE_USERNAME": "orange",
    "SPRING_DATASOURCE_PASSWORD": env["POSTGRES_PASSWORD"],
    "SERVER_ADDRESS": "127.0.0.1",
    "SERVER_PORT": os.environ["PORT"],
    # Where the run network reaches orange, and what runs call that address.
    "ORANGE_SERVER_EXTRA_ADDRESSES": gw,
    "ORANGE_RUNNER_HOST_ADDRESS": gw,
    "ORANGE_RUNNER_ISOLATION": "container",
    "ORANGE_REPOS_ROOT": h + "/repos",
    "WORKTREES_ROOT": h + "/worktrees",
    "ORANGE_RUN_ROOT": h + "/runs",
    "LOGGING_FILE_NAME": h + "/logs/orange.log",
    "ORANGE_PUBLIC_URL": os.environ["PUBLIC_URL"],
    "ORANGE_NTFY_URL": "http://127.0.0.1:" + os.environ["NTFY_PORT"],
    "NTFY_IMAGE": os.environ["NTFY_IMAGE"],
    # No OpenTelemetry collector here (the laptop's Grafana LGTM is for development).
    "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED": "false",
    "MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED": "false",
    "MANAGEMENT_OTLP_LOGGING_EXPORT_ENABLED": "false",
    "MANAGEMENT_TRACING_EXPORT_ENABLED": "false",
})
if os.environ["ORANGE_ZONE"]:
    env["ORANGE_NOTIFY_ZONE"] = os.environ["ORANGE_ZONE"]
missing = [k for k in ("CLAUDE_CODE_OAUTH_TOKEN", "ORANGE_GITHUB_TOKENS") if not env.get(k)]
if missing:
    print("setup: warning: no " + ", ".join(missing) + " yet (run deploy/push-secrets.sh)")
tmp = path + ".tmp"
fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    f.write("# orange on this host. Written by /opt/orange/deploy/setup.sh; secrets come from push-secrets.sh.\n")
    for k in sorted(env):
        f.write(f"{k}={env[k]}\n")
os.replace(tmp, path)
PY
get() { sed -n "s/^$1=//p" "$ENV_FILE"; }

say "Postgres and ntfy"
sed "s|@BASE_URL@|$NTFY_PUBLIC_URL|" "$OPT/deploy/ntfy/server.yml.tmpl" > "$ETC/ntfy/server.yml"
chmod 0644 "$ETC/ntfy/server.yml"
compose() { docker compose -p orange --env-file "$ENV_FILE" -f "$OPT/deploy/compose.yaml" "$@"; }
compose up -d --wait --quiet-pull
ntfy_cli() { compose exec -T ntfy ntfy "$@"; }
TOPIC=$(get ORANGE_NTFY_ATTENTION_TOPIC)
users=$(ntfy_cli user list 2>&1 || true)
for u in orange phone; do
  if ! grep -q "^user $u " <<<"$users"; then
    # The password goes in through the environment, never an argv.
    NTFY_PASSWORD=$(get "NTFY_$(tr a-z A-Z <<<"$u")_PASSWORD") \
      compose exec -T -e NTFY_PASSWORD ntfy ntfy user add "$u" >/dev/null
  fi
done
ntfy_cli access orange "$TOPIC" rw >/dev/null
ntfy_cli access phone "$TOPIC" ro >/dev/null
if [ -z "$NTFY_TOKEN_IN_FILE" ]; then
  token=$(ntfy_cli token add --label=orange orange 2>&1 | grep -o 'tk_[a-z0-9]*' | head -1)
  [ -n "$token" ] || fail "ntfy did not give orange a token"
  printf 'ORANGE_NTFY_TOKEN=%s\n' "$token" >> "$ENV_FILE"
fi

say "Claude Code $CLAUDE_CODE_VERSION on the host (plan, brief and judge runs)"
if ! /usr/local/bin/claude --version 2>/dev/null | grep -qF "$CLAUDE_CODE_VERSION"; then
  sudo -u orange -H bash -c "curl -fsSL https://claude.ai/install.sh | bash -s -- '$CLAUDE_CODE_VERSION'" >/dev/null
  install -m 0755 "$(readlink -f "$HOME_DIR/.local/bin/claude")" /usr/local/bin/claude
  /usr/local/bin/claude --version | grep -qF "$CLAUDE_CODE_VERSION" || fail "claude is not at $CLAUDE_CODE_VERSION"
fi

say "the worker image"
want=$(cat "$OPT/worker/Dockerfile" "$OPT/worker/build.sh" | sha256sum | cut -c1-16)
have=$(cat "$HOME_DIR/.worker-inputs" 2>/dev/null || true)
if [ "$want" != "$have" ] || ! docker image inspect orange-worker:local >/dev/null 2>&1; then
  # As orange: the image's agent user gets orange's uid.
  sudo -u orange -H bash -c "cd '$OPT/worker' && ./build.sh" \
    > "$HOME_DIR/logs/worker-build.log" 2>&1 || fail "the worker image did not build: $HOME_DIR/logs/worker-build.log"
  echo "$want" > "$HOME_DIR/.worker-inputs"
fi

say "orange"
install -m 0644 "$OPT/deploy/orange.service" /etc/systemd/system/orange.service
systemctl daemon-reload
systemctl enable orange.service >/dev/null 2>&1
if [ -f "$OPT/orange.jar.new" ]; then
  [ -f "$OPT/orange.jar" ] && mv -f "$OPT/orange.jar" "$OPT/orange.jar.prev"
  mv -f "$OPT/orange.jar.new" "$OPT/orange.jar"
fi
chmod 0644 "$OPT/orange.jar"
healthy() {
  for _ in $(seq 1 90); do
    if curl -fsS "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null | grep -q '"UP"'; then return 0; fi
    systemctl is-failed -q orange.service && return 1
    sleep 2
  done
  return 1
}
systemctl restart orange.service
if ! healthy; then
  journalctl -u orange.service -n 40 --no-pager >&2 || true
  if [ -f "$OPT/orange.jar.prev" ]; then
    echo "setup: the new jar is not healthy; putting the previous one back" >&2
    mv -f "$OPT/orange.jar" "$OPT/orange.jar.bad"
    mv -f "$OPT/orange.jar.prev" "$OPT/orange.jar"
    systemctl restart orange.service
    healthy || fail "the previous jar is not healthy either"
  fi
  fail "orange is not healthy"
fi

say "the tailnet entry"
tailscale serve --bg --https=443 "http://127.0.0.1:$PORT" >/dev/null
tailscale serve --bg --https=8443 "http://127.0.0.1:$NTFY_PORT" >/dev/null

say "firewall and SSH"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow 22/tcp >/dev/null                  # the way back in when Tailscale breaks
ufw allow 41641/udp >/dev/null               # Tailscale's direct connections
ufw allow in on tailscale0 >/dev/null
ufw allow in on "$RUN_BRIDGE" to "$RUN_GATEWAY" port "$PORT" proto tcp >/dev/null  # runs reach /mcp/worker
ufw insert 1 deny in on "$RUN_BRIDGE" to any port 22 proto tcp >/dev/null  # but not sshd
ufw --force enable >/dev/null
install -m 0644 "$OPT/deploy/orange-run-egress.service" /etc/systemd/system/orange-run-egress.service
systemctl daemon-reload
systemctl enable orange-run-egress.service >/dev/null 2>&1
systemctl restart orange-run-egress.service
# Passwords go off only once a key is known to let root in; nothing is written before that.
mkdir -p /run/sshd
keys=$(sshd -T 2>/dev/null | sed -n 's/^authorizedkeysfile //p' | tr ' ' '\n' | sed "s|%h|/root|; s|%u|root|; s|^\([^/]\)|/root/\1|")
has_key=0
for f in $keys; do [ -s "$f" ] && has_key=1; done
[ "$has_key" = 1 ] || fail "root has no authorized key ($keys); refusing to turn passwords off"
DROPIN=/etc/ssh/sshd_config.d/00-orange.conf
trap 'rm -f "$DROPIN.new"' EXIT
cat > "$DROPIN.new" <<'SSHD'
# Written by orange's setup.sh: keys only.
PermitRootLogin prohibit-password
PasswordAuthentication no
KbdInteractiveAuthentication no
SSHD
mv -f "$DROPIN.new" "$DROPIN"
sshd -t || { rm -f "$DROPIN"; fail "sshd refused the new config; left it as it was"; }
systemctl reload ssh.service 2>/dev/null || true

say "checks"
API_TOKEN=$(get ORANGE_API_TOKEN)
probe() { # probe <args...>: one curl from a container on the run network, as a run makes it
  docker run --rm --network "$RUN_NETWORK" --add-host "host.docker.internal:$RUN_GATEWAY" \
    --cap-drop ALL --security-opt no-new-privileges --entrypoint curl orange-worker:local \
    -s -o /dev/null -w '%{http_code}' --max-time 5 "$@" || true
}
ok=1
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: expected $2, got $3"; ok=0; fi; }
check "orange on loopback wants the token" 403 "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/tasks")"
check "orange on loopback with the token" 200 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $API_TOKEN" "http://127.0.0.1:$PORT/tasks")"
check "a run is refused the operator API, token or not" 403 "$(probe -H "Authorization: Bearer $API_TOKEN" "http://host.docker.internal:$PORT/tasks")"
check "a run reaches /mcp/worker (which wants its run token)" 401 "$(probe -X POST "http://host.docker.internal:$PORT/mcp/worker")"
check "a run does not reach Postgres" 000 "$(probe "http://$RUN_GATEWAY:5432/")"
check "a run does not reach ntfy" 000 "$(probe "http://$RUN_GATEWAY:$NTFY_PORT/")"
check "a run does not reach sshd" 000 "$(probe "http://$RUN_GATEWAY:22/")"
check "a run does not reach the tailnet (MagicDNS)" 000 "$(probe http://100.100.100.100/)"
check "a run does not reach link-local (metadata)" 000 "$(probe http://169.254.169.254/)"
check "a run reaches the internet" 200 "$(probe https://api.github.com/zen)"
ntfy_anon=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$NTFY_PORT/$TOPIC/json?poll=1")
check "ntfy wants an account" refused "$(case $ntfy_anon in 401|403) echo refused;; *) echo "$ntfy_anon";; esac)"
check "sshd: keys only" "passwordauthentication no permitrootlogin without-password" \
  "$(sshd -T 2>/dev/null | grep -E '^(permitrootlogin|passwordauthentication) ' | sort | tr '\n' ' ' | sed 's/ $//')"
check "ufw is active" active "$(ufw status | sed -n 's/^Status: //p')"

echo
echo "public-url: $PUBLIC_URL"
echo "ntfy-url: $NTFY_PUBLIC_URL"
[ "$ok" = 1 ] || fail "some checks failed"
echo "setup: done"
