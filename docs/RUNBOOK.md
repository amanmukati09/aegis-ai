# AegisAI — RUNBOOK (build here, run on the Linux EC2)

This is the operational loop for the whole project. **Author in Kiro (this Windows box) → transfer to the Linux EC2 → build/run/test there → send results back.** Nothing builds or runs on the Windows RDP box (RAM-constrained), and nothing touches office/TCS AWS, GitHub, or git.

Repo lives here at `aegis-v2/aegisai/` and transfers to the EC2 as `~/aegisai/`.

---

## 0. One-time on the EC2: add swap (recommended)

The box has 3.8 GB RAM and no swap. Give it headroom before the first Docker build:

```bash
sudo bash infra/scripts/setup-swap.sh   # creates a 4GB swapfile, persists in /etc/fstab
```

---

## 1. Transfer the repo to the EC2

Run these **from the Windows box**, in `C:\Users\Administrator\Desktop\MY Files` (where the pem key is).

**Option A — quick full copy (PowerShell `scp`):**
```powershell
# Create a tarball of the authored repo, copy it up, extract on the EC2.
tar --exclude='node_modules' --exclude='target' --exclude='.next' -czf aegisai.tgz -C aegis-v2 aegisai
scp -i "storedprocedurekeypairtest.pem" aegisai.tgz ec2-user@ec2-34-228-62-32.compute-1.amazonaws.com:~/
ssh -i "storedprocedurekeypairtest.pem" ec2-user@ec2-34-228-62-32.compute-1.amazonaws.com "tar -xzf ~/aegisai.tgz -C ~/ && rm ~/aegisai.tgz && ls ~/aegisai"
del aegisai.tgz
```

**Option B — from a Linux/WSL terminal (rsync, incremental):**
```bash
rsync -az --delete \
  --exclude node_modules --exclude target --exclude .next \
  -e "ssh -i storedprocedurekeypairtest.pem" \
  aegis-v2/aegisai/ ec2-user@ec2-34-228-62-32.compute-1.amazonaws.com:~/aegisai/
```

---

## 2. Configure `.env` on the EC2

```bash
cd ~/aegisai
cp .env.example .env
# Edit .env: set JWT_SECRET (any long random string), POSTGRES_PASSWORD, REDIS_PASSWORD,
# and GROQ_API_KEY (your personal key). Everything else can stay blank for Phase 0.
nano .env
```

> Phase 0 boots fine even without GROQ_API_KEY — the ML `/v1/models` endpoint just returns an empty model list until a key is set.

---

## 3. Bring up the core stack

```bash
cd ~/aegisai
bash infra/scripts/ec2-up.sh
```

This builds and starts: **postgres (pgvector) · redis · ml-service · gateway · frontend**. First build pulls base images and compiles the JVM app — give it a few minutes.

Check status:
```bash
docker compose --env-file ../../.env ps   # from infra/docker, or just: cd infra/docker && docker compose --env-file ../../.env ps
```

---

## 4. Verify (Phase 0 definition of done)

```bash
bash infra/scripts/smoke-test.sh
```

Expected: all five checks PASS —
- `http://localhost:8080/actuator/health` → `{"status":"UP"}`
- `http://localhost:8080/api/health` → gateway ok
- `http://localhost:8001/healthz` → ml ok
- `http://localhost:8001/v1/models` → JSON (empty models list until GROQ key set)
- `http://localhost:3000` → frontend HTML (health page shows both backends "healthy")

To reach the UI from your browser, use the EC2 public IP (open the ports in the security group only if you intend to — otherwise use an SSH tunnel):
```bash
# SSH tunnel from the Windows box (no security-group changes needed):
ssh -i "storedprocedurekeypairtest.pem" -L 3000:localhost:3000 -L 8080:localhost:8080 -L 8001:localhost:8001 ec2-user@ec2-34-228-62-32.compute-1.amazonaws.com
# then open http://localhost:3000 in your browser
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

## 6. Per-service tests (optional, run inside Docker)

```bash
# ML service tests
docker run --rm -v ~/aegisai/services/ml-service:/app -w /app python:3.11-slim \
  sh -c "pip install -q -r requirements.txt && pytest -q"

# Gateway tests (unit; full context tests come in Phase 1 with Testcontainers)
docker run --rm -v ~/aegisai/services/gateway:/app -w /app maven:3.9-eclipse-temurin-21 \
  mvn -q -B test
```

---

## 7. The loop

1. I edit files here in Kiro.
2. You re-run the transfer (step 1) — rsync only sends changed files.
3. `bash infra/scripts/ec2-up.sh` (rebuilds changed images) and `smoke-test.sh`.
4. Paste back any errors/logs.
5. I fix here. Repeat.

Optional heavy overlays (only on a bigger host, not this 3.8GB box):
```bash
cd infra/docker
docker compose -f docker-compose.yml -f docker-compose.graph.yml   --env-file ../../.env up -d   # + Neo4j
docker compose -f docker-compose.yml -f docker-compose.kafka.yml   --env-file ../../.env up -d   # + Kafka
docker compose -f docker-compose.yml -f docker-compose.metrics.yml --env-file ../../.env up -d   # + Timescale/Prometheus/Grafana
```
