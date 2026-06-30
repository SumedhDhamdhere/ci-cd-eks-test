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
REGISTRY=$ACCOUNT_ID.dkr.ecr.ap-south-1.localhost.localstack.cloud:4566
SERVICES="user-service product-service order-service inventory-service payment-service notification-service"
ROOT=$(dirname $(dirname $0))

echo "=== [1/4] Create ECR Repos ==="
for svc in $SERVICES; do
  aws ecr create-repository \
    --repository-name ecommerce/$svc \
    --image-scanning-configuration scanOnPush=true \
    --encryption-configuration encryptionType=AES256

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
IMAGE_TAG=$(git -C $ROOT rev-parse --short HEAD 2>/dev/null || echo "local-$(date +%s)")
echo "  Tag: $IMAGE_TAG (git SHA — immutable, rollback-safe)"

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
