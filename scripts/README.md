# AWS Infrastructure Scripts — Floci Learning Path

These scripts provision the full production AWS architecture (VPC → ALB → ECR →
EKS → app deployment) against [Floci](http://localhost:4566), a local AWS
simulator — no real AWS account or bill required. Run them in order.

```
01-vpc.sh        VPC, subnets (3-tier × 2 AZ), route tables, NAT gateway,
                 security groups, NACL, VPC Flow Logs
02-alb.sh        ACM cert, S3 access-log bucket, WAF, ALB, target group,
                 listeners (80→443 redirect, 443 forward), Route 53
03-ecr.sh        Per-service ECR repos, build + push all 6 service images
04-eks.sh        IAM roles, EKS cluster, 2 node groups
05-deploy.sh     Deploy the full k8s/ manifest set to that EKS cluster,
                 register Kong pods in the ALB target group
06-teardown.sh   Delete everything (K8s resources, ALB, EKS, security
                 groups) and stop the local Docker stack
```

Run with: `bash scripts/01-vpc.sh`, then `02-alb.sh`, etc. — each one
sources `scripts/.env` (created by the previous script) for resource IDs.

## What's genuinely verified vs. what to know about

Every step above was run for real against a live Floci instance, not just
read from the script. Here's what that testing found:

**Works as advertised:**
- VPC, IGW, 6 subnets, route tables, NAT gateway, security group creation
- ALB, target group, both listeners (port 80 genuinely redirects to 443 —
  verified with a real HTTP 301 from the data plane)
- ACM cert issuance, WAF Web ACL creation + attachment, S3 buckets +
  policies, Route 53 hosted zone + records
- ECR repos, real image push/pull (Floci backs this with an actual Docker
  Registry v2 container)
- IAM roles + policy attachment
- **EKS actually provisions a real backing cluster** (k3s) that runs real
  pods — this surprised us; it's not just API mocking.

**Real Floci limitations found by testing, not assumed:**
1. **VPC Flow Logs** — `aws ec2 create-flow-logs` returns
   `UnsupportedOperation`. Not implemented.
2. **Security-group-to-security-group references** — rules created with
   `--source-group <id>` are accepted (no error) but the reference is
   silently dropped; `describe-security-groups` shows an empty
   `UserIdGroupPairs`. The CIDR-based ALB rules work fine; only SG
   chaining doesn't persist.
3. **NACL entries** — `create-network-acl-entry` calls succeed with no
   error, but the entries never actually attach to the NACL — only the
   VPC's default allow-all/deny-all rules are present afterward.
4. **ALB HTTPS (port 443) doesn't actually terminate TLS** — port 80's
   redirect is real, but port 443 returns `503 Service unavailable` for
   both real TLS and plain-HTTP requests. The simulated ACM cert isn't
   wired into the data plane's TLS termination.
5. **ALB target groups (`target-type=ip`) can't reach EKS pods** —
   Floci's EKS uses k3s's default flannel/overlay networking
   (`10.42.0.0/16`), which isn't routable from outside the cluster, unlike
   real EKS's VPC CNI (which gives pods real VPC IPs). Target health
   always reports `Target.FailedHealthChecks` as a result. This is a
   structural gap, not a config error — there's nothing in these scripts
   to fix it.
6. **Pulling pushed ECR images into the EKS/k3s cluster requires manual
   surgery** — `localhost:5100` (the registry's host-published port)
   isn't reachable from inside the k3s node's network namespace. The
   actual fix: the registry and the k3s node share a Docker network, so
   `floci-ecr-registry:5000` works directly — but you have to add that to
   `/etc/rancher/k3s/registries.yaml` inside the node container and
   restart it. There's no AWS-API-level way to configure this.

**Windows-specific gotcha:** running these in Git Bash mangles `/`-prefixed
path-style CLI arguments (e.g. `--health-check-path /status` silently
became `C:/Program Files/Git/status`). Doesn't happen on Linux/CI — only
bites you running these by hand on Windows.

## Why CI doesn't use this EKS path

`.github/workflows/ci-cd.yml`'s automated dev deploy uses a Kind cluster
instead of `04-eks.sh` + `05-deploy.sh`, specifically because of findings
5 and 6 above — neither is something a CI script can reliably automate.
These scripts remain the right way to *learn* the full AWS architecture
hands-on; they're just not wired into the automated pipeline.
