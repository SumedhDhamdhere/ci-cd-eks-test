# 06 — Runbook (Run It, Break It, Fix It)

## Level 1 — Docker Compose (5 minutes, easiest)

```bash
# one-time: create .env in project root (see .env values in team vault)
docker-compose up --build -d
docker ps                                   # expect ~19 containers
curl http://localhost:8000/api/products     # through Kong — should answer
```

Full user journey by hand:
```bash
# 1. register
curl -X POST http://localhost:8000/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Ali","email":"a@a.com","password":"password123"}'
# 2. create product
curl -X POST http://localhost:8000/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Phone","price":499.99,"category":"electronics"}'
# 3. add stock
curl -X POST http://localhost:8000/api/inventory/1/restock \
  -H "Content-Type: application/json" -d '{"quantity":10}'
# 4. order it
curl -X POST http://localhost:8000/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"shippingAddress":"Pune","items":[{"productId":1,"productName":"Phone","quantity":1,"price":499.99}]}'
# 5. a few seconds later — status should be PAID (the saga ran!)
curl http://localhost:8000/api/orders/1
```

## Level 2 — Floci + Kubernetes (the full cloud simulation)

```bash
docker-compose up -d floci        # start fake AWS
bash scripts/01-vpc.sh            # network
bash scripts/02-alb.sh            # front gate
bash scripts/03-ecr.sh            # build+push images
bash scripts/04-eks.sh            # kubernetes cluster
bash scripts/05-deploy.sh         # deploy everything
kubectl get pods -n ecommerce     # all should be Running
```

## Level 3 — CI/CD

```bash
git push origin develop    # → tests + auto deploy-dev + smoke test
# PR develop→main → merge → approve the production gate in GitHub UI
```

## Debug cheat-sheet (the commands that solve 90% of problems)

| Symptom | Command | What to look for |
|---|---|---|
| Pod not starting | `kubectl describe pod <p> -n ecommerce` | Events at the bottom: ImagePullBackOff? CreateContainerConfigError (missing Secret)? |
| App crashing | `kubectl logs <p> -n ecommerce` | Java stack trace — usually DB not reachable or missing env var |
| Pod restarts repeatedly | `kubectl get pods -n ecommerce` (RESTARTS column) | livenessProbe failing → app too slow to start or /health broken |
| Order stuck PENDING | `kubectl logs -l app=inventory-service -n ecommerce` | Did inventory consume order.created? Stock available? |
| 404 through Kong | `curl localhost:8001/routes` | Is the route there? `strip_path: false` present? |
| Kafka silence | exec into kafka pod → `kafka-topics --list --bootstrap-server localhost:9092` | Topics exist? |
| Everything weird | `docker-compose down -v && docker-compose up --build -d` | Nuclear option (wipes data) |

## Golden rules

1. **Logs first.** 90% of answers are in `kubectl logs` / `docker logs`.
2. **Events second.** `kubectl describe pod` events explain why K8s won't start something.
3. Container can't see `localhost` — services reach each other **by name**.
4. Killed a pod and it came back? That's not a bug — that's the reconciliation loop (doc 02).
