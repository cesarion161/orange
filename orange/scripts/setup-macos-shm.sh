#!/usr/bin/env bash
# Bump macOS sysctl shared-memory limits so embedded-postgres can run initdb
# during jOOQ codegen. Stock macOS ships shmmax=4MB / shmall=1024 pages (4MB),
# which is below what postgres 18 needs to bootstrap — `./gradlew generateJooq`
# then fails with "could not create shared memory segment: Cannot allocate
# memory".
#
# Usage:
#   ./scripts/setup-macos-shm.sh         # one-shot for current boot
#   ./scripts/setup-macos-shm.sh --persist  # also write to /etc/sysctl.conf
#
# Both modes require sudo. Linux is unaffected and doesn't need this.

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "skip: not macOS"; exit 0
fi

PAGE=$(getconf PAGESIZE)            # 4096 on Apple Silicon + Intel
TARGET_SHMMAX=$((256 * 1024 * 1024))  # 256 MB
TARGET_SHMALL=$((TARGET_SHMMAX / PAGE))

echo "applying: kern.sysv.shmmax=${TARGET_SHMMAX} kern.sysv.shmall=${TARGET_SHMALL}"
sudo sysctl -w "kern.sysv.shmmax=${TARGET_SHMMAX}"
sudo sysctl -w "kern.sysv.shmall=${TARGET_SHMALL}"

if [[ "${1:-}" == "--persist" ]]; then
    SYSCTL_CONF="/etc/sysctl.conf"
    echo "persisting to ${SYSCTL_CONF}"
    sudo touch "${SYSCTL_CONF}"
    if ! sudo grep -q "^kern.sysv.shmmax" "${SYSCTL_CONF}"; then
        echo "kern.sysv.shmmax=${TARGET_SHMMAX}" | sudo tee -a "${SYSCTL_CONF}" >/dev/null
    fi
    if ! sudo grep -q "^kern.sysv.shmall" "${SYSCTL_CONF}"; then
        echo "kern.sysv.shmall=${TARGET_SHMALL}" | sudo tee -a "${SYSCTL_CONF}" >/dev/null
    fi
    echo "done — values will survive reboot"
else
    echo "done — values revert on reboot (re-run, or pass --persist to keep them)"
fi
