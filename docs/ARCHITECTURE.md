# Architecture — Deep Dive

> 6 Spring Boot microservices · Kafka saga · Kong gateway · Redis · Postgres-per-service · Floci (AWS sim)
> How everything ships end-to-end → [WORKFLOW.md](WORKFLOW.md)

---

## 1. System Overview

Every request enters through Kong (rate-limited per IP). Services never call each other over REST — all cross-service communication is Kafka events.

```mermaid
flowchart TB
    C[Client] --> KONG[Kong Gateway :8000<br/>rate-limit · routing · WAF]
    KONG --> U[user-service :8081]
    KONG --> P[product-service :8082]
    KONG --> O[order-service :8083]
    KONG --> I[inventory-service :8084]
    KONG --> PAY[payment-service :8085]

    U --- UDB[(userdb)]
    P --- PDB[(productdb)]
    O --- ODB[(orderdb)]
    I --- IDB[(inventorydb)]
    PAY --- PAYDB[(paymentdb)]

    U -.sessions.- R[(Redis)]
    P -.cache 15m TTL.- R
    I -.distributed lock.- R

    O <-->|events| K[(Kafka)]
    I <-->|events| K
    PAY <-->|events| K
    P -->|events| K
    K --> N[notification-service :8086]
    N --> SES[Floci SES email]
```

- **DB-per-service** — no shared tables; each service owns its schema (Flyway migrations run on startup).
- **Redis, 3 distinct jobs** — JWT session store (real logout), product cache, inventory distributed lock (oversell prevention).

---

## 2. Order Saga (choreography — no orchestrator)

Each service reacts to events and emits the next one. Failure at any step emits a compensating event.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant O as order-service
    participant K as Kafka
    participant I as inventory-service
    participant P as payment-service
    participant N as notification-service

    C->>O: POST /orders
    O->>O: save order (PENDING)
    O->>K: order.created
    K->>I: order.created
    I->>I: Redis lock → reserve stock
    I->>K: inventory.updated (ok/fail)
    K->>O: inventory.updated
    K->>P: inventory.updated
    alt stock reserved
        P->>K: payment.processed (ok/fail)
        K->>O: payment.processed → CONFIRMED / CANCELLED
    else out of stock
        O->>O: mark CANCELLED
        O->>K: order.cancelled (compensation)
        K->>I: order.cancelled → release stock
    end
    K->>N: order.created / payment.processed / order.cancelled
    N->>C: email via SES
```

**5 topics:** `order.created` · `inventory.updated` · `payment.processed` · `order.cancelled` · `product.created`
One consumer group per service; 3–6 concurrent listeners per topic for throughput.

---

## 3. Auth Flow (JWT + Redis sessions)

```mermaid
sequenceDiagram
    participant C as Client
    participant U as user-service
    participant R as Redis
    participant S as any service

    C->>U: POST /login
    U->>R: store session (jti)
    U-->>C: JWT
    C->>S: request + JWT
    S->>R: session still valid?
    alt logged out
        S-->>C: 401
    else valid
        S-->>C: 200
    end
```

Logout deletes the Redis session → token dead instantly, even before its expiry.

---

## 4. Cloud Topology (Floci EKS)

```mermaid
flowchart LR
    NET[Internet] --> ALB[ALB :443<br/>SSL + WAF<br/>public subnet]
    subgraph EKS[EKS cluster — private subnets]
        ALB --> KONG[Kong]
        KONG --> SVC[6 service Deployments + HPA]
    end
    subgraph DATA[data subnet]
        PG[(Postgres ×5)]
        RD[(Redis)]
        KF[(Kafka)]
    end
    SVC --> PG & RD & KF
    ECR[(ECR — image per service,<br/>tag = git SHA)] --> SVC
```

- 3-tier network: **public** (ALB only) → **private** (EKS nodes, Kong, services) → **data** (DBs, Kafka, Redis). Security groups enforce each hop.
- 2 EKS node groups: `general` (m5.large — most services, Kong) and `high-mem` (r5.large — Kafka consumers).
- Manifests in [k8s/](../k8s/): `namespace/ postgres/ redis/ kafka/ kong/ services/ monitoring/`. Same manifests work on real AWS — only env vars change.

---

## 5. CI/CD (summary — full detail in [WORKFLOW.md](WORKFLOW.md))

```mermaid
flowchart LR
    PR[push / PR] --> T[test ×6 parallel matrix]
    T --> B[mvn -T 1C build + docker build ×6]
    B --> S[Trivy scan]
    S --> K[k3d cluster → deploy all manifests]
    K --> SM[saga smoke test → order PAID]
    SM --> G{{main only: human approval}} --> PROD[deploy-prod]
```

---

## 6. Per-Service Detail

| Service | Port | Owns | Publishes | Consumes | Special |
|---|---|---|---|---|---|
| user | 8081 | users, auth | — | — | JWT + Redis sessions |
| product | 8082 | catalog | `product.created` | — | Redis cache 15-min TTL |
| order | 8083 | orders, saga state | `order.created`, `order.cancelled` | `inventory.updated`, `payment.processed` | Saga initiator |
| inventory | 8084 | stock | `inventory.updated` | `order.created`, `order.cancelled`, `product.created` | Redis lock — no oversell |
| payment | 8085 | payments | `payment.processed` | `inventory.updated` | Pays only after stock reserved |
| notification | 8086 | — (stateless) | — | `order.created`, `payment.processed`, `order.cancelled` | SES email |
