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

# REGISTRY and IMAGE_TAG are appended to .env by 03-ecr.sh. If that has not run,
# both are empty and every image reference silently becomes
# "/ecommerce/user-service:" — which surfaces as ImagePullBackOff minutes later,
# a long way from the actual cause. Fail here instead.
: "${REGISTRY:?REGISTRY is not set — run scripts/03-ecr.sh first}"
: "${IMAGE_TAG:?IMAGE_TAG is not set — run scripts/03-ecr.sh first, or pass a tag as \$1}"
echo "Deploying $REGISTRY images, tag: $IMAGE_TAG"

echo "=== [1/9] Namespaces ==="
kubectl apply -f k8s/namespace/namespace.yaml
echo "  ecommerce + monitoring namespaces ready"

echo "=== [2/9] Secrets + ConfigMaps ==="
# There is deliberately no `sed -i` here any more. It rewrote a tracked file in
# place on every deploy, and configmap.yaml has no REGISTRY placeholder for it
# to substitute — so it was pure risk for no effect.
kubectl apply -f k8s/services/configmap.yaml

# Prefer the SealedSecret: it is encrypted with the cluster's public key, which
# is what makes it safe to keep in git. Plain secrets.yaml is base64 — encoding,
# not encryption — and is only a fallback for a cluster with no controller.
if kubectl get crd sealedsecrets.bitnami.com >/dev/null 2>&1 && [ -f k8s/security/sealed-secrets.yaml ]; then
  kubectl apply -f k8s/security/sealed-secrets.yaml
  echo "  SealedSecret applied — the controller decrypts it into app-secrets"
else
  echo "  WARNING: no sealed-secrets controller; falling back to plaintext secrets.yaml"
  kubectl apply -f k8s/services/secrets.yaml
fi
echo "  Secrets + ConfigMaps applied"

echo "=== [3/9] Data Layer — PostgreSQL + Redis + Kafka ==="
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

echo "=== [4/9] Kong API Gateway ==="
kubectl apply -f k8s/kong/kong.yaml
kubectl wait --for=condition=ready pod -l app=kong -n ecommerce --timeout=120s
echo "  ✅ Kong ready"

echo "=== [5/9] Microservices ==="
# Replace REGISTRY and IMAGE_TAG placeholders
sed "s|REGISTRY|$REGISTRY|g; s|IMAGE_TAG|$IMAGE_TAG|g" \
  k8s/services/deployments.yaml | kubectl apply -f -

SERVICES="user-service product-service order-service inventory-service payment-service notification-service"
for svc in $SERVICES; do
  kubectl rollout status deployment/$svc -n ecommerce --timeout=300s
  echo "  ✅ $svc running"
done

echo "=== [6/9] Bind Kong to the ALB Target Group ==="
# We no longer register pod IPs by hand.
#
# The old approach took a one-time snapshot of Kong's pod IPs and called
# `aws elbv2 register-targets`. That is correct only until the first pod
# restart — after that the registered IP is dead, the replacement pod is
# unknown to the ALB, and clients get 503s. HPA scale-ups were invisible too.
#
# Instead we hand target membership to the AWS Load Balancer Controller via a
# TargetGroupBinding. It watches the kong-proxy Endpoints and registers /
# deregisters pod IPs continuously. The ALB, listeners and rules stay owned
# by 02-alb.sh — only the target list is delegated.

# The controller must be installed, since it provides the CRD.
if ! kubectl get crd targetgroupbindings.elbv2.k8s.aws >/dev/null 2>&1; then
  echo "  ❌ AWS Load Balancer Controller not found."
  echo "     TargetGroupBinding needs it (it provides the CRD)."
  echo "     Install it, then re-run this script:"
  echo "       helm repo add eks https://aws.github.io/eks-charts && helm repo update"
  echo "       helm install aws-load-balancer-controller eks/aws-load-balancer-controller \\"
  echo "         -n kube-system --set clusterName=\$CLUSTER \\"
  echo "         --set serviceAccount.create=false \\"
  echo "         --set serviceAccount.name=aws-load-balancer-controller"
  exit 1
fi

sed "s|TG_ARN_PLACEHOLDER|$TG_ARN|g; s|ALB_SG_PLACEHOLDER|$ALB_SG|g" \
  k8s/alb/targetgroupbinding.yaml | kubectl apply -f -

echo "  Waiting for the controller to register targets..."
sleep 20
aws elbv2 describe-target-health \
  --target-group-arn $TG_ARN \
  --query 'TargetHealthDescriptions[*].{IP:Target.Id,Health:TargetHealth.State}'
echo "  (targets now stay in sync automatically on restarts and scaling)"

echo "=== [7/9] Network policy + disruption budgets ==="
# These were written but never deployed by this script, so a clean `01..05` run
# produced a cluster with no NetworkPolicy at all — every pod able to reach
# every database, which is exactly what an attacker pod demonstrated.
#
# Applied AFTER the workloads on purpose: default-deny takes effect the moment
# it lands, so the DNS and per-service allow rules in the same file must arrive
# with it, and the pods must already exist to be selected.
kubectl apply -f k8s/security/networkpolicy.yaml
kubectl apply -f k8s/security/pdb.yaml
echo "  $(kubectl get netpol -n ecommerce --no-headers | wc -l) network policies, $(kubectl get pdb -n ecommerce --no-headers | wc -l) disruption budget(s)"

echo "=== [8/9] Monitoring ==="
# The whole directory, not just monitoring.yaml. Prometheus now mounts the
# prometheus-rules ConfigMap and Grafana mounts grafana-dashboards +
# grafana-dashboard-provider, and those live in alerts.yaml and dashboards.yaml.
# Applying only monitoring.yaml leaves both pods stuck in ContainerCreating
# waiting on a ConfigMap nothing ever created.
kubectl apply -f k8s/monitoring/
echo "  Prometheus + Grafana + Loki + kafka-exporter deployed"
echo "  $(kubectl get cm -n monitoring --no-headers | wc -l) config maps, alert rules and dashboards provisioned"

echo "=== [9/9] Verify Everything ==="
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
