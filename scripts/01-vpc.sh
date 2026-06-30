#!/bin/bash
# ================================================================
# SCRIPT 1 — VPC + Subnets + Security Groups on Floci
# Concept:
#   Public subnet  (10.0.1-2.x) → ALB only. Internet can reach here.
#   Private subnet (10.0.3-4.x) → EKS nodes, Kong, all services.
#                                  No direct internet access.
#   Data subnet    (10.0.5-6.x) → PostgreSQL, Redis, Kafka.
#                                  Only private subnet can reach here.
# Security groups = firewall rules per layer
# ================================================================
set -e
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=ap-south-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

echo "=== [1/5] VPC ==="
VPC_ID=$(aws ec2 create-vpc --cidr-block 10.0.0.0/16 --query 'Vpc.VpcId' --output text)
aws ec2 create-tags --resources $VPC_ID --tags Key=Name,Value=ecommerce-vpc
aws ec2 modify-vpc-attribute --vpc-id $VPC_ID --enable-dns-hostnames
aws ec2 modify-vpc-attribute --vpc-id $VPC_ID --enable-dns-support
echo "  VPC: $VPC_ID"

echo "=== [2/5] Internet Gateway ==="
IGW_ID=$(aws ec2 create-internet-gateway --query 'InternetGateway.InternetGatewayId' --output text)
aws ec2 attach-internet-gateway --internet-gateway-id $IGW_ID --vpc-id $VPC_ID
echo "  IGW: $IGW_ID"

echo "=== [3/5] Subnets (3 tiers, 2 AZs each) ==="
PUB1=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.1.0/24 --availability-zone ap-south-1a --query 'Subnet.SubnetId' --output text)
PUB2=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.2.0/24 --availability-zone ap-south-1b --query 'Subnet.SubnetId' --output text)
PRIV1=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.3.0/24 --availability-zone ap-south-1a --query 'Subnet.SubnetId' --output text)
PRIV2=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.4.0/24 --availability-zone ap-south-1b --query 'Subnet.SubnetId' --output text)
DATA1=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.5.0/24 --availability-zone ap-south-1a --query 'Subnet.SubnetId' --output text)
DATA2=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.6.0/24 --availability-zone ap-south-1b --query 'Subnet.SubnetId' --output text)
aws ec2 create-tags --resources $PUB1  --tags Key=Name,Value=public-az1
aws ec2 create-tags --resources $PUB2  --tags Key=Name,Value=public-az2
aws ec2 create-tags --resources $PRIV1 --tags Key=Name,Value=private-az1
aws ec2 create-tags --resources $PRIV2 --tags Key=Name,Value=private-az2
aws ec2 create-tags --resources $DATA1 --tags Key=Name,Value=data-az1
aws ec2 create-tags --resources $DATA2 --tags Key=Name,Value=data-az2
echo "  Public:  $PUB1 / $PUB2"
echo "  Private: $PRIV1 / $PRIV2"
echo "  Data:    $DATA1 / $DATA2"

echo "=== [4/5] Route Tables ==="
# Public → IGW (internet access)
RT_PUB=$(aws ec2 create-route-table --vpc-id $VPC_ID --query 'RouteTable.RouteTableId' --output text)
aws ec2 create-route --route-table-id $RT_PUB --destination-cidr-block 0.0.0.0/0 --gateway-id $IGW_ID
aws ec2 associate-route-table --route-table-id $RT_PUB --subnet-id $PUB1
aws ec2 associate-route-table --route-table-id $RT_PUB --subnet-id $PUB2

# NAT Gateway → private subnets can reach internet outbound (for docker pulls etc)
EIP=$(aws ec2 allocate-address --domain vpc --query 'AllocationId' --output text)
NAT=$(aws ec2 create-nat-gateway --subnet-id $PUB1 --allocation-id $EIP --query 'NatGateway.NatGatewayId' --output text)

# Private → NAT (outbound only, no inbound from internet)
RT_PRIV=$(aws ec2 create-route-table --vpc-id $VPC_ID --query 'RouteTable.RouteTableId' --output text)
aws ec2 create-route --route-table-id $RT_PRIV --destination-cidr-block 0.0.0.0/0 --nat-gateway-id $NAT
aws ec2 associate-route-table --route-table-id $RT_PRIV --subnet-id $PRIV1
aws ec2 associate-route-table --route-table-id $RT_PRIV --subnet-id $PRIV2
aws ec2 associate-route-table --route-table-id $RT_PRIV --subnet-id $DATA1
aws ec2 associate-route-table --route-table-id $RT_PRIV --subnet-id $DATA2
echo "  NAT Gateway: $NAT"

echo "=== [5/5] Security Groups ==="
# ALB SG — only 80/443 from internet
ALB_SG=$(aws ec2 create-security-group --group-name alb-sg --description "ALB Security Group" --vpc-id $VPC_ID --query 'GroupId' --output text)
aws ec2 authorize-security-group-ingress --group-id $ALB_SG --protocol tcp --port 80  --cidr 0.0.0.0/0
aws ec2 authorize-security-group-ingress --group-id $ALB_SG --protocol tcp --port 443 --cidr 0.0.0.0/0
aws ec2 create-tags --resources $ALB_SG --tags Key=Name,Value=alb-sg

# EKS SG — only from ALB SG (Kong on 8000)
EKS_SG=$(aws ec2 create-security-group --group-name eks-sg --description "EKS Security Group" --vpc-id $VPC_ID --query 'GroupId' --output text)
aws ec2 authorize-security-group-ingress --group-id $EKS_SG --protocol tcp --port 8000 --source-group $ALB_SG
aws ec2 authorize-security-group-ingress --group-id $EKS_SG --protocol tcp --port 1024-65535 --source-group $EKS_SG
aws ec2 create-tags --resources $EKS_SG --tags Key=Name,Value=eks-sg

# DB SG — only from EKS SG (postgres, redis, kafka)
DB_SG=$(aws ec2 create-security-group --group-name db-sg --description "Database Security Group" --vpc-id $VPC_ID --query 'GroupId' --output text)
aws ec2 authorize-security-group-ingress --group-id $DB_SG --protocol tcp --port 5432 --source-group $EKS_SG
aws ec2 authorize-security-group-ingress --group-id $DB_SG --protocol tcp --port 6379 --source-group $EKS_SG
aws ec2 authorize-security-group-ingress --group-id $DB_SG --protocol tcp --port 9092 --source-group $EKS_SG
aws ec2 create-tags --resources $DB_SG --tags Key=Name,Value=db-sg

# Save all IDs for next scripts
cat > $(dirname $0)/.env << ENVEOF
VPC_ID=$VPC_ID
PUB1=$PUB1
PUB2=$PUB2
PRIV1=$PRIV1
PRIV2=$PRIV2
DATA1=$DATA1
DATA2=$DATA2
ALB_SG=$ALB_SG
EKS_SG=$EKS_SG
DB_SG=$DB_SG
NAT=$NAT
ENVEOF

echo ""
echo "✅ VPC Done!"
echo "  Public  10.0.1-2.x → ALB (internet facing)"
echo "  Private 10.0.3-4.x → EKS + services (no internet inbound)"
echo "  Data    10.0.5-6.x → Postgres, Redis, Kafka (isolated)"
echo ""
echo "Next: ./scripts/02-ecr.sh"

# ── BONUS: VPC Flow Logs ─────────────────────────────
echo ""
echo "=== [BONUS] VPC Flow Logs ==="
# Flow logs = every network connection logged
# Required for security audit + troubleshooting
FL_BUCKET="ecommerce-vpc-flowlogs-$(date +%s)"
aws s3 mb s3://$FL_BUCKET

aws ec2 create-flow-logs \
  --resource-type VPC \
  --resource-ids $VPC_ID \
  --traffic-type ALL \
  --log-destination-type s3 \
  --log-destination arn:aws:s3:::$FL_BUCKET \
  --log-format '${version} ${account-id} ${interface-id} ${srcaddr} ${dstaddr} ${srcport} ${dstport} ${protocol} ${packets} ${bytes} ${start} ${end} ${action} ${log-status}'

echo "  Flow logs → s3://$FL_BUCKET"
echo "  Logs ALL traffic (accepted + rejected)"
echo "  WHY? If someone tries to hack DB — you see it in flow logs"

# ── BONUS: Network ACLs ──────────────────────────────
echo ""
echo "=== [BONUS] Network ACLs (NACL) ==="
# NACL = subnet level firewall (Security Groups = instance level)
# Defense in depth: NACL first, then Security Group
NACL=$(aws ec2 create-network-acl \
  --vpc-id $VPC_ID \
  --query 'NetworkAcl.NetworkAclId' \
  --output text)

# Allow inbound 443 (HTTPS)
aws ec2 create-network-acl-entry \
  --network-acl-id $NACL \
  --ingress --rule-number 100 \
  --protocol tcp --port-range From=443,To=443 \
  --cidr-block 0.0.0.0/0 --rule-action allow

# Allow inbound 80 (HTTP → will be redirected to 443)
aws ec2 create-network-acl-entry \
  --network-acl-id $NACL \
  --ingress --rule-number 110 \
  --protocol tcp --port-range From=80,To=80 \
  --cidr-block 0.0.0.0/0 --rule-action allow

# Allow inbound ephemeral ports (return traffic)
aws ec2 create-network-acl-entry \
  --network-acl-id $NACL \
  --ingress --rule-number 120 \
  --protocol tcp --port-range From=1024,To=65535 \
  --cidr-block 0.0.0.0/0 --rule-action allow

# Deny everything else
aws ec2 create-network-acl-entry \
  --network-acl-id $NACL \
  --ingress --rule-number 32766 \
  --protocol -1 --port-range From=0,To=65535 \
  --cidr-block 0.0.0.0/0 --rule-action deny

# Allow all outbound
aws ec2 create-network-acl-entry \
  --network-acl-id $NACL \
  --egress --rule-number 100 \
  --protocol -1 --port-range From=0,To=65535 \
  --cidr-block 0.0.0.0/0 --rule-action allow

# Associate with public subnets
aws ec2 replace-network-acl-association \
  --network-acl-id $NACL --association-id $PUB1
aws ec2 replace-network-acl-association \
  --network-acl-id $NACL --association-id $PUB2

echo "  NACL: $NACL"
echo "  Rules: allow 80, 443, ephemeral | deny all else"
echo "  WHY NACL + Security Group both?"
echo "  SG = stateful (return traffic auto-allowed)"
echo "  NACL = stateless (must allow return traffic explicitly)"
echo "  Two layers = defense in depth"
