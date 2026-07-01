#!/usr/bin/env bash
# ================================================================
# One-shot GitHub governance setup for the ecommerce-platform repo.
#
# Creates:
#   • production environment  → required reviewer (you) + 5-min wait timer
#   • dev environment         → no gate
#   • branch protection main    → PR + 1 approval + 6 test checks, no force-push
#   • branch protection develop → PR + 6 test checks
#
# Uses your existing GitHub login from git's credential store — no token to paste.
# Run from anywhere:  bash scripts/setup-github-governance.sh
# ================================================================
set -e
OWNER=SumedhDhamdhere
REPO=ecommerce-platform
API=https://api.github.com

TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null | sed -n 's/^password=//p')
if [ -z "$TOKEN" ]; then
  echo "❌ No GitHub credential found. Do one 'git push' first so your login is stored, then re-run."
  exit 1
fi
H_AUTH="Authorization: Bearer $TOKEN"
H_ACC="Accept: application/vnd.github+json"

WHO=$(curl -s -H "$H_AUTH" $API/user)
REVIEWER_ID=$(echo "$WHO" | sed -n 's/.*"id": \([0-9]*\).*/\1/p' | head -1)
LOGIN=$(echo "$WHO" | sed -n 's/.*"login": "\([^"]*\)".*/\1/p' | head -1)
echo "Authenticated as: $LOGIN (id=$REVIEWER_ID)"
[ -z "$REVIEWER_ID" ] && { echo "❌ Auth failed"; exit 1; }

code() { curl -s -o /tmp/gh_resp.json -w "%{http_code}" "$@"; }
show() { [ "$1" -ge 300 ] && { echo "   ⚠ response:"; cat /tmp/gh_resp.json; echo; } || echo "   ✅ ok"; }

echo "== 1/4 production environment (required reviewer + 5m wait) =="
C=$(code -X PUT -H "$H_AUTH" -H "$H_ACC" \
  "$API/repos/$OWNER/$REPO/environments/production" \
  -d "{\"wait_timer\":5,\"reviewers\":[{\"type\":\"User\",\"id\":$REVIEWER_ID}],\"deployment_branch_policy\":null}")
echo "   HTTP $C"; show "$C"

echo "== 2/4 dev environment (no gate) =="
C=$(code -X PUT -H "$H_AUTH" -H "$H_ACC" "$API/repos/$OWNER/$REPO/environments/dev" -d "{}")
echo "   HTTP $C"; show "$C"

CHECKS='"Test user-service","Test product-service","Test order-service","Test inventory-service","Test payment-service","Test notification-service"'

echo "== 3/4 branch protection: main =="
C=$(code -X PUT -H "$H_AUTH" -H "$H_ACC" \
  "$API/repos/$OWNER/$REPO/branches/main/protection" \
  -d "{\"required_status_checks\":{\"strict\":true,\"contexts\":[$CHECKS]},\"enforce_admins\":false,\"required_pull_request_reviews\":{\"required_approving_review_count\":1},\"restrictions\":null,\"allow_force_pushes\":false}")
echo "   HTTP $C"; show "$C"

echo "== 4/4 branch protection: develop =="
C=$(code -X PUT -H "$H_AUTH" -H "$H_ACC" \
  "$API/repos/$OWNER/$REPO/branches/develop/protection" \
  -d "{\"required_status_checks\":{\"strict\":true,\"contexts\":[$CHECKS]},\"enforce_admins\":false,\"required_pull_request_reviews\":{\"required_approving_review_count\":0},\"restrictions\":null,\"allow_force_pushes\":false}")
echo "   HTTP $C"; show "$C"

echo ""
echo "🎉 Governance setup complete."
echo "   Verify: Settings → Environments  and  Settings → Branches"
