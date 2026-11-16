#!/usr/bin/env bash
# Copies the two secrets orange needs from the laptop's orange/.env to the VPS, and restarts orange
# there unless told not to: CLAUDE_CODE_OAUTH_TOKEN (from `claude setup-token`) and
# ORANGE_GITHUB_TOKENS (owner=token,...). To rotate one: change it in orange/.env, run this again.
#
#   deploy/push-secrets.sh [host] [--no-restart]
#
# The values travel on SSH's stdin into /etc/orange/secrets.env (root, mode 600), never in an argv;
# setup.sh merges them into /etc/orange/orange.env.
set -euo pipefail
HOST=orange-vps
RESTART=1
for a in "$@"; do
  case $a in
    --no-restart) RESTART=0 ;;
    -*) echo "usage: $0 [host] [--no-restart]" >&2; exit 2 ;;
    *) HOST=$a ;;
  esac
done
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV="$ROOT/orange/.env"
[ -f "$ENV" ] || { echo "push-secrets: no $ENV" >&2; exit 1; }
lines=$(grep -E '^(CLAUDE_CODE_OAUTH_TOKEN|ORANGE_GITHUB_TOKENS)=.+' "$ENV" || true)
[ -n "$lines" ] || { echo "push-secrets: neither secret is set in $ENV" >&2; exit 1; }
printf '%s\n' "$lines" | ssh "$HOST" 'set -e; umask 077; mkdir -p /etc/orange; cat > /etc/orange/secrets.env.tmp; mv /etc/orange/secrets.env.tmp /etc/orange/secrets.env'
echo "push-secrets: sent $(printf '%s\n' "$lines" | cut -d= -f1 | paste -sd, -) to $HOST"
if [ "$RESTART" = 1 ]; then
  ssh "$HOST" 'set -e
    test -f /etc/orange/orange.env || { echo "push-secrets: not set up yet; run deploy/deploy.sh"; exit 1; }
    python3 - <<PY
import os
env, sec = "/etc/orange/orange.env", "/etc/orange/secrets.env"
def read(p):
    return [l.rstrip("\n") for l in open(p)]
new = dict(l.split("=", 1) for l in read(sec) if "=" in l)
out = []
for l in read(env):
    k = l.split("=", 1)[0]
    if k in new:
        l = k + "=" + new.pop(k)
    out.append(l)
out += [k + "=" + v for k, v in new.items()]
fd = os.open(env + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f: f.write("\n".join(out) + "\n")
os.replace(env + ".tmp", env)
PY
    systemctl restart orange.service && echo "push-secrets: orange restarted"'
fi
