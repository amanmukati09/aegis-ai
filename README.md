# AegisAI

Autonomous incident intelligence platform — anomaly detection, causal/root-cause diagnosis, RL-based triage, and an LLM copilot for SRE/DevOps teams.

> **v2 rebuild.** Migrated from a Python-only hackathon app (FastAPI + Gradio + SQLite + Ollama) to a modular, production-shaped stack.

## Stack

| Layer | Tech |
|---|---|
| Frontend | Next.js 14 (App Router, TypeScript, Tailwind, shadcn/ui) |
| Gateway | Spring Boot 3, Java 21, Maven (REST + WebSocket, virtual threads) |
| ML service | FastAPI (Python 3.11), pluggable AI providers |
| Database | PostgreSQL 16 + pgvector + Flyway |
| Cache / pub-sub | Redis 7 |
| AI providers | **Groq (default, free)** · Gemini · OpenRouter · Ollama (optional local) · OpenAI/Anthropic (optional) |

Everything heavy is **optional and pluggable by config** — Kafka/Flink, Neo4j, TimescaleDB, Prometheus/Grafana are adapters that turn on via profiles + `.env`, never a rewrite.

## Quick start (Docker-only)

You need **only Docker + docker-compose** on the host. No JDK/Node/Python required.

```bash
cp .env.example .env          # then fill in secrets (JWT_SECRET, GROQ_API_KEY, ...)
cd infra/docker
docker compose up -d --build  # core: postgres + redis + gateway + ml + frontend
docker compose ps
```

- Frontend: http://localhost:3000
- Gateway:  http://localhost:8080/actuator/health
- ML:       http://localhost:8001/healthz

### Optional overlays (off by default)

```bash
docker compose -f docker-compose.yml -f docker-compose.kafka.yml up -d    # + Kafka/Flink
docker compose -f docker-compose.yml -f docker-compose.graph.yml up -d    # + Neo4j
docker compose -f docker-compose.yml -f docker-compose.metrics.yml up -d  # + TimescaleDB/Prometheus/Grafana
```

## Layout

```
aegisai/
├── services/gateway/      Spring Boot gateway (transactional core, auth, orchestration)
├── services/ml-service/   FastAPI ML sidecar (inference/generation only)
├── frontend/              Next.js 14 app
├── infra/docker/          docker-compose (core + optional overlays)
├── infra/scripts/         deploy-to-ec2.sh, ec2-up.sh, setup-swap.sh
└── docs/                  RUNBOOK.md
```

See `../docs/MIGRATION_SPEC.md` and `../docs/PHASE_0_ARCHITECTURE.md` for the full design.

## Notes for the constrained host (3.8 GB EC2)

- Ollama is **not** in the core stack (too heavy for this box). Default LLM is **Groq** (hosted, free key). Ollama can be enabled later by config.
- Add swap before first run: `infra/scripts/setup-swap.sh` (see `docs/RUNBOOK.md`).
- Containers have memory limits set in the compose file.

## Security

- No secrets in the repo. All config via `.env` (git-ignored).
- JWT + BCrypt, OAuth2 (Google/GitHub/Microsoft), per-user API keys, RBAC (super_admin / org_admin / member), locked CORS, Redis-backed rate limiting.
