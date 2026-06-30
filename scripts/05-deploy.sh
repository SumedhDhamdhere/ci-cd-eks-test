#!/bin/bash
# ================================================================
# SCRIPT 5 — Deploy Everything to EKS
#
# Order matters:
#   1. Namespaces first (everything lives inside these)
#   2. Secrets + ConfigMaps (services need these to start)
#   3. StatefulSets (Postgres, Redis, Kafka — need stable storage)
#   4. Kong (gateway must be ready before services)
#   5. Microservices (depend on DB + Kafka + Redis)
#   6. Register Kong pods in ALB Target Group
#   7. Monitoring last (not critical path)
# ================================================================
set -e
source $(dirname $0)/.env

export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=ap-south-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

IMAGE_TAG=${1:-$IMAGE_TAG}
echo "Deploying image tag: $IMAGE_TAG"

echo "=== [1/8] Namespaces ==="
kubectl apply -f k8s/namespace/namespace.yaml
echo "  ecommerce + monitoring namespaces ready"

echo "=== [2/8] Secrets + ConfigMaps ==="
# Replace REGISTRY placeholder in configmap
sed -i "s|REGISTRY|$REGISTRY|g" k8s/services/configmap.yaml
kubectl apply -f k8s/services/configmap.yaml
echo "  Secrets + ConfigMaps applied"

echo "=== [3/8] Data Layer — PostgreSQL + Redis + Kafka ==="
kubectl apply -f k8s/postgres/postgres.yaml
kubectl apply -f k8s/redis/redis.yaml
kubectl apply -f k8s/kafka/kafka.yaml

echo "  Waiting for PostgreSQL..."
kubectl wait --for=condition=ready pod -l app=postgres-user      -n ecommerce --timeout=180s
kubectl wait --for=condition=ready pod -l app=postgres-order     -n ecommerce --timeout=180s
kubectl wait --for=condition=ready pod -l app=postgres-product   -n ecommerce --timeout=180s
kubectl wait --for=condition=ready pod -l app=postgres-inventory -n ecommerce --timeout=180s
kubectl wait --for=condition=ready pod -l app=postgres-payment   -n ecommerce --timeout=180s

echo "  Waiting for Redis..."
kubectl wait --for=condition=ready pod -l app=redis -n ecommerce --timeout=120s

echo "  Waiting for Kafka (takes ~60s)..."
kubectl wait --for=condition=ready pod -l app=kafka -n ecommerce --timeout=180s
echo "  ✅ Data layer ready"

echo "=== [4/8] Kong API Gateway ==="
kubectl apply -f k8s/kong/kong.yaml
kubectl wait --for=condition=ready pod -l app=kong -n ecommerce --timeout=120s
echo "  ✅ Kong ready"

echo "=== [5/8] Microservices ==="
# Replace REGISTRY and IMAGE_TAG placeholders
sed "s|REGISTRY|$REGISTRY|g; s|IMAGE_TAG|$IMAGE_TAG|g" \
  k8s/services/deployments.yaml | kubectl apply -f -

SERVICES="user-service product-service order-service inventory-service payment-service notification-service"
for svc in $SERVICES; do
  kubectl rollout status deployment/$svc -n ecommerce --timeout=300s
  echo "  ✅ $svc running"
done

echo "=== [6/8] Register Kong in ALB Target Group ==="
# Get Kong pod IPs (ALB sends traffic directly to pod IPs)
KONG_IPS=$(kubectl get pods -n ecommerce -l app=kong \
  -o jsonpath='{.items[*].status.podIP}')

# Register each Kong pod IP in Target Group
for ip in $KONG_IPS; do
  aws elbv2 register-targets \
    --target-group-arn $TG_ARN \
    --targets Id=$ip,Port=8000
  echo "  Registered Kong pod: $ip:8000 in Target Group"
done

echo "  Waiting for targets to be healthy..."
sleep 30
aws elbv2 describe-target-health \
  --target-group-arn $TG_ARN \
  --query 'TargetHealthDescriptions[*].{IP:Target.Id,Health:TargetHealth.State}'

echo "=== [7/8] Monitoring ==="
kubectl apply -f k8s/monitoring/monitoring.yaml
echo "  Prometheus + Grafana + Loki deployed"

echo "=== [8/8] Verify Everything ==="
echo ""
echo "--- Pods ---"
kubectl get pods -n ecommerce
echo ""
echo "--- Services ---"
kubectl get svc -n ecommerce
echo ""
echo "--- ALB Target Health ---"
aws elbv2 describe-target-health --target-group-arn $TG_ARN

echo ""
echo "================================================"
echo "✅ DEPLOYMENT COMPLETE!"
echo "================================================"
echo ""
echo "API endpoint: https://$ALB_DNS"
echo "Domain:       https://$DOMAIN (after DNS propagates)"
echo "Grafana:      kubectl port-forward svc/grafana 3000:3000 -n monitoring"
echo ""
echo "Test:"
echo "  curl https://$ALB_DNS/api/users/register -k \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"name\":\"Rahul\",\"email\":\"r@test.com\",\"password\":\"pass1234\"}'"
