#!/bin/sh
# Builds the image orange's contained runs start from (orange.runner.container.image).
#
#   ./worker/build.sh [--base <image>] [--tag <name>] [--claude <version>]
#
#   --base    the image to build on: any Debian or Ubuntu image that has the repo's toolchain
#             (default debian:bookworm-slim)
#   --tag     the image's name (default orange-worker:local); name it in ORANGE_WORKER_IMAGE
#   --claude  the Claude Code version to install (default 2.1.322)
#
# The agent user gets the uid and gid of whoever runs this script, and that must be the user
# orange runs as: only then can orange read and delete what a run wrote. So build the image on
# the host that uses it, as that user. orange never pulls or builds it by itself.
set -eu

base=debian:bookworm-slim
tag=orange-worker:local
claude=2.1.322

usage() {
    echo "usage: $0 [--base <image>] [--tag <name>] [--claude <version>]" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --base)   [ $# -ge 2 ] || usage; base=$2; shift 2 ;;
        --tag)    [ $# -ge 2 ] || usage; tag=$2; shift 2 ;;
        --claude) [ $# -ge 2 ] || usage; claude=$2; shift 2 ;;
        *)        usage ;;
    esac
done

uid=$(id -u)
gid=$(id -g)
if [ "$uid" -eq 0 ]; then
    echo "$0: run this as the user orange runs as, not as root: container runs must not be root" >&2
    exit 1
fi

cd "$(dirname "$0")"
docker build \
    --build-arg "BASE_IMAGE=$base" \
    --build-arg "CLAUDE_CODE_VERSION=$claude" \
    --build-arg "AGENT_UID=$uid" \
    --build-arg "AGENT_GID=$gid" \
    --tag "$tag" \
    .
echo "built $tag: Claude Code $claude on $base, agent user $uid:$gid"
