# AegisAI — Laptop Setup (Track C)

This guide gets AegisAI running on your **personal laptop** (Ryzen 7, 16 GB RAM, small GPU)
and turns on the **Track C** capabilities that stay dormant on the small cloud box:
streaming ingestion (Kafka), a time-series metrics store (TimescaleDB + Grafana), a graph
database (Neo4j), and the heavy-ML models (MTAD-GAT, PCMCI, DQN triage).

Nothing here touches any office/TCS/AWS account. Everything is free and self-hosted.

---

## 0. What you need (one-time)

Inside **WSL2 (Ubuntu)** on Windows:

- Docker Desktop with the WSL2 backend enabled, **or** Docker Engine + compose v2 inside WSL.
- `git`.

That's it. You do **not** need Java, Node, or Python on the host — everything builds in
containers. (You only need Python locally if you choose to run the heavy-ML pipeline
outside Docker.)

Verify:

```bash
docker --version
docker compose version
```

---

## 1. Get the code and configure

```bash
git clone <your-repo-url> aegisai
cd aegisai
cp .env.example .env
```

Edit `.env` and set at minimum:

- `JWT_SECRET` — any long random string (32+ chars).
- `POSTGRES_PASSWORD`, `REDIS_PASSWORD` — pick anything.
- `GROQ_API_KEY` — your free key from https://console.groq.com (default LLM provider).
- `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` — seeds your first super_admin.

Leave the Track C variables blank for now (`KAFKA_BOOTSTRAP`, `NEO4J_*`, `TIMESCALE_URL`).

---

## 2. Run the core (same as the cloud box)

```bash
cd infra/docker
docker compose --env-file ../../.env up -d --build
docker compose ps
```

- Frontend → http://localhost:3000
- Gateway  → http://localhost:8080/actuator/health
- ML       → http://localhost:8001/healthz

Log in with the bootstrap admin you set in `.env`. Everything from Batches 1–5 works here
exactly as on the cloud deployment. Track C reports **dormant**:

```bash
curl -s localhost:8080/api/trackc/status        # (needs an auth token) mode: "core"
curl -s localhost:8001/v1/heavy/status          # mode: "core", capabilities available:false
```

---

## 3. Turn on Track C infrastructure (overlays)

Each overlay is a compose file layered on top of the core. Your laptop has the RAM to run
these; the 3.8 GB cloud box does not.

### Kafka (streaming ingestion)

```bash
docker compose -f docker-compose.yml -f docker-compose.kafka.yml --env-file ../../.env up -d
```
Then in `.env`:
```
KAFKA_BOOTSTRAP=kafka:9092
SPRING_PROFILES_ACTIVE=dev,kafka
```

### TimescaleDB + Prometheus + Grafana (metrics)

```bash
docker compose -f docker-compose.yml -f docker-compose.metrics.yml --env-file ../../.env up -d
```
Then in `.env`:
```
TIMESCALE_URL=jdbc:postgresql://timescaledb:5432/aegisai_metrics
SPRING_PROFILES_ACTIVE=dev,timescale,metrics
```
Grafana → http://localhost:3001 · Prometheus → http://localhost:9090

### Neo4j (causal / dependency graph)

```bash
docker compose -f docker-compose.yml -f docker-compose.graph.yml --env-file ../../.env up -d
```
Then in `.env`:
```
NEO4J_URI=bolt://neo4j:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=neo4jpassword
SPRING_PROFILES_ACTIVE=dev,neo4j
```
Neo4j Browser → http://localhost:7474

> You can combine overlays by stacking `-f` flags and comma-joining the profiles, e.g.
> `SPRING_PROFILES_ACTIVE=dev,kafka,timescale,neo4j`.

After changing `.env`, recreate the gateway so it picks up the new profile:
```bash
docker compose --env-file ../../.env up -d gateway
```

---

## 4. Wire the adapters (small code step)

The repo already ships the **ports** and **dormant adapters** for Track C:

- Gateway: `services/gateway/src/main/java/ai/aegis/gateway/trackc/`
  - `IngestionSource`, `MetricsStore`, `GraphStore` interfaces
  - `DormantAdapters` provides no-op beans via `@ConditionalOnMissingBean`
  - `TrackCController` exposes `GET /api/trackc/status`

To activate a real adapter (example: Kafka ingestion):

1. Add the client dependency to `services/gateway/pom.xml`:
   ```xml
   <dependency>
     <groupId>org.springframework.kafka</groupId>
     <artifactId>spring-kafka</artifactId>
   </dependency>
   ```
2. Add a real bean, e.g. `KafkaIngestionSource implements IngestionSource`, annotated
   `@Component @Profile("kafka")`. Because the dormant beans use
   `@ConditionalOnMissingBean`, your real bean **replaces** the dormant one automatically —
   no other code changes.
3. Rebuild the gateway: `docker compose --env-file ../../.env up -d --build gateway`.
4. `GET /api/trackc/status` now shows `mode: "extended"` and that capability `active: true`.

The same pattern applies to `MetricsStore` (Timescale via a second JDBC `DataSource`) and
`GraphStore` (Neo4j via `spring-boot-starter-data-neo4j`).

---

## 5. Turn on the heavy-ML models

The ML service ships **install-to-activate** stubs in `services/ml-service/app/heavy/`:

| Capability | Requires | Endpoint |
|---|---|---|
| MTAD-GAT anomaly detection | `torch` | `/v1/heavy/status` |
| PCMCI causal discovery | `tigramite` | `/v1/heavy/status` |
| DQN triage (upgrade from tabular Q-learning) | `torch` | `/v1/heavy/status` |

To activate:

1. Add the heavy deps to the ML image. Easiest: extend the ML Dockerfile to also install
   `requirements-heavy.txt`, **or** for quick local iteration exec into the container:
   ```bash
   docker exec -it aegisai-ml pip install -r requirements-heavy.txt
   ```
   (Persisting them means editing `services/ml-service/Dockerfile` to install both files.)
2. Implement `run()` in the relevant class in `app/heavy/models.py` using the now-available
   library (the stub raises `NotImplementedError` until you do).
3. Check activation:
   ```bash
   curl -s localhost:8001/v1/heavy/status   # capability flips to available: true
   ```

> **GPU:** your small GPU can accelerate torch. Enable GPU passthrough in Docker Desktop
> (WSL2 CUDA), install the CUDA build of torch, and torch will use it automatically. CPU
> works too, just slower.

The tabular RL triage agent already runs in the core; DQN is a drop-in policy upgrade
behind the same `/v1/triage/*` contract, so the UI needs no changes.

---

## 6. Sanity check

```bash
# core health
curl -s localhost:8080/actuator/health
curl -s localhost:8001/healthz

# Track C capability report (extended once overlays + adapters are on)
curl -s localhost:8001/v1/heavy/status | python3 -m json.tool
```

Log in at http://localhost:3000 and confirm the dashboard, copilot streaming, RL triage,
and insights pages all work — they behave identically to the cloud deployment, now with the
extra infrastructure available underneath.

---

## Summary of the "few steps"

```bash
# once
git clone <repo> aegisai && cd aegisai && cp .env.example .env   # fill secrets

# core
cd infra/docker && docker compose --env-file ../../.env up -d --build

# add whichever Track C pieces you want
docker compose -f docker-compose.yml -f docker-compose.kafka.yml   --env-file ../../.env up -d
docker compose -f docker-compose.yml -f docker-compose.metrics.yml --env-file ../../.env up -d
docker compose -f docker-compose.yml -f docker-compose.graph.yml   --env-file ../../.env up -d
# set SPRING_PROFILES_ACTIVE + connection vars in .env, then:
docker compose --env-file ../../.env up -d gateway
```

Everything is reversible: stop an overlay with `docker compose ... down` and clear its
profile from `.env`, and the core drops straight back to dormant mode.
