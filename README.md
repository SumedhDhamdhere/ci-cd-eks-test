# E-Commerce Platform — Production Microservices on Floci

> Spring Boot 3.2 · Java 17 · Kafka · Redis · PostgreSQL · Kong · Floci (AWS Simulator)

A production-grade e-commerce backend — 6 microservices, Kafka event-driven,
deployable locally via Docker Compose or on simulated AWS (EKS + ECR + ALB)
using Floci. Same Docker images, same K8s manifests deploy to real AWS by
changing only environment variables.

---

## Quick Navigation

| I want to... | Go to |
|---|---|
| Run the app now | [Quick Start](#quick-start) |
| Architecture deep dive (all diagrams) | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Full workflow: laptop → GitHub → CI → K8s → cloud | [docs/WORKFLOW.md](docs/WORKFLOW.md) |

---

## Services

| Service | Port | DB | Key Feature |
|---|---|---|---|
| **user-service** | 8081 | userdb | JWT auth, Redis sessions |
| **product-service** | 8082 | productdb | Redis cache (15-min TTL) |
| **order-service** | 8083 | orderdb | Kafka publisher, Saga pattern |
| **inventory-service** | 8084 | inventorydb | Redis distributed lock (oversell prevention) |
| **payment-service** | 8085 | paymentdb | Kafka consumer + publisher |
| **notification-service** | 8086 | — | Floci SES email |
| **Kong Gateway** | 8000 | — | Rate limiting, routing, WAF |
| **Floci** | 4566 | — | AWS simulator (S3, SES, ECR, EKS, ALB…) |

---

## Quick Start

### Option 1 — Docker Compose (60 seconds)
```bash
docker compose up -d
# All services ready at localhost:8000
```

### Option 2 — Full AWS Simulation via Floci (5 minutes)
```bash
bash scripts/01-vpc.sh      # VPC, subnets, security groups, NAT gateway
bash scripts/02-alb.sh      # ALB, WAF, ACM cert, Route53
bash scripts/03-ecr.sh      # ECR repos, build + push all 6 images
bash scripts/04-eks.sh      # EKS cluster (real k3s), IAM roles, node groups
bash scripts/05-deploy.sh   # Deploy K8s manifests, register Kong in ALB
```

---

## Test Endpoints (all via Kong :8000)

```bash
# Register
curl -X POST http://localhost:8000/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Rahul","email":"rahul@test.com","password":"password123"}'

# Create product
curl -X POST http://localhost:8000/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"iPhone 15","price":79999,"category":"Electronics"}'

# Place order → triggers full Kafka chain
# order.created → inventory reserves → payment processes → notification sends
curl -X POST http://localhost:8000/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"shippingAddress":"Mumbai","items":[{"productId":1,"productName":"iPhone 15","quantity":1,"price":79999}]}'

# Check order
curl http://localhost:8000/api/orders/user/1
```

---

## Kafka Topics

| Topic | Partitions | Publisher | Consumers |
|---|---|---|---|
| order.created | 12 | Order | Payment, Inventory, Notification |
| payment.processed | 12 | Payment | Notification |
| inventory.updated | 6 | Inventory | Order (cancel if no stock) |
| order.cancelled | 3 | Order | Inventory, Notification |
| product.created | 4 | Product | Inventory (auto-create stock row) |
