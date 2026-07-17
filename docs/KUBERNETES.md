# Kubernetes — Architecture & Internal Workflow (Newbie-Friendly)

## A. First, the words (no jargon left unexplained)

| Word | Plain meaning |
|---|---|
| Container | One running copy of your app (built from a Docker image) |
| Pod | Kubernetes' wrapper around 1 (or more) containers — the unit K8s manages |
| Deployment | "Keep N identical pods of this app alive, always" — for **stateless** apps |
| StatefulSet | Same, but for apps that hold data (Postgres/Kafka/Redis) — stable name + own disk |
| Service | A fixed internal "phone number" that forwards to whichever pod is healthy |
| Label / Selector | Sticky tag on pods / the search query that finds pods by tag |
| HPA | Autoscaler: CPU high → add pods, CPU low → remove pods |
| PVC | A disk that survives even when its pod dies |

## B. THE core workflow — what happens inside Kubernetes when you apply a Deployment

This loop **never stops running**. It is the heart of Kubernetes ("reconciliation loop").

```mermaid
flowchart TB
    A["1. You run: kubectl apply -f deployments.yaml<br/>(desired state: replicas = 1, label app: user-service)"]
    B["2. Deployment controller creates a ReplicaSet<br/>(job: keep pod count = replicas, forever)"]
    C["3. ReplicaSet counts pods matching<br/>selector app: user-service"]
    D{"count == replicas?"}
    E["4. Create missing Pod from template<br/>(template's labels = app: user-service,<br/>so ReplicaSet can find it later)"]
    F["5. Scheduler picks a Node<br/>(which machine has free CPU/memory?)"]
    G["6. kubelet on that Node pulls the image<br/>from the registry and starts the container"]
    H["7. Pod gets an internal IP"]
    I["8. Service (selector app: user-service)<br/>notices the new pod → adds its IP to its list"]
    J["9. kube-proxy updates network rules →<br/>traffic to 'user-service:8081' now reaches this pod"]
    K["10. kubelet keeps hitting livenessProbe<br/>every 10s (/actuator/health)"]
    L{"probe fails 3x?"}
    M["Kill pod"]
    N["Pod count drops below replicas"]
    OK["✅ Steady state — loop keeps watching"]

    A --> B --> C --> D
    D -- "no, too few" --> E --> F --> G --> H --> I --> J --> K
    D -- "yes" --> OK
    K --> L
    L -- "yes" --> M --> N --> C
    L -- "no" --> OK
    OK -. "any pod dies, node crashes,<br/>or you change replicas" .-> C
```

**The one sentence to remember:** you declare *what you want* (replicas: 1), and controllers loop forever comparing "what I want" vs "what exists," fixing any difference — that's why a killed pod comes back by itself.

## C. How labels glue everything together

```mermaid
flowchart LR
    DEP["Deployment<br/>selector: app=user-service"] -- "finds/creates" --> POD["Pod<br/>label: app=user-service"]
    SVC["Service<br/>selector: app=user-service"] -- "routes traffic to" --> POD
    HPA["HPA<br/>targets Deployment user-service"] -- "changes replicas of" --> DEP
```

Three different objects never reference each other by ID — **they all just match the same label**. Change the label on one side and everything silently disconnects (a classic beginner bug).

## D. This project's cloud architecture (zones)

```mermaid
flowchart TB
    CL([Client]) --> R53[Route 53 DNS] --> WAF[WAF] 
    subgraph VPC["VPC 10.0.0.0/16"]
        subgraph PUB["🟢 Public subnet (internet-facing)"]
            WAF --> ALB[ALB] --> KONG[Kong :8000]
        end
        subgraph PRIV["🟣 Private subnet — stateless apps (Deployments)"]
            KONG --> S1[user :8081] & S2[product :8082] & S3[order :8083] & S4[inventory :8084] & S5[payment :8085] & S6[notify :8086]
        end
        subgraph DATA["🟠 Private subnet — stateful data (StatefulSets + PVC)"]
            KAF[(Kafka)] 
            RED[(Redis)]
            P1[(userdb)] 
            P2[(productdb)] 
            P3[(orderdb)] 
            P4[(inventorydb)] 
            P5[(paymentdb)]
        end
        S1---P1
        S2---P2
        S3---P3
        S4---P4
        S5---P5
        S1---RED
        S2---RED
        S4---RED
        S3<-->KAF
        S4<-->KAF
        S5<-->KAF
        S6<-->KAF
    end
    subgraph AWSX["🟧 AWS managed (outside VPC, via Floci)"]
        SES[SES email] 
        ECR[ECR images] 
        EKS[EKS control plane] 
        CW[CloudWatch]
    end
    S6 --> SES
```

| Zone | Object type used | Why |
|---|---|---|
| 🟢 Public | Service type LoadBalancer | Must be reachable from internet |
| 🟣 Private apps | Deployment | Stateless — safe to kill/recreate |
| 🟠 Private data | StatefulSet + PVC | Holds data — needs stable identity + disk |
| 🟧 AWS managed | (not K8s) | AWS runs these for you |

## E. Apply order (why `05-deploy.sh` goes 1→8)

```
1 Namespace → 2 Secrets/ConfigMaps → 3 Postgres+Redis+Kafka (wait ready)
→ 4 Kong → 5 Microservices → 6 Register Kong in ALB → 7 Monitoring → 8 Verify
```

Rule behind the order: **a thing must exist before anything that references it starts.** Services crash-loop if the DB isn't up; a pod fails with `CreateContainerConfigError` if its Secret doesn't exist yet.

## F. Same YAML, three environments

| Environment | Cluster comes from | Image registry |
|---|---|---|
| 🔵 Docker Compose | (no K8s at all) | local build |
| 🟠 Floci | `04-eks.sh` fake-EKS → k3s | localhost:5100 |
| 🟣 CI | k3d created per pipeline run | `k3d image import` (no registry) |

The YAML in `k8s/` never changes — only `REGISTRY`/`IMAGE_TAG` placeholders and Secret values get swapped per environment.
