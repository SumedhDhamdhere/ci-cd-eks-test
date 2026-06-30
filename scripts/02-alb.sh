#!/bin/bash
# ================================================================
# SCRIPT 2 — ALB (Application Load Balancer) — Full Production Setup
#
# What this creates:
#   1. ACM SSL Certificate (HTTPS)
#   2. ALB (Application Load Balancer) in PUBLIC subnets
#   3. Target Group (where ALB sends traffic → Kong pods)
#   4. Listener port 80  → redirect to 443
#   5. Listener port 443 → forward to Target Group
#   6. Health checks on Target Group
#   7. WAF attach to ALB
#   8. Access logs → S3 bucket
#   9. ALB deletion protection
#  10. Route 53 record → ALB
#
# Why ALB and not just K8s LoadBalancer?
#   K8s type:LoadBalancer = basic NLB (layer 4, TCP only)
#   ALB = layer 7, HTTP/HTTPS aware, path routing, WAF support
#   Production ALWAYS uses ALB for HTTP workloads
# ================================================================
set -e
source $(dirname $0)/.env

export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=ap-south-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

DOMAIN="ecommerce.local"   # Change to your real domain in prod

echo ""
echo "================================================"
echo " ALB Full Production Setup"
echo "================================================"

# ── STEP 1: ACM SSL Certificate ──────────────────────
echo ""
echo "=== [1/10] ACM SSL Certificate ==="
# In production: ACM validates you own the domain via DNS or email
# Floci simulates this — in real AWS it takes ~5 min to validate
CERT_ARN=$(aws acm request-certificate \
  --domain-name $DOMAIN \
  --subject-alternative-names "*.$DOMAIN" \
  --validation-method DNS \
  --query 'CertificateArn' \
  --output text)

echo "  Certificate ARN: $CERT_ARN"
echo "  Domain: $DOMAIN + *.$DOMAIN (wildcard)"
echo "  Validation: DNS (add CNAME record to Route 53)"
echo ""
echo "  WHY SSL?"
echo "  Production never uses HTTP — all traffic must be HTTPS"
echo "  ACM = free SSL cert, auto-renews, managed by AWS"

# ── STEP 2: S3 Bucket for ALB Access Logs ────────────
echo ""
echo "=== [2/10] S3 Bucket for ALB Access Logs ==="
# Every request to ALB gets logged — required for audit + debugging
LOG_BUCKET="ecommerce-alb-logs-$(date +%s)"
aws s3 mb s3://$LOG_BUCKET

# Enable versioning on log bucket
aws s3api put-bucket-versioning \
  --bucket $LOG_BUCKET \
  --versioning-configuration Status=Enabled

# Bucket policy — allow ALB to write logs
aws s3api put-bucket-policy \
  --bucket $LOG_BUCKET \
  --policy "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [{
      \"Effect\": \"Allow\",
      \"Principal\": {\"Service\": \"delivery.logs.amazonaws.com\"},
      \"Action\": \"s3:PutObject\",
      \"Resource\": \"arn:aws:s3:::$LOG_BUCKET/alb-logs/AWSLogs/*\"
    }]
  }"

echo "  Log bucket: $LOG_BUCKET"
echo ""
echo "  WHY ACCESS LOGS?"
echo "  Every request logged: IP, timestamp, URL, response code, latency"
echo "  Required for: debugging, security audit, compliance"

# ── STEP 3: WAF Web ACL ──────────────────────────────
echo ""
echo "=== [3/10] WAF Web ACL (Firewall) ==="
WAF_ACL=$(aws wafv2 create-web-acl \
  --name ecommerce-waf \
  --scope REGIONAL \
  --default-action Allow={} \
  --rules '[
    {
      "Name": "AWSManagedRulesCommonRuleSet",
      "Priority": 1,
      "OverrideAction": {"None": {}},
      "Statement": {
        "ManagedRuleGroupStatement": {
          "VendorName": "AWS",
          "Name": "AWSManagedRulesCommonRuleSet"
        }
      },
      "VisibilityConfig": {
        "SampledRequestsEnabled": true,
        "CloudWatchMetricsEnabled": true,
        "MetricName": "CommonRuleSet"
      }
    },
    {
      "Name": "RateLimitRule",
      "Priority": 2,
      "Action": {"Block": {}},
      "Statement": {
        "RateBasedStatement": {
          "Limit": 2000,
          "AggregateKeyType": "IP"
        }
      },
      "VisibilityConfig": {
        "SampledRequestsEnabled": true,
        "CloudWatchMetricsEnabled": true,
        "MetricName": "RateLimit"
      }
    },
    {
      "Name": "SQLInjectionRule",
      "Priority": 3,
      "OverrideAction": {"None": {}},
      "Statement": {
        "ManagedRuleGroupStatement": {
          "VendorName": "AWS",
          "Name": "AWSManagedRulesSQLiRuleSet"
        }
      },
      "VisibilityConfig": {
        "SampledRequestsEnabled": true,
        "CloudWatchMetricsEnabled": true,
        "MetricName": "SQLi"
      }
    }
  ]' \
  --visibility-config SampledRequestsEnabled=true,CloudWatchMetricsEnabled=true,MetricName=ecommerce-waf \
  --query 'Summary.ARN' \
  --output text)

echo "  WAF ACL ARN: $WAF_ACL"
echo ""
echo "  WAF Rules:"
echo "    - AWSManagedRulesCommonRuleSet → OWASP Top 10 protection"
echo "    - RateLimitRule → block IPs with >2000 req/5min"
echo "    - SQLInjectionRule → block SQL injection attempts"

# ── STEP 4: Create ALB ───────────────────────────────
echo ""
echo "=== [4/10] Application Load Balancer ==="
ALB_ARN=$(aws elbv2 create-load-balancer \
  --name ecommerce-alb \
  --subnets $PUB1 $PUB2 \
  --security-groups $ALB_SG \
  --scheme internet-facing \
  --type application \
  --ip-address-type ipv4 \
  --query 'LoadBalancers[0].LoadBalancerArn' \
  --output text)

ALB_DNS=$(aws elbv2 describe-load-balancers \
  --load-balancer-arns $ALB_ARN \
  --query 'LoadBalancers[0].DNSName' \
  --output text)

echo "  ALB ARN: $ALB_ARN"
echo "  ALB DNS: $ALB_DNS"
echo ""
echo "  WHY PUBLIC SUBNETS for ALB?"
echo "  ALB needs internet access to receive traffic"
echo "  But ALB only forwards to PRIVATE subnet (Kong)"
echo "  So internet → ALB (public) → Kong (private) — secure!"

# ── STEP 5: Enable ALB Access Logs ───────────────────
echo ""
echo "=== [5/10] Enable ALB Access Logs → S3 ==="
aws elbv2 modify-load-balancer-attributes \
  --load-balancer-arn $ALB_ARN \
  --attributes \
    Key=access_logs.s3.enabled,Value=true \
    Key=access_logs.s3.bucket,Value=$LOG_BUCKET \
    Key=access_logs.s3.prefix,Value=alb-logs \
    Key=deletion_protection.enabled,Value=true \
    Key=idle_timeout.timeout_seconds,Value=60 \
    Key=routing.http2.enabled,Value=true \
    Key=routing.http.drop_invalid_header_fields.enabled,Value=true

echo "  Access logs → s3://$LOG_BUCKET/alb-logs/"
echo "  Deletion protection: ENABLED"
echo "  Idle timeout: 60 seconds"
echo "  HTTP/2: ENABLED"
echo ""
echo "  WHY DELETION PROTECTION?"
echo "  Prevents accidental 'aws elbv2 delete-load-balancer'"
echo "  Must explicitly disable before deleting"

# ── STEP 6: Target Group ─────────────────────────────
echo ""
echo "=== [6/10] Target Group ==="
# Target Group = pool of backends ALB routes to
# type=ip means ALB directly hits pod IPs (not node IPs)
# This is required for EKS with CNI networking
TG_ARN=$(aws elbv2 create-target-group \
  --name ecommerce-kong-tg \
  --protocol HTTP \
  --port 8000 \
  --vpc-id $VPC_ID \
  --target-type ip \
  --health-check-enabled \
  --health-check-protocol HTTP \
  --health-check-port 8100 \
  --health-check-path /status \
  --health-check-interval-seconds 15 \
  --health-check-timeout-seconds 5 \
  --healthy-threshold-count 2 \
  --unhealthy-threshold-count 3 \
  --matcher HttpCode=200 \
  --query 'TargetGroups[0].TargetGroupArn' \
  --output text)

echo "  Target Group ARN: $TG_ARN"
echo "  Target type: ip (directly hits Kong pod IPs)"
echo "  Health check: GET /status port 8100 → expects 200"
echo "  Healthy threshold: 2 consecutive successes"
echo "  Unhealthy threshold: 3 consecutive failures"
echo ""
echo "  HOW HEALTH CHECKS WORK:"
echo "  ALB pings /status every 15 seconds"
echo "  If Kong pod fails 3 times → ALB removes it from rotation"
echo "  If Kong pod recovers 2 times → ALB adds it back"
echo "  Zero downtime even when pods restart"

# ── STEP 7: Modify Target Group Attributes ───────────
echo ""
echo "=== [7/10] Target Group Attributes ==="
aws elbv2 modify-target-group-attributes \
  --target-group-arn $TG_ARN \
  --attributes \
    Key=deregistration_delay.timeout_seconds,Value=30 \
    Key=stickiness.enabled,Value=false \
    Key=load_balancing.algorithm.type,Value=least_outstanding_requests

echo "  Deregistration delay: 30s"
echo "  Stickiness: DISABLED (stateless services use Redis for sessions)"
echo "  Algorithm: least_outstanding_requests"
echo ""
echo "  WHY DEREGISTRATION DELAY 30s?"
echo "  When pod is terminating, ALB waits 30s before removing it"
echo "  Allows in-flight requests to complete = zero dropped requests"
echo ""
echo "  WHY least_outstanding_requests?"
echo "  Sends new request to pod with FEWEST active requests"
echo "  Better than round-robin for variable request duration"

# ── STEP 8: ALB Listeners ────────────────────────────
echo ""
echo "=== [8/10] ALB Listeners ==="

# Listener 80 → redirect to 443 (force HTTPS)
LISTENER_80=$(aws elbv2 create-listener \
  --load-balancer-arn $ALB_ARN \
  --protocol HTTP \
  --port 80 \
  --default-actions '[{
    "Type": "redirect",
    "RedirectConfig": {
      "Protocol": "HTTPS",
      "Port": "443",
      "StatusCode": "HTTP_301"
    }
  }]' \
  --query 'Listeners[0].ListenerArn' \
  --output text)

# Listener 443 → forward to Target Group (Kong)
LISTENER_443=$(aws elbv2 create-listener \
  --load-balancer-arn $ALB_ARN \
  --protocol HTTPS \
  --port 443 \
  --ssl-policy ELBSecurityPolicy-TLS13-1-2-2021-06 \
  --certificates CertificateArn=$CERT_ARN \
  --default-actions '[{
    "Type": "forward",
    "TargetGroupArn": "'$TG_ARN'"
  }]' \
  --query 'Listeners[0].ListenerArn' \
  --output text)

# Add forwarding rules on 443 listener
# Rule 1: /api/* → Kong
aws elbv2 create-rule \
  --listener-arn $LISTENER_443 \
  --priority 10 \
  --conditions '[{"Field":"path-pattern","Values":["/api/*"]}]' \
  --actions '[{"Type":"forward","TargetGroupArn":"'$TG_ARN'"}]'

echo "  Listener 80:  HTTP  → 301 redirect to HTTPS"
echo "  Listener 443: HTTPS → forward to Kong Target Group"
echo "  SSL Policy: TLS 1.3 (most secure, rejects TLS 1.0/1.1)"
echo ""
echo "  WHY REDIRECT 80 → 443?"
echo "  Never serve HTTP in production"
echo "  301 = permanent redirect — browsers remember it"
echo "  All traffic encrypted in transit"

# ── STEP 9: Attach WAF to ALB ────────────────────────
echo ""
echo "=== [9/10] Attach WAF to ALB ==="
aws wafv2 associate-web-acl \
  --web-acl-arn $WAF_ACL \
  --resource-arn $ALB_ARN

echo "  WAF attached to ALB"
echo "  Every request now passes through WAF BEFORE reaching Kong"
echo ""
echo "  Traffic flow:"
echo "  Internet → WAF (block bad requests) → ALB → Kong → Services"

# ── STEP 10: Route 53 ────────────────────────────────
echo ""
echo "=== [10/10] Route 53 DNS Record ==="

# Create hosted zone
HOSTED_ZONE=$(aws route53 create-hosted-zone \
  --name $DOMAIN \
  --caller-reference $(date +%s) \
  --query 'HostedZone.Id' \
  --output text)

# Create A record alias → ALB
aws route53 change-resource-record-sets \
  --hosted-zone-id $HOSTED_ZONE \
  --change-batch "{
    \"Changes\": [{
      \"Action\": \"CREATE\",
      \"ResourceRecordSet\": {
        \"Name\": \"$DOMAIN\",
        \"Type\": \"A\",
        \"AliasTarget\": {
          \"HostedZoneId\": \"Z35SXDOTRQ7X7K\",
          \"DNSName\": \"$ALB_DNS\",
          \"EvaluateTargetHealth\": true
        }
      }
    },
    {
      \"Action\": \"CREATE\",
      \"ResourceRecordSet\": {
        \"Name\": \"api.$DOMAIN\",
        \"Type\": \"A\",
        \"AliasTarget\": {
          \"HostedZoneId\": \"Z35SXDOTRQ7X7K\",
          \"DNSName\": \"$ALB_DNS\",
          \"EvaluateTargetHealth\": true
        }
      }
    }]
  }"

echo "  Hosted Zone: $HOSTED_ZONE"
echo "  $DOMAIN         → $ALB_DNS (ALB)"
echo "  api.$DOMAIN     → $ALB_DNS (ALB)"
echo ""
echo "  WHY ALIAS RECORD not CNAME?"
echo "  Alias = AWS native, no extra DNS hop, free queries"
echo "  CNAME = extra lookup, costs money, slower"
echo "  Alias also supports root domain (CNAME cannot)"

# ── Save all IDs ─────────────────────────────────────
cat >> $(dirname $0)/.env << ENVEOF
ALB_ARN=$ALB_ARN
ALB_DNS=$ALB_DNS
TG_ARN=$TG_ARN
LISTENER_80=$LISTENER_80
LISTENER_443=$LISTENER_443
CERT_ARN=$CERT_ARN
WAF_ACL=$WAF_ACL
LOG_BUCKET=$LOG_BUCKET
HOSTED_ZONE=$HOSTED_ZONE
DOMAIN=$DOMAIN
ENVEOF

echo ""
echo "================================================"
echo "✅ ALB PRODUCTION SETUP COMPLETE!"
echo "================================================"
echo ""
echo "Traffic flow:"
echo "  User → Route53 ($DOMAIN)"
echo "       → ALB (internet-facing, public subnet)"
echo "       → WAF (OWASP + rate limit + SQLi check)"
echo "       → Listener :443 (TLS 1.3 terminated here)"
echo "       → Target Group (health checked)"
echo "       → Kong pod (private subnet)"
echo "       → Microservice pod (private subnet)"
echo "       → Database (data subnet)"
echo ""
echo "Listener :80 → 301 redirect to :443 (force HTTPS)"
echo ""
echo "Security:"
echo "  SSL: TLS 1.3 only"
echo "  WAF: OWASP + rate limit 2000req/5min + SQLi"
echo "  Logs: s3://$LOG_BUCKET/alb-logs/"
echo "  Deletion protection: ON"
echo ""
echo "Next: ./scripts/03-ecr.sh"
