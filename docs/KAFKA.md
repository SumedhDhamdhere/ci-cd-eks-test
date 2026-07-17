# Kafka — HLD & LLD (Newbie-Friendly)

## A. The one-line idea

**Services never call each other directly. They drop messages on Kafka topics; whoever cares, listens.**

## B. HLD — who publishes, who listens

```mermaid
flowchart LR
    PR[product-service] -- "product.created" --> BUS[(Kafka)]
    O[order-service] -- "order.created<br/>order.cancelled" --> BUS
    I[inventory-service] -- "inventory.updated" --> BUS
    P[payment-service] -- "payment.processed" --> BUS

    BUS -- "order.created" --> I
    BUS -- "inventory.updated" --> P
    BUS -- "inventory.updated<br/>payment.processed" --> O
    BUS -- "order.cancelled" --> I
    BUS -- "product.created" --> I
    BUS -- "order.created, payment.processed,<br/>order.cancelled" --> N[notification-service]
```

| Principle | Meaning in plain words |
|---|---|
| Choreography | No boss service; each one reacts to events on its own |
| At-least-once | A message may arrive twice, but never gets lost |
| Key = orderId | All events of one order stay in order (same partition) |

## C. LLD — topics, declared vs actually used

**7 topics are created, only 5 are used by code:**

| Topic | Partitions | Status | Publisher | Consumers |
|---|---|---|---|---|
| `order.created` | 12 | ✅ Active | order | inventory, notification |
| `payment.processed` | 12 | ✅ Active | payment | order, notification |
| `inventory.updated` | 6 | ✅ Active | inventory | order, payment |
| `order.cancelled` | 3 | ✅ Active | order | inventory, notification |
| `product.created` | 4 | ✅ Active | product | inventory |
| `order.confirmed` | 6 | ⚠️ Dead — nobody uses it | — | — |
| `notification.send` | 4 | ⚠️ Dead — nobody uses it | — | — |

## D. Consumer groups & concurrency

| Service | Topic | groupId | concurrency |
|---|---|---|---|
| inventory | order.created | inventory-service-group | 6 |
| inventory | order.cancelled | inventory-service-group | 3 |
| inventory | product.created | inventory-service-group | 1 (default) |
| order | inventory.updated | order-service-group | 6 |
| order | payment.processed | order-service-group | 6 |
| payment | inventory.updated | payment-service-group | 6 |
| notification | order.created | notification-service-group | 4 |
| notification | payment.processed | notification-service-group | 4 |
| notification | order.cancelled | notification-service-group | 3 |

**concurrency** = threads reading partitions in parallel. Never set it above the topic's partition count (extra threads sit idle).

**Different groupIds on the same topic = fan-out:** order-service-group and payment-service-group EACH get a full copy of every `inventory.updated` message.

## E. The Order Saga — full flow

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant O as OrderSvc
    participant K as Kafka
    participant I as InventorySvc
    participant P as PaymentSvc
    participant N as NotifySvc

    C->>O: POST api orders
    O->>O: save order PENDING
    O->>K: publish order.created
    K->>I: consume order.created
    I->>I: Redis lock, check stock
    alt stock ok
        I->>K: inventory.updated success true
    else no stock
        I->>K: inventory.updated success false
    end
    K->>P: inventory.updated (acts only on success true)
    P->>K: payment.processed success true or false
    K->>O: payment.processed → PAID, or cancel
    K->>O: inventory.updated false → cancel
    O->>K: order.cancelled (on any cancel)
    K->>I: order.cancelled → release stock
    K->>N: all events → send email via SES
```

**Order states:** `PENDING → PAID` (happy) or `PENDING → CANCELLED` (no stock / payment failed).

## F. Producer reliability (KafkaConfig.java)

```java
config.put(ProducerConfig.ACKS_CONFIG, "all");              // wait for all replicas
config.put(ProducerConfig.RETRIES_CONFIG, 3);               // retry blips
config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // retry ≠ duplicate
```

Without idempotence, a retried send could double-charge an order.

## G. Known gaps

1. 2 dead topics (`order.confirmed`, `notification.send`)
2. No dead-letter queue — a bad message is logged and lost
3. No schema registry — payloads are hand-built JSON, typos surface only at runtime
