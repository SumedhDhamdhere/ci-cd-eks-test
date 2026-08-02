# Diagrams — Cloud, Kafka, Redis & Workflow Matrix

> Companion to [ARCHITECTURE.md](ARCHITECTURE.md). Every scenario below is traced end-to-end.

---

## 1. Cloud Topology — every provisioned resource

> Values below are the **actual** ones created by [`scripts/01-vpc.sh`](../scripts/01-vpc.sh) →
> [`02-alb.sh`](../scripts/02-alb.sh) → [`03-ecr.sh`](../scripts/03-ecr.sh) →
> [`04-eks.sh`](../scripts/04-eks.sh). Region **ap-south-1**, VPC **10.0.0.0/16**, 2 AZs.

### 1.1 Full resource inventory

| # | Resource | Identifier / value | Created by |
|---|---|---|---|
| 1 | VPC | `10.0.0.0/16` (DNS hostnames + support on) | 01 |
| 2 | Internet Gateway | attached to VPC | 01 |
| 3 | Public subnet 1 | `10.0.1.0/24` · ap-south-1a | 01 |
| 4 | Public subnet 2 | `10.0.2.0/24` · ap-south-1b | 01 |
| 5 | Private subnet 1 | `10.0.3.0/24` · ap-south-1a | 01 |
| 6 | Private subnet 2 | `10.0.4.0/24` · ap-south-1b | 01 |
| 7 | Data subnet 1 | `10.0.5.0/24` · ap-south-1a | 01 |
| 8 | Data subnet 2 | `10.0.6.0/24` · ap-south-1b | 01 |
| 9 | Elastic IP | for the NAT Gateway | 01 |
| 10 | NAT Gateway | in **public subnet 1** | 01 |
| 11 | Route table (public) | `0.0.0.0/0` → IGW | 01 |
| 12 | Route table (private) | `0.0.0.0/0` → NAT — associated to **private *and* data** subnets | 01 |
| 13 | Security group | `alb-sg` | 01 |
| 14 | Security group | `eks-sg` | 01 |
| 15 | Security group | `db-sg` | 01 |
| 16 | Network ACL | subnet-level stateless filter | 01 |
| 17 | S3 bucket | `ecommerce-vpc-flowlogs-<ts>` (versioned) | 01 |
| 18 | VPC Flow Logs | VPC → that S3 bucket | 01 |
| 19 | ACM certificate | for `ecommerce.local` | 02 |
| 20 | S3 bucket | `ecommerce-alb-logs-<ts>` + bucket policy | 02 |
| 21 | WAFv2 Web ACL | `ecommerce-waf`, associated to ALB | 02 |
| 22 | ALB | `ecommerce-alb` (public subnets, access logs on) | 02 |
| 23 | Target group | `ecommerce-kong-tg` | 02 |
| 24 | Listener :443 | HTTPS, ACM cert → target group | 02 |
| 25 | Listener :80 | redirect → 443 | 02 |
| 26 | Listener rule(s) | path routing on the 443 listener | 02 |
| 27 | Route53 hosted zone | `ecommerce.local` | 02 |
| 28 | Route53 record | alias → ALB DNS | 02 |
| 29 | ECR repositories ×6 | `ecommerce/<service>` · scanOnPush · AES256 · keep-10 lifecycle | 03 |
| 30 | IAM role | `eks-cluster-role` → `AmazonEKSClusterPolicy` | 04 |
| 31 | IAM role | `eks-node-role` → WorkerNode + ECR-ReadOnly + CNI | 04 |
| 32 | EKS cluster | `ecommerce-cluster`, k8s **1.28** | 04 |
| 33 | Nodegroup | `general-nodes` · m5.large · min2/max8/**desired3** | 04 |
| 34 | Nodegroup | `high-mem-nodes` · r5.large · min1/max4/**desired2** | 04 |
| 35 | Auto Scaling Groups | one per nodegroup (implicit) | 04 |

### 1.2 Network topology — all 6 subnets, both AZs

```mermaid
flowchart TB
    NET([Internet])
    IGW[Internet Gateway]
    R53[Route53 zone<br/>ecommerce.local<br/>alias → ALB]
    ACM[ACM cert<br/>*.ecommerce.local]
    WAF[WAFv2<br/>ecommerce-waf]

    subgraph VPC["VPC 10.0.0.0/16 — ap-south-1"]
        subgraph PUBZ["PUBLIC tier — route: 0.0.0.0/0 → IGW"]
            PUB1["public-1<br/>10.0.1.0/24 · AZ-a"]
            PUB2["public-2<br/>10.0.2.0/24 · AZ-b"]
            ALB["ALB ecommerce-alb<br/>:443 HTTPS · :80 redirect<br/>sg = alb-sg"]
            NAT["NAT Gateway<br/>+ Elastic IP"]
        end

        subgraph PRIVZ["PRIVATE tier — route: 0.0.0.0/0 → NAT (egress only)"]
            PRIV1["private-1<br/>10.0.3.0/24 · AZ-a"]
            PRIV2["private-2<br/>10.0.4.0/24 · AZ-b"]
            EKS["EKS ecommerce-cluster v1.28<br/>general-nodes · high-mem-nodes<br/>sg = eks-sg"]
        end

        subgraph DATAZ["DATA tier — shares the private route table (NAT egress); isolation comes from db-sg"]
            DATA1["data-1<br/>10.0.5.0/24 · AZ-a"]
            DATA2["data-2<br/>10.0.6.0/24 · AZ-b"]
            STORES[("Postgres x5 :5432<br/>Redis :6379<br/>Kafka :9092<br/>sg = db-sg")]
        end
    end

    S3F[(S3 ecommerce-vpc-flowlogs)]
    S3A[(S3 ecommerce-alb-logs)]
    ECR[(ECR ecommerce<br/>tag = git SHA)]

    NET --> R53 --> ALB
    NET --> IGW --> PUB1 & PUB2
    ACM -.TLS.-> ALB
    WAF -.inspects.-> ALB
    ALB -->|"tg: ecommerce-kong-tg"| EKS
    ALB -.access logs.-> S3A
    EKS -->|5432 · 6379 · 9092| STORES
    EKS -.outbound pulls.-> NAT --> IGW
    ECR -.image pull.-> EKS
    VPC -.flow logs.-> S3F
    PUB1 -.- ALB
    PUB2 -.- ALB
    PRIV1 -.- EKS
    PRIV2 -.- EKS
    DATA1 -.- STORES
    DATA2 -.- STORES
```

### 1.3 Security groups — actual allowed hops

| From | To | Port | Source type |
|---|---|---|---|
| Internet | `alb-sg` | 443 | `0.0.0.0/0` |
| Internet | `alb-sg` | 80 | `0.0.0.0/0` (redirects to 443) |
| `alb-sg` | `eks-sg` | 8000 | **SG reference**, not CIDR |
| `eks-sg` | `db-sg` | 5432 | SG reference (Postgres) |
| `eks-sg` | `db-sg` | 6379 | SG reference (Redis) |
| `eks-sg` | `db-sg` | 9092 | SG reference (Kafka) |
| `eks-sg` | `eks-sg` | 1024-65535 | node-to-node / kubelet |
| data tier | Internet | outbound | via NAT (shares the private route table) — **inbound still impossible** |

Two independent layers: **NACLs** (stateless, subnet-wide) and **security groups**
(stateful, per-ENI). SG rules reference *other SGs* rather than IP ranges, so they
stay correct when Pod/node IPs change.

### 1.4 Why a NAT Gateway is needed

```mermaid
flowchart LR
    POD["Pod in private subnet<br/>no public IP"] -->|"outbound only"| NAT[NAT Gateway<br/>public subnet + EIP]
    NAT --> IGW[IGW] --> EXT([ECR · SES · package repos])
    EXT -.->|"inbound BLOCKED —<br/>NAT is one-way"| NAT
```

Nodes must reach out (pull images, call SES) but must not be reachable *from*
the internet. NAT allows exactly that asymmetry — outbound yes, inbound no.

### 1.5 Pod placement across the two nodegroups

```mermaid
flowchart LR
    subgraph general["general-nodes · m5.large · 2→8 (desired 3)"]
        direction TB
        GA[kong]
        GB[user :8081]
        GC[product :8082]
        GD[order :8083]
    end
    subgraph highmem["high-mem-nodes · r5.large · 1→4 (desired 2)"]
        direction TB
        HA[inventory :8084<br/>heavy Kafka consumer]
        HB[payment :8085<br/>heavy Kafka consumer]
        HC[notification :8086]
    end
    HPA[HPA → pod count] -.-> general & highmem
    CA[Cluster Autoscaler → node count<br/>via each nodegroup's ASG] -.on Pending Pods.-> general & highmem
```

Both nodegroups span `private-1` + `private-2`, so a single-AZ failure never takes
out the whole cluster. Kafka consumers sit on `high-mem` because each holds in-flight
record batches per partition; request/response services stay on `general`.

**Two distinct scaling loops:** HPA changes **Pod** count (reacting to CPU/memory);
Cluster Autoscaler changes **node** count (reacting to Pods stuck `Pending`).

---

## 2. Kafka Architecture

### 2.1 Topics, producers, consumers

```mermaid
flowchart LR
    O[order-service] -->|produce| T1[[order.created]]
    O -->|produce| T4[[order.cancelled]]
    I[inventory-service] -->|produce| T2[[inventory.updated]]
    PAY[payment-service] -->|produce| T3[[payment.processed]]
    P[product-service] -->|produce| T5[[product.created]]

    T1 --> I
    T1 --> N[notification-service]
    T2 --> O
    T2 --> PAY
    T3 --> O
    T3 --> N
    T4 --> I
    T4 --> N
    T5 --> I
```

### 2.2 Who listens to what — the full matrix

| Topic | Producer | Consumers | Consumer action |
|---|---|---|---|
| `order.created` | order | inventory, notification | reserve stock / send "order placed" mail |
| `inventory.updated` | inventory | order, payment | update saga state / charge if reserved |
| `payment.processed` | payment | order, notification | CONFIRMED-or-CANCELLED / send receipt |
| `order.cancelled` | order | inventory, notification | **release** stock / send cancellation mail |
| `product.created` | product | inventory | seed initial stock row |

Every service has **its own consumer group** — so the same event reaches
inventory *and* notification independently; neither steals it from the other.

### 2.3 Partitions & ordering

```mermaid
flowchart TB
    subgraph topic["topic: order.created — 3 partitions"]
        P0["partition 0<br/>orders where hash(orderId) %3 == 0"]
        P1["partition 1<br/>hash %3 == 1"]
        P2["partition 2<br/>hash %3 == 2"]
    end
    subgraph cg["consumer group: inventory-service"]
        C1[inventory pod 1] --- P0
        C2[inventory pod 2] --- P1
        C3[inventory pod 3] --- P2
    end
```

Key = `orderId` → **all events for one order always land on the same partition**,
so that order's events are processed in emit-order. Different orders run in
parallel across partitions. Scaling past 3 pods adds idle consumers — partitions,
not pods, are the parallelism ceiling.

### 2.4 Why events instead of REST

```mermaid
flowchart LR
    subgraph bad["REST chain — coupled"]
        A1[order] -->|HTTP| B1[inventory] -->|HTTP| C1[payment]
        B1 -.if inventory down<br/>whole chain fails.-> X1((✗))
    end
    subgraph good["Kafka — decoupled"]
        A2[order] --> K[(Kafka)]
        K --> B2[inventory]
        B2 -.if inventory down,<br/>event waits in topic.-> Y2((✓ resumes<br/>on restart))
    end
```

---

## 3. Redis — three unrelated jobs, one instance

```mermaid
flowchart TB
    subgraph redis["Redis"]
        K1["session:{email}<br/>→ jti"]
        K2["product:{id}<br/>→ cached product JSON"]
        K3["inventory:lock:{productId}<br/>→ orderId (SETNX)"]
    end

    U[user-service] -->|"SET on login<br/>DEL on logout"| K1
    ANY[any service<br/>validating a JWT] -->|"HASKEY?"| K1

    P[product-service] -->|"GET → miss → DB → SET (15m TTL)"| K2
    P -->|"DEL on update/delete"| K2

    I[inventory-service] -->|"SETNX acquire<br/>DEL release"| K3
```

### 3.1 Session store — real logout

```mermaid
sequenceDiagram
    participant C as Client
    participant U as user-service
    participant R as Redis
    participant S as product-service

    C->>U: POST /login
    U->>R: SET session:jane@example.com = jti
    U-->>C: JWT (contains jti)

    C->>S: GET /products + JWT
    S->>R: HASKEY session:jane@example.com?
    R-->>S: true
    S-->>C: 200 products

    C->>U: POST /logout
    U->>R: DEL session:jane@example.com

    C->>S: GET /products + same JWT
    S->>R: HASKEY session:jane@example.com?
    R-->>S: false
    S-->>C: 401 — token dead before expiry
```

Plain JWT can't be revoked before expiry. The Redis check is what makes logout real.

### 3.2 Product cache — 15-min TTL

```mermaid
flowchart TB
    REQ[GET /products/5] --> Q{"redis GET product:5"}
    Q -->|HIT| FAST[return cached<br/>~1ms, no DB]
    Q -->|MISS| DB[(productdb SELECT)]
    DB --> SET["redis SET product:5<br/>TTL 15m"] --> RET[return]
    UPD[PUT/DELETE /products/5] --> INV["redis DEL product:5"]
    INV -.next read repopulates.-> Q
```

### 3.3 Distributed lock — oversell prevention

```mermaid
sequenceDiagram
    participant A as inventory pod A
    participant B as inventory pod B
    participant R as Redis
    participant D as inventorydb

    Note over A,B: two orders for product 1 arrive at the same instant
    A->>R: SETNX inventory:lock:1 = orderA
    R-->>A: true (acquired)
    B->>R: SETNX inventory:lock:1 = orderB
    R-->>B: false (already held) — B waits/retries

    A->>D: SELECT stock → 1 left
    A->>D: UPDATE stock = 0
    A->>R: DEL inventory:lock:1

    B->>R: SETNX inventory:lock:1 = orderB
    R-->>B: true
    B->>D: SELECT stock → 0
    B-->>B: reject — out of stock
```

Without the lock both pods read "1 left" simultaneously and both sell it — **oversell**.
Lock key is per-**product**, so different products never block each other.

---

## 4. Workflow Matrix — every path traced

### 4.1 Outcome table

| # | Stock | Payment | Final order state | Compensation | Emails sent |
|---|---|---|---|---|---|
| 1 | ✅ available | ✅ success | **CONFIRMED** | none | placed + receipt |
| 2 | ✅ available | ❌ declined | **CANCELLED** | stock released | placed + failure |
| 3 | ❌ out of stock | — never runs | **CANCELLED** | nothing to release | placed + cancellation |
| 4 | ✅ available | ⏳ timeout | **PENDING** → retry | lock TTL expires | placed only |
| 5 | ✅ available | ✅ success | **CONFIRMED** | none | placed + receipt (dedup on retry) |

### 4.2 Case 1 — happy path

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant KG as Kong
    participant O as order
    participant K as Kafka
    participant I as inventory
    participant R as Redis
    participant P as payment
    participant N as notification

    C->>KG: POST /orders + JWT
    KG->>KG: rate-limit ok · route
    KG->>O: forward
    O->>O: save order PENDING
    O->>K: order.created
    par
        K->>I: order.created
    and
        K->>N: order.created → "order placed" mail
    end
    I->>R: SETNX inventory:lock:1
    I->>I: stock 10 → 9
    I->>R: DEL lock
    I->>K: inventory.updated (reserved=true)
    par
        K->>O: state → STOCK_RESERVED
    and
        K->>P: inventory.updated
    end
    P->>P: charge ok
    P->>K: payment.processed (success=true)
    par
        K->>O: state → CONFIRMED
    and
        K->>N: receipt mail
    end
```

### 4.3 Case 2 — payment declined (compensation fires)

```mermaid
sequenceDiagram
    autonumber
    participant O as order
    participant K as Kafka
    participant I as inventory
    participant P as payment
    participant N as notification

    O->>K: order.created
    K->>I: order.created
    I->>I: stock 10 → 9 (reserved)
    I->>K: inventory.updated (reserved=true)
    K->>P: inventory.updated
    P->>P: card declined ✗
    P->>K: payment.processed (success=false)
    K->>O: mark CANCELLED
    O->>K: order.cancelled  ← COMPENSATING EVENT
    par
        K->>I: order.cancelled → stock 9 → 10 (released)
    and
        K->>N: "payment failed" mail
    end
```

The stock that was already reserved **must** be given back — that's the whole
point of a compensating event. No orchestrator tells inventory to do this;
it reacts to `order.cancelled` on its own.

### 4.4 Case 3 — out of stock (payment never runs)

```mermaid
sequenceDiagram
    autonumber
    participant O as order
    participant K as Kafka
    participant I as inventory
    participant P as payment
    participant N as notification

    O->>K: order.created
    K->>I: order.created
    I->>I: stock = 0 ✗
    I->>K: inventory.updated (reserved=false)
    par
        K->>O: mark CANCELLED
    and
        K->>P: inventory.updated (reserved=false)
        Note over P: sees reserved=false<br/>→ does nothing, no charge
    end
    O->>K: order.cancelled
    K->>I: nothing to release (never reserved)
    K->>N: "out of stock" mail
```

Payment **subscribes** to `inventory.updated` but self-filters on `reserved=false` —
the customer is never charged for something that was never reserved.

### 4.5 Case 4 — concurrent orders, same product, 1 left

```mermaid
sequenceDiagram
    participant O1 as order A
    participant O2 as order B
    participant I as inventory (2 pods)
    participant R as Redis

    par same millisecond
        O1->>I: order.created (product 1)
    and
        O2->>I: order.created (product 1)
    end
    I->>R: SETNX lock:1 (pod A) → true
    I->>R: SETNX lock:1 (pod B) → false, retry
    Note over I: pod A: stock 1 → 0, releases lock
    I->>R: SETNX lock:1 (pod B) → true
    Note over I: pod B: reads stock 0 → reject
    Note over O1,O2: A → CONFIRMED · B → CANCELLED (out of stock)
```

### 4.6 Auth permutations at the gateway

| Request | Kong | Service | Redis session | Result |
|---|---|---|---|---|
| no JWT | pass | reject | not checked | **401** |
| expired JWT | pass | reject | not checked | **401** |
| valid JWT, logged in | pass | accept | `HASKEY` → true | **200** |
| valid JWT, logged out | pass | reject | `HASKEY` → false | **401** |
| valid JWT, >N req/min | **rate-limited** | never reached | — | **429** |

---

## 5. Failure & Recovery Matrix

| What dies | Immediate effect | Recovery |
|---|---|---|
| one service pod | HPA/Deployment reschedules; Kafka rebalances its partitions to surviving pods | automatic |
| **Redis** | logins fail, cache misses go to DB, inventory lock unavailable → inventory pauses rather than risk oversell | restart; sessions lost (users re-login) |
| **Kafka** | saga freezes — orders stay PENDING, nothing is lost | on restart consumers resume from last committed offset |
| one Postgres | only that service degrades; others unaffected (DB-per-service) | restore that DB alone |
| **Kong** | no external traffic enters; internal Kafka flow keeps draining | ALB routes to healthy Kong pod |
| **ALB** | total external outage | multi-AZ ALB fails over |

```mermaid
flowchart LR
    D[inventory pod dies<br/>mid-processing] --> E{offset committed?}
    E -->|no| F[event redelivered<br/>to another pod]
    E -->|yes| G[already applied<br/>skip]
    F --> H[idempotency check<br/>on orderId]
    H --> I[safe — no double reserve]
```

---

## 6. Request Path Comparison

```mermaid
flowchart TB
    subgraph sync["SYNCHRONOUS — client waits"]
        A1[Client] --> A2[Kong] --> A3[order-service]
        A3 --> A4[(orderdb save PENDING)]
        A4 --> A5[202 Accepted — returns immediately]
    end
    subgraph async["ASYNCHRONOUS — after response is sent"]
        B1[order.created] --> B2[inventory] --> B3[payment] --> B4[CONFIRMED]
        B4 --> B5[email]
    end
    A5 -.client polls GET /orders/id<br/>or waits for email.-> B4
```

The client never waits for the whole saga — it gets an order id instantly and the
final state arrives via polling or email. That is the practical payoff of choreography.
