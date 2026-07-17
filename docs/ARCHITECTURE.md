# Architecture

## System Overview

```mermaid
graph TB
    Client([Browser / Mobile / Postman])
    
    subgraph "Edge Layer"
        Route53[Route 53<br/>DNS]
        ALB[Application Load Balancer<br/>SSL termination · WAF · path routing]
    end

    subgraph "API Gateway"
        Kong[Kong Gateway :8000<br/>Rate limiting · JWT · routing · logging]
    end

    subgraph "Microservices"
        US[user-service :8081<br/>JWT auth]
        PS[product-service :8082<br/>Redis cache]
        OS[order-service :8083<br/>Kafka publisher]
        INV[inventory-service :8084<br/>Redis lock]
        PAY[payment-service :8085<br/>Kafka consumer]
        NOT[notification-service :8086<br/>Floci SES]
    end

    subgraph "Message Bus"
        K[Kafka :9092<br/>5 active topics · event-driven saga]
    end

    subgraph "Data Layer"
        R[Redis :6379<br/>sessions · cache · locks]
        PG1[(userdb)]
        PG2[(productdb)]
        PG3[(orderdb)]
        PG4[(inventorydb)]
        PG5[(paymentdb)]
    end

    subgraph "AWS / Floci :4566"
        SES[SES — email]
        ECR[ECR — Docker registry]
        S3[S3 — images · backups]
        CW[CloudWatch — logs]
    end

    Client --> Route53 --> ALB --> Kong
    Kong --> US & PS & OS & INV & PAY & NOT
    OS -->|order.created| K
    K -->|inventory.updated| INV & OS
    K -->|payment.processed| PAY & OS
    K -->|order.created and payment.processed and order.cancelled| NOT
    US --- PG1
    PS --- PG2 & R
    OS --- PG3
    INV --- PG4 & R
    PAY --- PG5
    NOT --> SES
    US --- R
```

---

## Kafka Event Flow (Order Saga)

```mermaid
sequenceDiagram
    participant Client
    participant Order
    participant Inventory
    participant Payment
    participant Notification

    Client->>Order: POST api orders
    Order->>Order: Save order PENDING
    Order-->>Kafka: order.created
    Kafka-->>Inventory: consume order.created
    Inventory->>Inventory: Reserve stock with Redis lock
    Inventory-->>Kafka: inventory.updated success true or false
    Kafka-->>Payment: consume inventory.updated success only
    Payment->>Payment: Process payment
    Payment-->>Kafka: payment.processed success true
    Kafka-->>Notification: consume order and payment events
    Notification->>Notification: Send email via Floci SES
    Kafka-->>Order: consume inventory.updated failure
    Order->>Order: Cancel order OUT OF STOCK
```

**Key design decision:** Payment only fires AFTER inventory confirms stock is reserved.
If inventory fails → order is automatically cancelled → no payment taken.

---

## Data Model (DB per service)

Each service owns its own PostgreSQL database. No cross-service DB queries.

```
userdb        → users (id, email, password_hash, role, active)
productdb     → products (id, name, price, category, stock=in inventory)
orderdb       → orders + order_items
inventorydb   → inventory (product_id, quantity, reserved, version)
paymentdb     → payments (order_id, amount, status, transaction_id)
```

---

## Redis Usage (3 purposes)

| Purpose | Service | Key pattern | TTL |
|---|---|---|---|
| JWT sessions | user-service | `session:<email>` | 24h |
| Product cache | product-service | `product:<id>` | 15 min |
| Distributed lock | inventory-service | `inventory:lock:<productId>` | 30s |

---

## Security Layers

```
Internet
  ↓
WAF (OWASP rules + rate limit 2000 req/5min + SQLi blocking)
  ↓
ALB (SSL/TLS termination — HTTPS only)
  ↓
Kong (rate limiting 1000/min + correlation IDs + JWT validation)
  ↓
Services (Spring Security + JWT + input validation)
  ↓
Databases (separate credentials per service, no cross-service access)
```

---

## Floci vs Real AWS

| Component | Floci (local) | Real AWS |
|---|---|---|
| VPC / Subnets / SGs | ✅ Full API simulation | AWS EC2 |
| ALB + listeners | ✅ Port 80 redirect works; port 443 TLS not implemented | AWS ELB |
| WAF | ✅ API works | AWS WAF |
| ACM (SSL cert) | ✅ API works | AWS ACM |
| Route 53 | ✅ API works | AWS Route 53 |
| ECR (push/pull) | ✅ Real Docker Registry v2 at localhost:5100 | AWS ECR |
| EKS (cluster) | ✅ Real k3s cluster provisions | AWS EKS |
| SES (email) | ✅ Real API, emails logged | AWS SES |
| S3 | ✅ Full API | AWS S3 |
| CloudWatch Logs | ✅ API works | AWS CloudWatch |
