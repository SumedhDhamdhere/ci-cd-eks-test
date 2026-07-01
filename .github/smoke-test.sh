#!/usr/bin/env bash
# ================================================================
# End-to-end smoke test — drives a real purchase through Kong and
# verifies the ENTIRE event-driven saga completes:
#
#   register user
#     → create product        (fires product.created → inventory row)
#     → restock the product   (so there is stock to reserve)
#     → place an order        (fires order.created)
#        → inventory reserves stock  → inventory.updated{success}
#        → payment charges           → payment.processed{success}
#        → order-service marks order  PAID
#     → poll the order until status == PAID  (fail if CANCELLED / timeout)
#
# Run against a cluster: expects Kong reachable after a port-forward.
# ================================================================
set -euo pipefail

NS=ecommerce
BASE=http://localhost:8000

echo "== Port-forwarding Kong =="
kubectl port-forward svc/kong-proxy 8000:80 -n "$NS" >/tmp/pf.log 2>&1 &
PF_PID=$!
trap 'kill $PF_PID 2>/dev/null || true' EXIT

# Wait for the port-forward + gateway to answer
for i in $(seq 1 20); do
  if curl -fsS "$BASE/api/products" -o /dev/null 2>/dev/null; then break; fi
  echo "waiting for kong... ($i/20)"; sleep 3
done

echo "== 1. Register user =="
curl -fsS -X POST "$BASE/api/users/register" \
  -H 'Content-Type: application/json' \
  -d '{"name":"CI User","email":"ci@test.com","password":"password123"}' | jq -e '.token' >/dev/null
echo "   ✅ registered (userId=1 on fresh DB)"

echo "== 2. Create product =="
PID=$(curl -fsS -X POST "$BASE/api/products" \
  -H 'Content-Type: application/json' \
  -d '{"name":"CI Widget","price":100,"category":"test"}' | jq -r '.id')
echo "   ✅ productId=$PID"

echo "== 3. Restock product (add 100 units) =="
# restock upserts, so it works even if product.created hasn't landed yet
curl -fsS -X POST "$BASE/api/inventory/$PID/restock" \
  -H 'Content-Type: application/json' \
  -d '{"quantity":100}' | jq -e '.quantity >= 100' >/dev/null
echo "   ✅ stock added"

echo "== 4. Place order (2 units) =="
OID=$(curl -fsS -X POST "$BASE/api/orders" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":1,\"shippingAddress\":\"Mumbai\",\"items\":[{\"productId\":$PID,\"productName\":\"CI Widget\",\"quantity\":2,\"price\":100}]}" \
  | jq -r '.id')
echo "   ✅ orderId=$OID (status PENDING)"

echo "== 5. Wait for saga to complete (order → PAID) =="
STATUS=""
for i in $(seq 1 40); do
  STATUS=$(curl -fsS "$BASE/api/orders/$OID" | jq -r '.status')
  echo "   attempt $i: order status = $STATUS"
  case "$STATUS" in
    PAID)      echo "   ✅ SAGA COMPLETE — order PAID (inventory reserved + payment charged)"; exit 0 ;;
    CANCELLED) echo "   ❌ order CANCELLED — saga did not succeed"; exit 1 ;;
  esac
  sleep 3
done

echo "   ❌ order never reached PAID (stuck at '$STATUS' after 120s)"
exit 1
