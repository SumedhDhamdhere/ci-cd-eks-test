#!/bin/bash
# ================================================================
# SCRIPT 4 — EKS Cluster + Node Groups
#
# EKS = AWS managed Kubernetes
# Node Group = pool of EC2 machines that run pods
# We use 2 node groups:
#   general    = user, product, kong (m5.large)
#   high-mem   = kafka consumers (r5.large — more RAM)
# All nodes in PRIVATE subnets — no direct internet access
# ================================================================
set -e
source $(dirname $0)/.env

export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=ap-south-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# ----------------------------------------------------------------------
# Every create-* call below is guarded so this script can be re-run.
#
# Re-running is the normal case: a deploy fails halfway, you fix it and go
# again. Unguarded, the second run dies on the very first resource with
# EntityAlreadyExists / ResourceInUseException, because `set -e` treats it
# as fatal - so nothing after it ever executes.
# ----------------------------------------------------------------------

# Create an IAM role only if it is missing.
ensure_role() {
  local NAME="$1" DOC="$2"
  if aws iam get-role --role-name "$NAME" >/dev/null 2>&1; then
    echo "  Role exists: $NAME"
  else
    aws iam create-role --role-name "$NAME" --assume-role-policy-document "$DOC" >/dev/null
    echo "  Role created: $NAME"
  fi
}

# attach-role-policy is already idempotent in AWS, but keep it non-fatal so a
# policy that cannot be resolved does not abort the run.
attach() {
  aws iam attach-role-policy --role-name "$1" --policy-arn "$2" >/dev/null 2>&1 \
    || echo "  WARNING: could not attach $2 to $1"
}

echo "=== [1/5] IAM Role — EKS Cluster ==="
ensure_role eks-cluster-role '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"eks.amazonaws.com"},"Action":"sts:AssumeRole"}]
  }'
aws iam attach-role-policy --role-name eks-cluster-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonEKSClusterPolicy
echo "  Cluster role created"

echo "=== [2/5] IAM Role — EKS Nodes ==="
ensure_role eks-node-role '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]
  }'
attach eks-node-role arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy
attach eks-node-role arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
attach eks-node-role arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy
echo "  Node role created"

echo "=== [3/5] EKS Cluster ==="
# Re-running is normal, but a cluster left FAILED by an earlier attempt will
# never reach ACTIVE - the waiter below simply blocks and then reports a
# terminal failure with no reason attached. Clear it first.
SKIP_CREATE=""
STATUS=$(aws eks describe-cluster --name ecommerce-cluster --query 'cluster.status' --output text 2>/dev/null || echo NONE)
if [ "$STATUS" = "ACTIVE" ]; then
  echo "  Cluster already ACTIVE - reusing it"
  SKIP_CREATE=1
elif [ "$STATUS" != "NONE" ]; then
  echo "  Cluster is $STATUS - deleting it before recreating"
  aws eks delete-cluster --name ecommerce-cluster >/dev/null 2>&1 || true

  # Wait for the delete to actually finish. It is asynchronous: a fixed
  # `sleep 5` is not enough, and the deletion then lands AFTER the new cluster
  # has been created and tears it straight back down. The symptom is brutal to
  # read - the new k3s comes up healthy, serves for ~20s, then takes a SIGTERM
  # out of nowhere:
  #     level=warning msg="signal received: \"terminated\", canceling context..."
  # and kubectl starts refusing connections on a cluster AWS still calls ACTIVE.
  echo "  Waiting for the delete to complete..."
  for i in $(seq 1 60); do
    aws eks describe-cluster --name ecommerce-cluster >/dev/null 2>&1 || break
    sleep 2
  done
  # And wait for Floci to release the container/port behind it.
  for i in $(seq 1 30); do
    docker ps -a --filter "name=floci-eks-ecommerce-cluster" --format '{{.Names}}' \
      | grep -q . || break
    sleep 2
  done
  docker rm -f floci-eks-ecommerce-cluster >/dev/null 2>&1 || true
  echo "  Previous cluster gone"
fi

# Floci backs every EKS cluster with a k3s container that binds host port
# 6500. A leftover container from a previously-named cluster still holds it,
# and the only symptom is the create landing in FAILED with this buried in
# Floci's own logs:
#   Bind for 0.0.0.0:6500 failed: port is already allocated
# Detect that up front and say so, instead of failing opaquely minutes later.
if [ -z "$SKIP_CREATE" ]; then
  STALE=$(docker ps -a --filter "publish=6500" --format '{{.Names}}' 2>/dev/null | grep -v "^floci-eks-ecommerce-cluster$" || true)
  if [ -n "$STALE" ]; then
    echo "  ERROR: host port 6500 is held by another k3s container: $STALE"
    echo "         That is a previous EKS cluster whose AWS-side record is gone."
    echo "         Remove it and re-run:  docker rm -f $STALE"
    exit 1
  fi
fi

[ -n "$SKIP_CREATE" ] || aws eks create-cluster \
  --name ecommerce-cluster \
  --kubernetes-version 1.28 \
  --role-arn arn:aws:iam::000000000000:role/eks-cluster-role \
  --resources-vpc-config subnetIds=$PRIV1,$PRIV2,securityGroupIds=$EKS_SG \
  --logging '{"clusterLogging":[{"types":["api","audit","authenticator","controllerManager","scheduler"],"enabled":true}]}'

echo "  Waiting for cluster ACTIVE..."
[ -n "$SKIP_CREATE" ] || aws eks wait cluster-active --name ecommerce-cluster
echo "  Cluster ACTIVE"

echo "=== [4/5] Node Groups ==="

# Same idempotency guard as everything else: create-nodegroup throws
# ResourceInUseException on a re-run and `set -e` stops the script before it
# ever reaches the kubectl configuration in step 5 — leaving a working cluster
# that kubectl still cannot talk to.
ng_exists() {
  aws eks describe-nodegroup --cluster-name ecommerce-cluster \
    --nodegroup-name "$1" >/dev/null 2>&1
}

# General nodes — user, product, order, kong
ng_exists general-nodes || aws eks create-nodegroup \
  --cluster-name ecommerce-cluster \
  --nodegroup-name general-nodes \
  --node-role arn:aws:iam::000000000000:role/eks-node-role \
  --subnets $PRIV1 $PRIV2 \
  --instance-types m5.large \
  --scaling-config minSize=2,maxSize=8,desiredSize=3 \
  --disk-size 20 \
  --ami-type AL2_x86_64 \
  --labels role=general \
  --tags Environment=production,Team=ecommerce

# High memory nodes — kafka consumers, payment
ng_exists high-mem-nodes || aws eks create-nodegroup \
  --cluster-name ecommerce-cluster \
  --nodegroup-name high-mem-nodes \
  --node-role arn:aws:iam::000000000000:role/eks-node-role \
  --subnets $PRIV1 $PRIV2 \
  --instance-types r5.large \
  --scaling-config minSize=1,maxSize=4,desiredSize=2 \
  --disk-size 30 \
  --ami-type AL2_x86_64 \
  --labels role=high-memory \
  --tags Environment=production,Team=ecommerce

echo "  Waiting for node groups..."
aws eks wait nodegroup-active --cluster-name ecommerce-cluster --nodegroup-name general-nodes
echo "  general-nodes ACTIVE"

echo "=== [5/5] Configure kubectl ==="
aws eks update-kubeconfig --name ecommerce-cluster --region ap-south-1
kubectl get nodes

echo "=== [FLOCI FIX] Configure k3s to pull from Floci ECR ==="
# k3s runs inside a Floci-managed Docker container. By default it tries to pull
# images from Docker Hub. We redirect localhost:5100 to the Floci ECR registry
# container (floci-ecr-registry:5000) which is on the same Docker network.
K3S_CONTAINER=$(docker ps --filter "name=floci-eks" --format "{{.Names}}" | head -1)
if [ -n "$K3S_CONTAINER" ]; then
  # Floci writes this same file, but points the mirror at the container NAME
  # "floci-ecr-registry". Both containers sit on Docker's default bridge, and
  # the default bridge has no embedded DNS — container names only resolve on
  # user-defined networks. So every pull died with:
  #   failed to resolve reference "localhost:5100/ecommerce/user-service:...":
  #   dial tcp: lookup floci-ecr-registry: no such host
  #
  # Use the registry's IP instead of its name. Editing /etc/hosts is not an
  # option here: Docker bind-mounts it, so `sed -i` fails with
  # "can't move '/etc/hostsXXXXXX' to '/etc/hosts': Resource busy".
  #
  # Re-derived on every run — the bridge IP is not stable across restarts.
  REG_IP=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$v.IPAddress}}{{end}}' floci-ecr-registry 2>/dev/null)
  if [ -z "$REG_IP" ]; then
    echo "  ⚠️  floci-ecr-registry container not found — image pulls will fail"
  else
    docker exec "$K3S_CONTAINER" sh -c "mkdir -p /etc/rancher/k3s && cat > /etc/rancher/k3s/registries.yaml <<EOF
mirrors:
  \"localhost:5100\":
    endpoint:
      - \"http://$REG_IP:5000\"
EOF
"
    # Reload k3s config without restarting
    docker exec "$K3S_CONTAINER" sh -c 'kill -SIGHUP 1 2>/dev/null || true'
    echo "  ✅ k3s pulls localhost:5100 from the Floci ECR registry at $REG_IP:5000"
  fi
else
  echo "  ⚠️  Could not find Floci k3s container. If pods get ImagePullBackOff, run manually:"
  echo "      docker exec <floci-k3s-container> sh -c 'mkdir -p /etc/rancher/k3s && echo ...'"
fi

echo "REGISTRY=localhost:5100" >> $(dirname $0)/.env
echo "EKS_CLUSTER=ecommerce-cluster" >> $(dirname $0)/.env
echo "✅ EKS done. Next: ./scripts/05-deploy.sh"
