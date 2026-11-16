#!/usr/bin/env bash
# Deploys orange to the VPS (remote-first spec R3): builds the jar here, copies it with the deploy
# files and the worker image's files, sends the secrets, and runs setup.sh there as root. Idempotent:
# rerun it for every new version. Nothing host-specific lives in the repo; the host is an SSH alias.
#
#   deploy/deploy.sh [host]        (default host: orange-vps)
set -euo pipefail
HOST=${1:-orange-vps}
ROOT=$(cd "$(dirname "$0")/.." && pwd)

echo "== building the jar"
(cd "$ROOT/orange" && ./gradlew -q bootJar)
JAR=$(ls "$ROOT"/orange/build/libs/orange-*.jar | grep -v -- '-plain' | head -1)
[ -f "$JAR" ] || { echo "deploy: no jar" >&2; exit 1; }

echo "== copying to $HOST"
ssh "$HOST" 'mkdir -p /opt/orange/deploy /opt/orange/worker'
# root runs and builds from these: copied without owner or group, so they are root's, not this machine's uid (macOS rsync has no --chown).
rsync -rlpt --delete "$ROOT/deploy/vps/" "$HOST:/opt/orange/deploy/"
rsync -rlpt --delete "$ROOT/orange/worker/" "$HOST:/opt/orange/worker/"
rsync -rlpt "$JAR" "$HOST:/opt/orange/orange.jar.new"

echo "== secrets"
"$ROOT/deploy/push-secrets.sh" "$HOST" --no-restart

echo "== setup"
# The time zone quiet hours and brief times are read in: the laptop's own.
ZONE=$(readlink /etc/localtime 2>/dev/null | sed 's|.*/zoneinfo/||' || true)
OUT=$(mktemp); trap 'rm -f "$OUT"' EXIT
ssh "$HOST" "ORANGE_ZONE='$ZONE' bash /opt/orange/deploy/setup.sh" | tee "$OUT" || { echo "deploy: setup failed" >&2; exit 1; }
URL=$(sed -n 's/^public-url: //p' "$OUT")

echo "== from this machine, over the tailnet"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$URL/api/ready" || true)
if [ "$code" = 200 ]; then echo "ok    $URL answers"; else echo "FAIL  $URL/api/ready gave $code" >&2; exit 1; fi
