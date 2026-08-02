#!/bin/bash
# ================================================================
# SCRIPT 3 — ECR (Elastic Container Registry)
# Private Docker registry inside AWS
# Each service = own repo, image tag = git SHA (immutable)
# ================================================================
set -e
source $(dirname $0)/.env

export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=ap-south-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

ACCOUNT_ID=000000000000
# Floci backs ECR pushes with a real Docker Registry v2 container on host
# port 5100 (not the LocalStack-style ...localhost.localstack.cloud:4566
# hostname — Floci doesn't implement the Docker Registry API there, only
# the ECR control-plane API).
REGISTRY=localhost:5100
SERVICES="user-service product-service order-service inventory-service payment-service notification-service"
# Must be absolute: a relative ROOT (e.g. ".") breaks once the loop below
# cd's into a service directory — "cd $ROOT" stops pointing at the project
# root and silently no-ops, leaving subsequent iterations in the wrong dir.
ROOT=$(cd "$(dirname "$0")/.." && pwd)

echo "=== [1/4] Create ECR Repos ==="
for svc in $SERVICES; do
  # Idempotent. Re-running this script is the normal case (rebuild, retag,
  # redeploy), but create-repository throws RepositoryAlreadyExistsException
  # the second time and `set -e` then kills the script before anything is
  # built or pushed.
  if aws ecr describe-repositories --repository-names ecommerce/$svc >/dev/null 2>&1; then
    echo "  Exists: ecommerce/$svc"
  else
    aws ecr create-repository \
      --repository-name ecommerce/$svc \
      --image-scanning-configuration scanOnPush=true \
      --encryption-configuration encryptionType=AES256
  fi

  # Keep only last 10 images — saves storage cost
  aws ecr put-lifecycle-policy \
    --repository-name ecommerce/$svc \
    --lifecycle-policy-text '{
      "rules": [{"rulePriority":1,"description":"Keep 10","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":10},"action":{"type":"expire"}}]
    }'
  echo "  Created: ecommerce/$svc"
done

echo "=== [2/4] Login to ECR ==="
aws ecr get-login-password | docker login --username AWS --password-stdin $REGISTRY

echo "=== [3/4] Maven Build ==="
for svc in $SERVICES; do
  cd $ROOT/$svc && mvn package -DskipTests -q && cd $ROOT
  echo "  Built: $svc"
done

echo "=== [4/4] Docker Build + Push ==="
# A git SHA is only immutable if the tree actually matches that commit. With
# uncommitted changes the SHA is a lie: the same tag ends up pointing at two
# different builds, and because the deployments use imagePullPolicy IfNotPresent
# a node that already cached the earlier image will keep running the OLD code
# while every dashboard says the new tag is deployed.
IMAGE_TAG=$(git -C $ROOT rev-parse --short HEAD 2>/dev/null || echo "local")
if [ -n "$(git -C $ROOT status --porcelain 2>/dev/null)" ]; then
  IMAGE_TAG="${IMAGE_TAG}-dirty-$(date +%s)"
  echo "  Working tree is dirty — tagging $IMAGE_TAG so this build cannot be"
  echo "  confused with the committed one. Commit before a real release."
fi
echo "  Tag: $IMAGE_TAG"

for svc in $SERVICES; do
  docker build -t $REGISTRY/ecommerce/$svc:$IMAGE_TAG -t $REGISTRY/ecommerce/$svc:latest $ROOT/$svc/
  docker push $REGISTRY/ecommerce/$svc:$IMAGE_TAG
  docker push $REGISTRY/ecommerce/$svc:latest
  echo "  Pushed: $svc:$IMAGE_TAG"
done

echo "REGISTRY=$REGISTRY" >> $(dirname $0)/.env
echo "IMAGE_TAG=$IMAGE_TAG" >> $(dirname $0)/.env
echo "ACCOUNT_ID=$ACCOUNT_ID" >> $(dirname $0)/.env
echo "✅ ECR done. Next: ./scripts/04-eks.sh"
