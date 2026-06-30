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

echo "=== [1/5] IAM Role — EKS Cluster ==="
aws iam create-role \
  --role-name eks-cluster-role \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"eks.amazonaws.com"},"Action":"sts:AssumeRole"}]
  }'
aws iam attach-role-policy --role-name eks-cluster-role \
  --policy-arn arn:aws:iam::aws:policy/AmazonEKSClusterPolicy
echo "  Cluster role created"

echo "=== [2/5] IAM Role — EKS Nodes ==="
aws iam create-role \
  --role-name eks-node-role \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]
  }'
aws iam attach-role-policy --role-name eks-node-role --policy-arn arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy
aws iam attach-role-policy --role-name eks-node-role --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
aws iam attach-role-policy --role-name eks-node-role --policy-arn arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy
echo "  Node role created"

echo "=== [3/5] EKS Cluster ==="
aws eks create-cluster \
  --name ecommerce-cluster \
  --kubernetes-version 1.28 \
  --role-arn arn:aws:iam::000000000000:role/eks-cluster-role \
  --resources-vpc-config subnetIds=$PRIV1,$PRIV2,securityGroupIds=$EKS_SG \
  --logging '{"clusterLogging":[{"types":["api","audit","authenticator","controllerManager","scheduler"],"enabled":true}]}'

echo "  Waiting for cluster ACTIVE..."
aws eks wait cluster-active --name ecommerce-cluster
echo "  Cluster ACTIVE"

echo "=== [4/5] Node Groups ==="
# General nodes — user, product, order, kong
aws eks create-nodegroup \
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
aws eks create-nodegroup \
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

echo "EKS_CLUSTER=ecommerce-cluster" >> $(dirname $0)/.env
echo "✅ EKS done. Next: ./scripts/05-deploy.sh"
