#!/usr/bin/env bash
# What a run container may not reach through the host: the tailnet (the user's laptop and phone,
# MagicDNS), link-local addresses (a provider's metadata service) and private networks. The internet
# stays open to runs (they talk to Anthropic and fetch dependencies). Docker forwards bridge traffic
# past ufw, so the rules go in DOCKER-USER, which Docker consults first and never flushes.
# Idempotent; orange-run-egress.service runs it at boot, after Docker.
set -euo pipefail
BRIDGE=${1:-orange-runs0}
for range in 100.64.0.0/10 169.254.0.0/16 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16; do
  iptables -C DOCKER-USER -i "$BRIDGE" -d "$range" -j DROP 2>/dev/null \
    || iptables -I DOCKER-USER -i "$BRIDGE" -d "$range" -j DROP
done
