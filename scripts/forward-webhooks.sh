#!/usr/bin/env bash
#
# Forwards GitHub webhook events straight to the locally running orange app.
# Uses `gh webhook forward` (no public tunnel, no ngrok), so GitHub never
# needs to reach localhost.
#
# Scope: pick exactly ONE of
#   GITHUB_ORG   — forward events from every repo in the org
#                  (recommended when working across multiple repos)
#   GITHUB_REPO  — forward events from a single repo, "owner/name"
#
# To forward from repos across DIFFERENT orgs, run this script multiple
# times in parallel (different terminals) with different GITHUB_REPO
# / GITHUB_ORG values — `gh webhook forward` itself only accepts one.
#
# Other required env (from .env or shell):
#   GITHUB_WEBHOOK_SECRET   shared secret — must match orange.github.webhook-secret
#
# Optional env:
#   WEBHOOK_URL             default: http://localhost:8080/webhooks/github
#   WEBHOOK_EVENTS          default: pull_request,pull_request_review,
#                                    pull_request_review_comment,issue_comment
#
# First-time setup:
#   gh extension install cli/gh-webhook
#   gh auth login   # if not already

set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

: "${GITHUB_WEBHOOK_SECRET:?set GITHUB_WEBHOOK_SECRET in .env (openssl rand -hex 32)}"

GITHUB_ORG="${GITHUB_ORG:-}"
GITHUB_REPO="${GITHUB_REPO:-}"

if [ -n "$GITHUB_ORG" ] && [ -n "$GITHUB_REPO" ]; then
    echo "Set only one of GITHUB_ORG or GITHUB_REPO — they are mutually exclusive." >&2
    exit 1
fi
if [ -z "$GITHUB_ORG" ] && [ -z "$GITHUB_REPO" ]; then
    echo "Set either GITHUB_ORG (all repos in the org) or GITHUB_REPO (owner/name) in .env." >&2
    exit 1
fi

URL="${WEBHOOK_URL:-http://localhost:8080/webhooks/github}"
EVENTS="${WEBHOOK_EVENTS:-pull_request,pull_request_review,pull_request_review_comment,issue_comment}"

if ! gh extension list 2>/dev/null | grep -q '^gh webhook'; then
    echo "gh-webhook extension not installed. Run: gh extension install cli/gh-webhook" >&2
    exit 1
fi

if [ -n "$GITHUB_ORG" ]; then
    echo "Forwarding [$EVENTS] from org $GITHUB_ORG → $URL"
    exec gh webhook forward \
        --org="$GITHUB_ORG" \
        --events="$EVENTS" \
        --url="$URL" \
        --secret="$GITHUB_WEBHOOK_SECRET"
else
    echo "Forwarding [$EVENTS] from repo $GITHUB_REPO → $URL"
    exec gh webhook forward \
        --repo="$GITHUB_REPO" \
        --events="$EVENTS" \
        --url="$URL" \
        --secret="$GITHUB_WEBHOOK_SECRET"
fi
