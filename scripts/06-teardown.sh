#!/bin/bash
# ================================================================
# SCRIPT 6 — Teardown (cleanup everything)
# WARNING: Deletes ALL data permanently
# ================================================================
source $(dirname $0)/.env

export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=ap-south-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

echo "=== Deleting K8s resources ==="
kubectl delete namespace ecommerce  --ignore-not-found
kubectl delete namespace monitoring --ignore-not-found

echo "=== Deregistering ALB targets ==="
aws elbv2 delete-listener --listener-arn $LISTENER_80  2>/dev/null || true
aws elbv2 delete-listener --listener-arn $LISTENER_443 2>/dev/null || true
aws elbv2 modify-load-balancer-attributes \
  --load-balancer-arn $ALB_ARN \
  --attributes Key=deletion_protection.enabled,Value=false
aws elbv2 delete-load-balancer --load-balancer-arn $ALB_ARN
aws elbv2 delete-target-group  --target-group-arn $TG_ARN

echo "=== Deleting EKS ==="
aws eks delete-nodegroup --cluster-name ecommerce-cluster --nodegroup-name general-nodes
aws eks delete-nodegroup --cluster-name ecommerce-cluster --nodegroup-name high-mem-nodes
sleep 30
aws eks delete-cluster --name ecommerce-cluster

echo "=== Deleting VPC resources ==="
aws ec2 delete-security-group --group-id $DB_SG  2>/dev/null || true
aws ec2 delete-security-group --group-id $EKS_SG 2>/dev/null || true
aws ec2 delete-security-group --group-id $ALB_SG 2>/dev/null || true

echo "=== Stop Docker ==="
docker compose down -v

echo "✅ Teardown complete"
