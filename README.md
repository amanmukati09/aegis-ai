<div align="center">

# 🛡️ AegisAI

**Autonomous incident intelligence for SRE & DevOps teams.**

Anomaly detection · root-cause diagnosis · RL-based triage · an LLM copilot — in one modular, self-hostable platform.

[![Stack](https://img.shields.io/badge/stack-Next.js%2014%20·%20Spring%20Boot%203%20·%20FastAPI-6366f1)](#-architecture)
[![Java](https://img.shields.io/badge/Java-21-orange)](#-architecture)
[![License](https://img.shields.io/badge/license-MIT-green)](#-license)
[![Docker](https://img.shields.io/badge/run-Docker%20Compose-2496ed)](#-quick-start)

</div>

---

## Overview

AegisAI ingests logs and telemetry, detects anomalies, diagnoses root cause, and recommends (or drafts) remediations — with an LLM copilot on top and a reinforcement-learning agent that learns how to prioritize incidents from your own resolution history.

It is built as a **modular, production-shaped stack**: a Next.js frontend, a Spring Boot gateway that owns all transactional data and auth, and a stateless FastAPI ML sidecar. Everything heavy — Kafka, Neo4j, TimescaleDB, deep-learning models — is an **optional adapter you turn on by config**, never a rewrite. The core runs comfortably on a small (~4 GB) box; the extended stack lights up on a capable machine.

> Migrated from a Python-only hackathon app (FastAPI + Gradio + SQLite + Ollama) into a clean multi-service architecture with multi-tenant auth, RBAC, and defense-in-depth guardrails.

---

## ✨ Features

### Incident intelligence
- **AI diagnosis pipeline** — anomaly detection → root-cause analysis → remediation suggestions.
- **Incident timeline** — reconstructed lifecycle (detected → diagnosed → claimed → resolved).
- **Similar incidents** — semantic retrieval via pgvector cosine similarity (RAG-ready).
- **Blast radius** — impact estimation over a component co-occurrence graph.
- **Bulk analysis** — async ingestion of large log batches (~10k+ lines) with a job queue.

### Ingestion & reporting
- **Flexible log input** — drag-and-drop file, paste, or fetch from a URL (SSRF-guarded).
- **Structured PDF reports** — generated server-side with sections, tables, and summaries.
- **Runbook generation** — phased, actionable runbooks (detect → diagnose → mitigate → validate).

### AI copilot
- **Streaming chat** — real token-by-token SSE responses, persisted per session.
- **Conversation search** — full-text search across your own chat history.
- **Sentiment / urgency** — lexicon-based tone signal on operator messages.
- Multi-turn memory, rename/delete/edit/regenerate, markdown rendering.

### Analytics suite
- **Health score** — live posture from open/critical counts and incident velocity.
- **AI benchmark** — diagnosis accuracy, remediation rate, resolution rate, MTTR.
- **Predictive patterns** — peak hours, high-risk components, severity trends, overall risk.
- **Incident clustering** — greedy similarity grouping into labeled clusters.
- **NL → SQL** — ask questions in plain English; the gateway safely runs org-scoped SELECTs.

### Reinforcement-learning triage
- Tabular **Q-learning** agent that prioritizes incidents (P1–P5) and **learns from resolution speed vs. severity**.
- Rule-based cold start, persisted policy, and a **DQN-ready** contract (drop-in neural upgrade).

### Platform
- **Auth**: JWT + BCrypt, OAuth2 (Google / GitHub / Microsoft), per-user API keys.
- **Multi-tenant RBAC**: `super_admin` / `org_admin` / `member`, strict org isolation.
- **Alerting**: pluggable channels — Slack, Teams, PagerDuty, Opsgenie, SMTP, stdout.
- **Guardrails**: prompt-injection blocking, PII masking, destructive-output filtering.
- **Ops**: audit log, notifications, live monitor, streams, workspaces, rate limiting.
- **Premium UI**: system/dark/light theme, animated dashboard, responsive charts.

---

## 🏗️ Architecture

```
                    ┌──────────────┐
   Browser  ─────▶  │  Next.js 14  │  (proxies /api/gateway/* → gateway; no CORS)
                    └──────┬───────┘
                           │
                    ┌──────▼────────────────┐        ┌───────────────┐
                    │  Spring Boot Gateway   │◀──────▶│  FastAPI ML    │
                    │  (Java 21)             │  HTTP  │  sidecar       │
                    │  auth · RBAC · CRUD    │        │  inference /   │
                    │  orchestration · guard │        │  generation    │
                    └──┬───────────┬─────────┘        └───────┬───────┘
                       │           │                          │
                 ┌─────▼───┐  ┌────▼────┐              ┌───────▼────────┐
                 │Postgres │  │  Redis  │              │  AI providers  │
                 │+pgvector│  │ cache   │              │  Groq · Gemini │
                 └─────────┘  └─────────┘              │  Ollama · …    │
                                                       └────────────────┘
```

| Layer | Tech |
|---|---|
| Frontend | Next.js 14 (App Router, TypeScript, Tailwind, framer-motion, recharts) |
| Gateway | Spring Boot 3, Java 21, Maven — REST + SSE, virtual threads, Flyway |
| ML service | FastAPI (Python 3.11), pluggable AI providers |
| Database | PostgreSQL 16 + pgvector |
| Cache | Redis 7 |
| AI providers | **Groq (default, free)** · Gemini · OpenRouter · Ollama · OpenAI · Anthropic |

**Design principle:** the gateway owns the data and all security decisions; the ML service is stateless and never sees another org's data. Optional infrastructure attaches through ports + adapters (see [Track C](#-track-c-extended-stack)).

---

## 🚀 Quick start

You need **only Docker + Docker Compose**. No JDK / Node / Python required — everything builds in containers.

```bash
git clone https://github.com/amanmukati09/cloud-hackathon-tcs-amd.git aegisai
cd aegisai
cp .env.example .env          # then fill in JWT_SECRET, POSTGRES_PASSWORD, GROQ_API_KEY, ...

cd infra/docker
docker compose --env-file ../../.env up -d --build
docker compose ps
```

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| Gateway health | http://localhost:8080/actuator/health |
| ML health | http://localhost:8001/healthz |

Log in with the bootstrap admin you set in `.env` (`BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD`).

### Configuration essentials

| Variable | Purpose |
|---|---|
| `JWT_SECRET` | Long random string (32+ chars) for signing tokens |
| `POSTGRES_PASSWORD`, `REDIS_PASSWORD` | Database / cache credentials |
| `GROQ_API_KEY` | Free key from [console.groq.com](https://console.groq.com) — default LLM provider |
| `BOOTSTRAP_ADMIN_*` | Seeds the first `super_admin` on startup |
| `DEFAULT_AI_PROVIDER` / `DEFAULT_AI_MODEL` | Provider/model selection (Groq model IDs rotate) |

See `.env.example` for the full list, including OAuth and alert-channel keys. **No secrets are ever committed** — `.env` is git-ignored.

---

## 📚 API surface (selected)

All routes are under the gateway (`/api/*`) and require a Bearer token unless noted.

| Area | Endpoint |
|---|---|
| Auth | `POST /api/auth/register` · `POST /api/auth/login` |
| Incidents | `GET/POST /api/incidents` · `POST /api/incidents/{id}/resolve` |
| Timeline / similar | `GET /api/incidents/{id}/timeline` · `GET /api/incidents/{id}/similar` |
| Runbook / report | `POST /api/incidents/{id}/runbook` · `POST /api/incidents/{id}/report.pdf` |
| Ingest | `POST /api/ingest/from-url` · `POST /api/jobs/bulk-analyze` |
| Chat | `POST /api/chat/stream` (SSE) · `GET /api/chat/search?q=` · `POST /api/chat/sentiment` |
| Triage (RL) | `GET /api/triage/queue` · `POST /api/triage/train` |
| Insights | `GET /api/insights/{health-score,benchmark,predictions,clusters}` |
| Analytics | `POST /api/analytics/ask` (NL→SQL) · `GET /api/analytics/timeseries` |
| Dependency | `GET /api/dependency/graph` · `GET /api/dependency/blast-radius/{component}` |
| Platform | `/api/streams` · `/api/notifications` · `/api/workspaces` · `/api/api-keys` · `/api/admin/*` |
| Track C | `GET /api/trackc/status` |

---

## 🧩 Track C (extended stack)

Optional, heavy capabilities ship **dormant** — the interfaces, wiring, status endpoints, and Compose overlays are all present, but the infrastructure stays off so the core stays lean. Activate them on a capable machine (see **[`LAPTOP_SETUP.md`](LAPTOP_SETUP.md)**).

```bash
# + Kafka streaming ingestion
docker compose -f docker-compose.yml -f docker-compose.kafka.yml   --env-file ../../.env up -d
# + TimescaleDB + Prometheus + Grafana
docker compose -f docker-compose.yml -f docker-compose.metrics.yml --env-file ../../.env up -d
# + Neo4j causal / dependency graph
docker compose -f docker-compose.yml -f docker-compose.graph.yml   --env-file ../../.env up -d
```

| Capability | Adapter | Heavy dep |
|---|---|---|
| Streaming ingestion | Kafka | `spring-kafka` |
| Metrics store | TimescaleDB + Grafana | second `DataSource` |
| Causal / dependency graph | Neo4j | `spring-boot-starter-data-neo4j` |
| Anomaly detection | MTAD-GAT | `torch` |
| Causal discovery | PCMCI | `tigramite` |
| Neural triage | DQN (upgrade from tabular) | `torch` |

Gateway adapters use `@ConditionalOnMissingBean`, so adding a real adapter bean transparently replaces the dormant one. `GET /api/trackc/status` and `GET /v1/heavy/status` report whether each capability is `core` (dormant) or `extended` (active).

---

## 📂 Project layout

```
aegisai/
├── services/gateway/      Spring Boot gateway — auth, RBAC, CRUD, orchestration, guardrails
│   └── src/main/java/ai/aegis/gateway/
│       ├── incident · chat · triage · insights · analytics · dependency
│       ├── alert · notification · stream · workspace · apikey · admin
│       └── trackc/         dormant Kafka/Timescale/Neo4j ports + adapters
├── services/ml-service/   FastAPI ML sidecar — inference / generation only
│   └── app/
│       ├── routers/        chat · diagnosis · advanced · bulk · embed · triage · heavy
│       ├── agents/         rl_triage · sentiment · prompts
│       ├── providers/      pluggable LLM providers (groq, ollama, …)
│       └── heavy/          install-to-activate heavy-ML stubs (MTAD-GAT/PCMCI/DQN)
├── frontend/              Next.js 14 app (dashboard, copilot, triage, insights)
├── infra/docker/          docker-compose (core + kafka/metrics/graph overlays)
├── infra/scripts/         verify + smoke scripts, EC2 helpers
├── docs/                  RUNBOOK.md
└── LAPTOP_SETUP.md        Track C activation guide (WSL)
```

---

## 🔒 Security

- No secrets in the repo — all config via git-ignored `.env`.
- JWT + BCrypt, OAuth2, per-user API keys, three-tier RBAC with strict org isolation.
- Locked CORS, Redis-backed rate limiting.
- Defense-in-depth guardrails: prompt-injection blocking, PII masking, destructive-output filtering.
- NL→SQL is SELECT-only, single-statement, and org-scoped by query rewriting — a user can never read another org's data.
- URL ingestion is SSRF-guarded (blocks loopback, private, link-local, and cloud-metadata ranges).

---

## 🧪 Development notes

- **Migrations** are additive (Flyway); never edit an applied migration.
- **AI models**: Groq rotates model IDs — verify current IDs before pinning.
- **Constrained hosts**: Ollama and all Track C infra stay off by default; containers have memory limits set in the Compose file.
- Verification scripts live in `infra/scripts/` (`smoke-full.sh` exercises every feature end-to-end).

---

## 📄 License

MIT — see [`LICENSE`](LICENSE).

<div align="center">
<sub>Built with a focus on modularity: start small, scale by config, never by rewrite.</sub>
</div>
