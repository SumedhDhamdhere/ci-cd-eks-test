# E-Commerce Platform — Microservices on Floci

## Architecture
```
Kong (8000) → User(8081) Product(8082) Order(8083) Inventory(8084) Payment(8085) Notification(8086)
                                          ↓
                              Kafka (9092) — 6 topics
                              Redis (6379) — sessions, cache, locks
                              PostgreSQL   — 5 separate DBs
                              Floci (4566) — AWS simulator (S3, SES)
```

## Quick Start
```bash
# 1. Start everything
./setup-floci.sh

# 2. Run each service (separate terminals)
cd user-service && mvn spring-boot:run
cd product-service && mvn spring-boot:run
cd order-service && mvn spring-boot:run
cd inventory-service && mvn spring-boot:run
cd payment-service && mvn spring-boot:run
cd notification-service && mvn spring-boot:run
```

## Test Flow
```bash
# Register user
curl -X POST http://localhost:8000/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"name":"John","email":"john@test.com","password":"password123"}'

# Login
curl -X POST http://localhost:8000/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"email":"john@test.com","password":"password123"}'

# Create product
curl -X POST http://localhost:8000/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"iPhone 15","price":79999,"category":"Electronics"}'

# Place order → triggers Kafka → Payment + Inventory + Notification
curl -X POST http://localhost:8000/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "shippingAddress": "123 MG Road, Pune",
    "items": [{"productId":1,"productName":"iPhone 15","quantity":1,"price":79999}]
  }'
```

## Services
| Service | Port | DB | Features |
|---|---|---|---|
| User | 8081 | userdb:5432 | JWT auth, Redis sessions |
| Product | 8082 | productdb:5433 | Redis cache (15min TTL) |
| Order | 8083 | orderdb:5434 | Kafka producer, Saga |
| Inventory | 8084 | inventorydb:5435 | Redis distributed lock |
| Payment | 8085 | paymentdb:5436 | Kafka consumer+producer |
| Notification | 8086 | — | Kafka consumer, SES (Floci) |

## Kafka Topics
| Topic | Partitions | Publisher | Consumers |
|---|---|---|---|
| order.created | 12 | Order | Payment, Inventory, Notification |
| payment.processed | 12 | Payment | Order, Notification |
| inventory.updated | 6 | Inventory | Order |
| order.confirmed | 6 | Order | Notification |
| notification.send | 4 | Multiple | Notification |
| order.cancelled | 3 | Order | Inventory, Notification |
