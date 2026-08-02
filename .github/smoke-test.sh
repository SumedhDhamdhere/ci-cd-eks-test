#!/usr/bin/env bash
# ================================================================
# End-to-end smoke test — exercises EVERY REST endpoint through Kong
# and verifies the full event-driven saga completes (order → PAID).
#
# Runs in two modes:
#   • CI (Kubernetes):  no KONG_URL set → port-forwards svc/kong-proxy
#   • Local (compose):  KONG_URL=http://localhost:8000 bash .github/smoke-test.sh
#
# Endpoints covered (16):
#   users:     register, login, logout, get
#   products:  create, list, get, category, search, update
#   inventory: restock, get
#   orders:    create, get, list-by-user, cancel
#   (payment + notification have no REST API — verified via the saga)
#
# AUTHORISATION
#   Every service now verifies the JWT for itself. Before that, five of the six
#   answered anonymous callers — `POST /api/orders` with no Authorization header
#   at all returned 200 — so this script used to pass without ever sending a
#   token. It now runs as two real principals:
#
#     USER   browses, orders, and may only touch its OWN orders
#     ADMIN  creates/edits products and restocks inventory
#
#   The negative checks at the end are the point of the exercise: they fail if
#   the authorisation ever regresses, which is the failure mode nothing else
#   here would notice.
# ================================================================
set -uo pipefail

NS=ecommerce
PASS=0; FAIL=0
PF_PID=""

# ---- decide how to reach Kong ----
BASE="${KONG_URL:-}"
if [ -z "$BASE" ]; then
  echo "== Port-forwarding Kong (k8s mode) =="
  kubectl port-forward svc/kong-proxy 8000:80 -n "$NS" >/tmp/pf.log 2>&1 &
  PF_PID=$!
  BASE="http://localhost:8000"
fi
trap '[ -n "$PF_PID" ] && kill $PF_PID 2>/dev/null || true' EXIT

# ---- helpers ----
# check <name> <expected-http> <curl-args...>
check() {
  local name="$1"; local want="$2"; shift 2
  local got
  got=$(curl -s -o /tmp/body.json -w "%{http_code}" "$@")
  if [ "$got" = "$want" ]; then
    echo "  ✅ $name  ($got)"; PASS=$((PASS+1))
  else
    echo "  ❌ $name  (got $got, want $want)"; echo "     $(head -c 300 /tmp/body.json)"; FAIL=$((FAIL+1))
  fi
}
json() { sed -n "s/.*\"$1\":[[:space:]]*\"\{0,1\}\([^,\"}]*\).*/\1/p" /tmp/body.json | head -1; }

# Reads a claim out of the JWT payload. The user id lives in the token, not in
# the login response, because that is the whole point — identity the caller
# cannot edit.
claim() {
  local tok="$1" name="$2" payload
  payload=$(echo "$tok" | cut -d. -f2 | tr '_-' '/+')
  case $(( ${#payload} % 4 )) in 2) payload="$payload==";; 3) payload="$payload=";; esac
  echo "$payload" | base64 -d 2>/dev/null | sed -n "s/.*\"$name\":[[:space:]]*\"\{0,1\}\([^,\"}]*\).*/\1/p" | head -1
}

# ---- wait for gateway ----
for i in $(seq 1 30); do
  curl -fsS "$BASE/api/products" -o /dev/null 2>/dev/null && break
  echo "waiting for kong... ($i/30)"; sleep 3
done

echo ""
echo "========== USERS =========="
EMAIL="ci_$(date +%s)@test.com"
check "POST /api/users/register" 200 -X POST "$BASE/api/users/register" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"CI User\",\"email\":\"$EMAIL\",\"password\":\"password123\"}"
check "POST /api/users/login" 200 -X POST "$BASE/api/users/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"password123\"}"
TOKEN=$(json token)
AUTH="Authorization: Bearer $TOKEN"
UID_USER=$(claim "$TOKEN" userId)
echo "     (userId=$UID_USER)"
check "GET  /api/users/$UID_USER" 200 -H "$AUTH" "$BASE/api/users/$UID_USER"

# ---- an ADMIN principal ----
# Product and inventory writes are back-office operations and now require
# ROLE_ADMIN. Registration always yields ROLE_USER, so promote one in the
# database — the same thing an operator would do once, by hand.
ADMIN_EMAIL="ci_admin_$(date +%s)@test.com"
curl -s -o /dev/null -X POST "$BASE/api/users/register" -H 'Content-Type: application/json' \
  -d "{\"name\":\"CI Admin\",\"email\":\"$ADMIN_EMAIL\",\"password\":\"password123\"}"
ADMIN_AUTH=""
ADMIN_ROLE=""
if command -v kubectl >/dev/null 2>&1; then
  # The database user is not always "postgres" — CI builds app-secrets from
  # GitHub secrets, so read whatever the services themselves are configured
  # with instead of hardcoding one.
  DB_USER=$(kubectl get secret app-secrets -n "$NS" -o jsonpath='{.data.USER_DB_USER}' 2>/dev/null | base64 -d 2>/dev/null)
  DB_USER="${DB_USER:-postgres}"
  if ! PROMOTE_OUT=$(kubectl exec -n "$NS" postgres-user-0 -- \
        psql -U "$DB_USER" -d userdb -c "UPDATE users SET role='ADMIN' WHERE email='$ADMIN_EMAIL';" 2>&1); then
    # Do not swallow this. It was hidden behind 2>&1 >/dev/null, the script
    # carried on with a USER token, and six later checks failed with 403 while
    # the real cause sat one line above them, unprinted.
    echo "     promotion failed (db user '$DB_USER'): $PROMOTE_OUT"
  fi
  curl -s -o /tmp/body.json -X POST "$BASE/api/users/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"password123\"}"
  ADMIN_TOKEN=$(json token)
  ADMIN_ROLE=$(claim "$ADMIN_TOKEN" role)
  # A token is not enough — it has to actually carry ROLE_ADMIN.
  [ "$ADMIN_ROLE" = "ADMIN" ] && ADMIN_AUTH="Authorization: Bearer $ADMIN_TOKEN"
fi
if [ -z "$ADMIN_AUTH" ]; then
  echo "  ❌ no ADMIN token (role='$ADMIN_ROLE') — admin-only endpoints cannot be tested"
  FAIL=$((FAIL+1))
  ADMIN_AUTH="$AUTH"
else
  echo "  ✅ ADMIN token obtained (role=$ADMIN_ROLE)"
  PASS=$((PASS+1))
fi

echo ""
echo "========== PRODUCTS =========="
check "POST /api/products (admin)" 200 -X POST "$BASE/api/products" \
  -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"CI Widget","price":100,"category":"electronics"}'
PID=$(json id); PID=${PID:-1}
echo "     (productId=$PID)"
# Browsing the catalogue stays public — no token on purpose.
check "GET  /api/products" 200 "$BASE/api/products"
check "GET  /api/products/$PID" 200 "$BASE/api/products/$PID"
check "GET  /api/products/category/electronics" 200 "$BASE/api/products/category/electronics"
check "GET  /api/products/search?name=Widget" 200 "$BASE/api/products/search?name=Widget"
check "PUT  /api/products/$PID (admin)" 200 -X PUT "$BASE/api/products/$PID" \
  -H "$ADMIN_AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"CI Widget v2","price":120,"category":"electronics"}'

echo ""
echo "========== INVENTORY =========="
check "POST /api/inventory/$PID/restock (admin)" 200 -X POST "$BASE/api/inventory/$PID/restock" \
  -H "$ADMIN_AUTH" -H 'Content-Type: application/json' -d '{"quantity":100}'
check "GET  /api/inventory/$PID" 200 -H "$AUTH" "$BASE/api/inventory/$PID"

echo ""
echo "========== ORDERS =========="
# No userId in the body — the owner comes from the token.
check "POST /api/orders" 200 -X POST "$BASE/api/orders" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"shippingAddress\":\"Mumbai\",\"items\":[{\"productId\":$PID,\"productName\":\"CI Widget\",\"quantity\":2,\"price\":100}]}"
OID=$(json id); OID=${OID:-1}
OWNER=$(json userId)
echo "     (orderId=$OID, userId=$OWNER)"
if [ "$OWNER" = "$UID_USER" ]; then
  echo "  ✅ order booked against the caller in the token"; PASS=$((PASS+1))
else
  echo "  ❌ order owner is $OWNER, expected $UID_USER"; FAIL=$((FAIL+1))
fi
check "GET  /api/orders/$OID" 200 -H "$AUTH" "$BASE/api/orders/$OID"
check "GET  /api/orders/user/$UID_USER" 200 -H "$AUTH" "$BASE/api/orders/user/$UID_USER"

echo ""
echo "========== SAGA (order → PAID) =========="
STATUS=""
for i in $(seq 1 40); do
  STATUS=$(curl -fsS -H "$AUTH" "$BASE/api/orders/$OID" | sed -n 's/.*"status":[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
  echo "  attempt $i: status=$STATUS"
  case "$STATUS" in
    PAID)      echo "  ✅ saga complete — order PAID"; PASS=$((PASS+1)); break ;;
    CANCELLED) echo "  ❌ order CANCELLED"; FAIL=$((FAIL+1)); break ;;
  esac
  sleep 3
done
[ "$STATUS" != "PAID" ] && { echo "  ❌ saga never reached PAID (stuck at '$STATUS')"; FAIL=$((FAIL+1)); }

# Place a fresh order we can cancel (leave the saga order intact)
echo ""
echo "========== CANCEL =========="
curl -s -o /tmp/body.json -X POST "$BASE/api/orders" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"shippingAddress\":\"Mumbai\",\"items\":[{\"productId\":$PID,\"productName\":\"CI Widget\",\"quantity\":1,\"price\":100}]}" >/dev/null
COID=$(json id); COID=${COID:-$OID}
check "PUT  /api/orders/$COID/cancel" 200 -X PUT "$BASE/api/orders/$COID/cancel" -H "$AUTH"

echo ""
echo "========== AUTHORISATION =========="
# Each of these returned 200 before the services verified tokens for themselves.
ORDER_BODY="{\"shippingAddress\":\"x\",\"items\":[{\"productId\":$PID,\"productName\":\"CI Widget\",\"quantity\":1,\"price\":100}]}"
check "POST /api/orders           no token   → denied" 403 -X POST "$BASE/api/orders" \
  -H 'Content-Type: application/json' -d "$ORDER_BODY"
check "POST /api/orders           bad token  → denied" 403 -X POST "$BASE/api/orders" \
  -H 'Authorization: Bearer not.a.real.token' -H 'Content-Type: application/json' -d "$ORDER_BODY"
check "POST /api/products         no token   → denied" 403 -X POST "$BASE/api/products" \
  -H 'Content-Type: application/json' -d '{"name":"x","price":1,"category":"c"}'
check "POST /api/products         USER role  → denied" 403 -X POST "$BASE/api/products" \
  -H "$AUTH" -H 'Content-Type: application/json' -d '{"name":"x","price":1,"category":"c"}'
check "POST restock               USER role  → denied" 403 -X POST "$BASE/api/inventory/$PID/restock" \
  -H "$AUTH" -H 'Content-Type: application/json' -d '{"quantity":9999}'
# Cross-user access, checked with a SECOND ORDINARY USER — not the admin.
# ADMIN is allowed to read anyone's orders by design (AuthenticatedUser.isAdmin),
# so testing this with the admin token proves nothing; it returns 200 correctly.
OTHER_EMAIL="ci_other_$(date +%s)@test.com"
curl -s -o /dev/null -X POST "$BASE/api/users/register" -H 'Content-Type: application/json' \
  -d "{\"name\":\"CI Other\",\"email\":\"$OTHER_EMAIL\",\"password\":\"password123\"}"
curl -s -o /tmp/body.json -X POST "$BASE/api/users/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$OTHER_EMAIL\",\"password\":\"password123\"}"
OTHER_AUTH="Authorization: Bearer $(json token)"

# Someone else's order history: 403.
check "GET  /api/orders/user/<other user>    → denied" 403 \
  -H "$OTHER_AUTH" "$BASE/api/orders/user/$UID_USER"
# A specific order that is not yours: 404 on purpose, not 403 — 403 would
# confirm the id is real and let an attacker walk the order table id by id.
check "GET  /api/orders/<other user's order> → 404 not 403" 404 \
  -H "$OTHER_AUTH" "$BASE/api/orders/$OID"
check "PUT  /api/orders/<other user's>/cancel → denied" 404 \
  -X PUT -H "$OTHER_AUTH" "$BASE/api/orders/$OID/cancel"
check "POST /api/users/login      no password → 400 not 403" 400 -X POST "$BASE/api/users/login" \
  -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\"}"

# A userId in the body must be ignored, not obeyed.
curl -s -o /tmp/body.json -X POST "$BASE/api/orders" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"userId\":999999,\"shippingAddress\":\"forged\",\"items\":[{\"productId\":$PID,\"productName\":\"CI Widget\",\"quantity\":1,\"price\":100}]}" >/dev/null
FORGED=$(json userId)
if [ "$FORGED" = "$UID_USER" ]; then
  echo "  ✅ forged userId in body ignored (booked as $FORGED)"; PASS=$((PASS+1))
else
  echo "  ❌ forged userId honoured — order booked as $FORGED, caller is $UID_USER"; FAIL=$((FAIL+1))
fi

# Logout last: it clears the Redis session, after which user-service rejects
# this token. Doing it earlier would break every check above.
echo ""
check "POST /api/users/logout" 200 -H "$AUTH" -X POST "$BASE/api/users/logout?email=$EMAIL"

echo ""
echo "================================================"
echo "  RESULT: $PASS passed, $FAIL failed"
echo "================================================"
[ "$FAIL" -eq 0 ] || exit 1
