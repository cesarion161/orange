#!/usr/bin/env bash
# What a run container may not reach through the host: the tailnet (the user's laptop and phone,
# MagicDNS), link-local addresses (a provider's metadata service) and private networks. The internet
# stays open to runs (they talk to Anthropic and fetch dependencies).
#
# Filtering in FORWARD does not hold: Tailscale's ts-forward chain comes first there and accepts
# whatever leaves through tailscale0, and Docker forwards bridge traffic past ufw. So the rules sit
# in the raw table's PREROUTING, which neither Docker nor Tailscale flushes, in a chain of orange's
# own. The run subnet itself is exempt: that is how runs reach orange on the gateway (ufw then
# admits only orange's port there).
#
#   run-egress.sh <bridge> <run subnet>      idempotent; orange-run-egress.service runs it at boot
set -euo pipefail
BRIDGE=${1:?bridge}
SUBNET=${2:?run subnet}
CHAIN=ORANGE-RUNS
iptables -t raw -N "$CHAIN" 2>/dev/null || true
iptables -t raw -F "$CHAIN"
iptables -t raw -A "$CHAIN" -d "$SUBNET" -j RETURN
for range in 100.64.0.0/10 169.254.0.0/16 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16; do
  iptables -t raw -A "$CHAIN" -d "$range" -j DROP
done
iptables -t raw -C PREROUTING -i "$BRIDGE" -j "$CHAIN" 2>/dev/null \
  || iptables -t raw -I PREROUTING -i "$BRIDGE" -j "$CHAIN"
# An earlier version put these in DOCKER-USER, where they never matched for the tailnet.
for range in 100.64.0.0/10 169.254.0.0/16 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16; do
  while iptables -C DOCKER-USER -i "$BRIDGE" -d "$range" -j DROP 2>/dev/null; do
    iptables -D DOCKER-USER -i "$BRIDGE" -d "$range" -j DROP
  done
done
