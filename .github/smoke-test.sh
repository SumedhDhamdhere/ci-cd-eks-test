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
# capture JWT from the login response for the protected endpoints below
TOKEN=$(json token)
AUTH="Authorization: Bearer $TOKEN"
check "GET  /api/users/1" 200 -H "$AUTH" "$BASE/api/users/1"
check "POST /api/users/logout" 200 -H "$AUTH" -X POST "$BASE/api/users/logout?email=$EMAIL"

echo ""
echo "========== PRODUCTS =========="
check "POST /api/products" 200 -X POST "$BASE/api/products" \
  -H 'Content-Type: application/json' \
  -d '{"name":"CI Widget","price":100,"category":"electronics"}'
PID=$(json id); PID=${PID:-1}
echo "     (productId=$PID)"
check "GET  /api/products" 200 "$BASE/api/products"
check "GET  /api/products/$PID" 200 "$BASE/api/products/$PID"
check "GET  /api/products/category/electronics" 200 "$BASE/api/products/category/electronics"
check "GET  /api/products/search?name=Widget" 200 "$BASE/api/products/search?name=Widget"
check "PUT  /api/products/$PID" 200 -X PUT "$BASE/api/products/$PID" \
  -H 'Content-Type: application/json' \
  -d '{"name":"CI Widget v2","price":120,"category":"electronics"}'

echo ""
echo "========== INVENTORY =========="
check "POST /api/inventory/$PID/restock" 200 -X POST "$BASE/api/inventory/$PID/restock" \
  -H 'Content-Type: application/json' -d '{"quantity":100}'
check "GET  /api/inventory/$PID" 200 "$BASE/api/inventory/$PID"

echo ""
echo "========== ORDERS =========="
check "POST /api/orders" 200 -X POST "$BASE/api/orders" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":1,\"shippingAddress\":\"Mumbai\",\"items\":[{\"productId\":$PID,\"productName\":\"CI Widget\",\"quantity\":2,\"price\":100}]}"
OID=$(json id); OID=${OID:-1}
echo "     (orderId=$OID)"
check "GET  /api/orders/$OID" 200 "$BASE/api/orders/$OID"
check "GET  /api/orders/user/1" 200 "$BASE/api/orders/user/1"

echo ""
echo "========== SAGA (order → PAID) =========="
STATUS=""
for i in $(seq 1 40); do
  STATUS=$(curl -fsS "$BASE/api/orders/$OID" | sed -n 's/.*"status":[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
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
curl -s -o /tmp/body.json -X POST "$BASE/api/orders" -H 'Content-Type: application/json' \
  -d "{\"userId\":1,\"shippingAddress\":\"Mumbai\",\"items\":[{\"productId\":$PID,\"productName\":\"CI Widget\",\"quantity\":1,\"price\":100}]}" >/dev/null
COID=$(json id); COID=${COID:-$OID}
check "PUT  /api/orders/$COID/cancel" 200 -X PUT "$BASE/api/orders/$COID/cancel"

echo ""
echo "================================================"
echo "  RESULT: $PASS passed, $FAIL failed"
echo "================================================"
[ "$FAIL" -eq 0 ] || exit 1
