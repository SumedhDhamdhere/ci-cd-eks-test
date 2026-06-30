#!/bin/bash
echo "======================================"
echo " E-Commerce Platform — Floci Setup"
echo "======================================"

# Step 1: Start all containers
echo ""
echo "Step 1: Starting Docker containers..."
docker compose up -d
echo "Waiting 30s for services to start..."
sleep 30

# Step 2: Configure AWS CLI for Floci
echo ""
echo "Step 2: Configuring AWS CLI for Floci..."
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=ap-south-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Step 3: Create S3 bucket for product images
echo ""
echo "Step 3: Creating S3 bucket..."
aws s3 mb s3://ecommerce-product-images \
  --endpoint-url http://localhost:4566
aws s3 mb s3://ecommerce-backups \
  --endpoint-url http://localhost:4566
echo "✅ S3 buckets created"

# Step 4: Verify Kafka topics
echo ""
echo "Step 4: Verifying Kafka topics..."
docker exec kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --list

# Step 5: Test Redis
echo ""
echo "Step 5: Testing Redis..."
docker exec redis redis-cli -a redis_pass ping

# Step 6: Health check all services
echo ""
echo "======================================"
echo " All services status:"
echo "======================================"
echo "Floci:        http://localhost:4566"
echo "Kong Gateway: http://localhost:8000"
echo "Kong Admin:   http://localhost:8001"
echo ""
echo "Start services separately:"
echo "  cd user-service    && mvn spring-boot:run"
echo "  cd product-service && mvn spring-boot:run"
echo "  cd order-service   && mvn spring-boot:run"
echo "  cd inventory-service && mvn spring-boot:run"
echo "  cd payment-service && mvn spring-boot:run"
echo "  cd notification-service && mvn spring-boot:run"
echo ""
echo "Test order flow via Kong:"
echo "  POST http://localhost:8000/api/users/register"
echo "  POST http://localhost:8000/api/users/login"
echo "  POST http://localhost:8000/api/orders"
echo "======================================"
