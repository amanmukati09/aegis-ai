# AegisAI — Operations Runbook

Operational guide for deploying and running AegisAI on a Linux host (VM, cloud instance, or
your laptop). The stack is Docker-only: no JDK / Node / Python needed on the host.

Replace the placeholders below with your own values:

- `HOST` — your server, e.g. `ec2-user@your-host.example.com`
- `KEY.pem` — your SSH key (omit `-i KEY.pem` if you use an SSH agent)

---

## 0. One-time: add swap (small hosts)

On a memory-constrained box (~4 GB), give Docker headroom before the first build:

```bash
sudo bash infra/scripts/setup-swap.sh   # creates a 4GB swapfile, persists in /etc/fstab
```

---

## 1. Get the code onto the host

**Clone directly on the host:**
```bash
git clone https://github.com/amanmukati09/cloud-hackathon-tcs-amd.git aegisai
cd aegisai
```

**Or transfer a local checkout (rsync, incremental):**
```bash
rsync -az --delete \
  --exclude node_modules --exclude target --exclude .next \
  -e "ssh -i KEY.pem" \
  aegisai/ HOST:~/aegisai/
```

---

## 2. Configure `.env`

```bash
cd ~/aegisai
cp .env.example .env
# Set JWT_SECRET (long random string), POSTGRES_PASSWORD, REDIS_PASSWORD,
# and GROQ_API_KEY (your key). See .env.example for the full list.
nano .env
```

> The stack boots even without `GROQ_API_KEY` — the ML `/v1/models` endpoint just returns an
> empty list until a key is set.

---

## 3. Bring up the core stack

```bash
cd ~/aegisai/infra/docker
docker compose --env-file ../../.env up -d --build
docker compose --env-file ../../.env ps
```

Starts: **postgres (pgvector) · redis · ml-service · gateway · frontend**. The first build
compiles the JVM app — give it a few minutes.

---

## 4. Verify

```bash
bash infra/scripts/smoke-test.sh     # core Phase-0 checks
bash infra/scripts/smoke-full.sh     # full end-to-end feature smoke
```

Expected core checks:
- `http://localhost:8080/actuator/health` → `{"status":"UP"}`
- `http://localhost:8001/healthz` → ml ok
- `http://localhost:3000` → frontend HTML

To reach the UI from your browser without opening ports, use an SSH tunnel:
```bash
ssh -i KEY.pem -L 3000:localhost:3000 -L 8080:localhost:8080 -L 8001:localhost:8001 HOST
# then open http://localhost:3000
```

---

## 5. Logs & teardown

```bash
bash infra/scripts/ec2-logs.sh            # all services
bash infra/scripts/ec2-logs.sh gateway    # one service

cd ~/aegisai/infra/docker
docker compose --env-file ../../.env down          # stop
docker compose --env-file ../../.env down -v       # stop + wipe volumes (fresh DB)
```

---

## 6. Per-service tests (inside Docker)

```bash
# ML service tests
docker run --rm -v ~/aegisai/services/ml-service:/app -w /app python:3.11-slim \
  sh -c "pip install -q -r requirements.txt && pytest -q"

# Gateway tests (Testcontainers spins up Postgres)
docker run --rm -v ~/aegisai/services/gateway:/app -w /app maven:3.9-eclipse-temurin-21 \
  mvn -q -B test
```

---

## 7. Optional Track C overlays

Only on a capable host (not a ~4 GB box). See [LAPTOP_SETUP.md](../LAPTOP_SETUP.md) for the
full activation guide.

```bash
cd infra/docker
docker compose -f docker-compose.yml -f docker-compose.graph.yml   --env-file ../../.env up -d   # + Neo4j
docker compose -f docker-compose.yml -f docker-compose.kafka.yml   --env-file ../../.env up -d   # + Kafka
docker compose -f docker-compose.yml -f docker-compose.metrics.yml --env-file ../../.env up -d   # + Timescale/Prometheus/Grafana
```
