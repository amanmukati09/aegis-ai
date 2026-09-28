# AegisAI — Architecture

This document explains how AegisAI is put together: the services, how data and requests
flow, the security model, and the design principles that keep the core lean while allowing
heavy infrastructure to be added by configuration.

---

## 1. System overview

AegisAI is three cooperating services plus a datastore layer:

```
                        ┌────────────────────┐
   Browser  ─────────▶  │   Next.js 14 (web) │
                        │   App Router / TS  │
                        └─────────┬──────────┘
                    /api/gateway/* │ (Next rewrite → no CORS, works via tunnel)
                                   ▼
                        ┌────────────────────────┐        ┌────────────────────┐
                        │  Spring Boot Gateway    │  HTTP  │  FastAPI ML sidecar │
                        │  (Java 21, virtual thr) │◀──────▶│  inference / gen    │
                        │  • auth · JWT · OAuth2  │        │  • providers        │
                        │  • RBAC · multi-tenant  │        │  • embeddings       │
                        │  • incident/chat/CRUD   │        │  • RL triage        │
                        │  • orchestration        │        │  • sentiment        │
                        │  • guardrails           │        │  • PDF / runbook    │
                        └───┬─────────────┬───────┘        └─────────┬──────────┘
                            │             │                          │
                     ┌──────▼─────┐  ┌────▼─────┐            ┌────────▼─────────┐
                     │ PostgreSQL │  │  Redis   │            │  AI providers    │
                     │ + pgvector │  │  cache   │            │  Groq (default)  │
                     └────────────┘  └──────────┘            │  Gemini/Ollama/… │
                                                             └──────────────────┘
```

**Responsibilities**

| Service | Owns | Does not |
|---|---|---|
| Frontend | UI, session token in browser | Never talks to the gateway cross-origin; all calls proxied by Next |
| Gateway | All persistent data, auth, RBAC, tenancy, orchestration, guardrails | No model inference (delegates to ML) |
| ML service | Stateless inference/generation, embeddings, RL policy, report rendering | No user data ownership; never sees another org's data |

---

## 2. Request flow

The browser only ever talks to the Next.js origin. Next rewrites `/api/gateway/*` to the
gateway container (`next.config.mjs`), so there is no CORS surface and it works transparently
through an SSH tunnel.

A typical authenticated call:

1. Browser → `POST /api/gateway/incidents` with a Bearer JWT.
2. Next proxies to the gateway `POST /api/incidents`.
3. Gateway's security filter validates the JWT, resolves the `AuthPrincipal` (user + org +
   role), and enforces org scope.
4. Business logic runs against Postgres; if AI is needed, the gateway calls the ML sidecar
   over internal HTTP.
5. Response returns up the same path.

### Streaming (chat)

Chat uses Server-Sent Events end to end:

1. Gateway `POST /api/chat/stream` runs the input guardrail, persists the user message, and
   builds history — **in a committed transaction before streaming starts**.
2. It emits an `event: session` frame, then relays ML tokens as `data:` frames while
   accumulating the full reply.
3. On completion it runs the output guardrail, persists the assistant message, and audits —
   off the event loop via `Mono.fromCallable(...).subscribeOn(boundedElastic())` — then emits
   `data: [DONE]`.

The frontend reads the stream with `fetch` + a `ReadableStream` reader (not `EventSource`,
because it needs to POST a body and send an `Authorization` header).

---

## 3. Data model & persistence

- **PostgreSQL 16 + pgvector** is the system of record. Schema is managed by **Flyway**;
  migrations are strictly additive (never edit an applied migration).
- Multi-tenant: rows carry `org_id`; every query is org-scoped. Incidents, chat sessions,
  API keys, workspaces, notifications, and audit entries all belong to an organization.
- **pgvector**: incidents store a 384-dim `embedding`. Similar-incident search uses the
  cosine operator (`<=>`) with an org filter and self-exclusion.
- **Redis** backs caching and rate limiting.
- The RL triage Q-table persists to a small pickle on a named Docker volume (`ml_data`).

---

## 4. Security model

Defense in depth, enforced in the gateway (the single trust boundary for data):

- **Authentication**: JWT (JJWT) + BCrypt password hashing; OAuth2 login via
  Google / GitHub / Microsoft; per-user API keys.
- **Authorization / tenancy**: three roles —
  - `member` and `org_admin` see their organization's shared data;
  - `super_admin` (no org) is the only cross-org path.
- **AI guardrails** (four layers): input prompt-injection blocking, PII masking before text
  leaves for the model, a security system prompt, and destructive-output filtering on the
  reply.
- **NL→SQL safety**: generated SQL is SELECT-only, single-statement, and **org-scoped by
  query rewriting** (the `incidents` table is shadowed by a CTE pre-filtered to the caller's
  org), so isolation holds even if the model produces an unscoped query. Hard row cap applied.
- **SSRF guard**: URL log ingestion allows only http/https and blocks loopback, private,
  link-local, site-local, and cloud-metadata (`169.254.169.254`) addresses, with size/line
  caps.
- **Transport**: locked CORS, Redis-backed rate limiting, stateless sessions.

---

## 5. Extensibility — ports & adapters

New integrations plug in as beans against a port interface; nothing in the callers changes.

- **AI providers** (`ml-service/app/providers`): implement the `AiProvider` port and register
  it in the registry. Selection is per-request or by env default. Groq is the default; Gemini,
  OpenRouter, Ollama, OpenAI, and Anthropic slot in the same way.
- **Alert channels** (`gateway/.../alert`): implement `AlertChannel`; `AlertService`
  auto-discovers all beans. Slack, Teams, PagerDuty, Opsgenie, SMTP, and stdout ship today.
- **Track C** (`gateway/.../trackc`): ports for `IngestionSource`, `MetricsStore`,
  `GraphStore` with dormant no-op adapters wired via `@ConditionalOnMissingBean` — a real
  adapter bean transparently replaces the dormant one.

---

## 6. Track C — dormant by design

Heavy infrastructure and models ship **present but off** so the core runs on a ~4 GB box.
Everything needed to activate is in the repo: the ports, the dormant adapters, the status
endpoints, the Compose overlays, and the extra requirements file — but no heavy dependency is
in the core build.

| Capability | Dormant (core) | Activated (extended) |
|---|---|---|
| Ingestion | HTTP paste/file/URL | Kafka streaming source |
| Metrics | Postgres analytics | TimescaleDB + Prometheus + Grafana |
| Graph | Postgres co-occurrence graph | Neo4j causal/dependency graph |
| Anomaly detection | LLM-based diagnosis | MTAD-GAT (`torch`) |
| Causal discovery | — | PCMCI (`tigramite`) |
| Triage | Tabular Q-learning | DQN (`torch`), same contract |

`GET /api/trackc/status` and `GET /v1/heavy/status` report `core` vs `extended` per capability.
See [../LAPTOP_SETUP.md](../LAPTOP_SETUP.md) for activation steps.

---

## 7. Analytics & ML placement

Decisions about *where* logic runs follow one rule: **keep data in the gateway; put only
stateless computation in the ML service.**

- **Analytics suite** (health score, benchmark, predictions, clustering) is computed in the
  **gateway** — deterministic aggregations and lightweight feature math over org-scoped data.
  No LLM, no data egress.
- **RL triage**: the Q-learning policy lives in the **ML service** (stateless math + a small
  persisted table), while the **gateway** builds the org-scoped feature view and enforces
  access. The gateway derives a component label from incident text (there is no component
  column) using a shared keyword map.
- **Embeddings**: a deterministic feature-hash embedding (`/v1/embed`) gives 384-dim unit
  vectors with no heavy model — swappable for `sentence-transformers` on a capable host
  behind the same contract.

---

## 8. Deployment topology

- Everything ships as Docker images; `docker compose` brings up the five core services.
- Container memory limits are set in the Compose file for constrained hosts.
- The frontend builds a standalone Next server; the gateway is a Spring Boot fat app on
  virtual threads (blocking ML calls are cheap); the ML service is Uvicorn/FastAPI.
- Optional overlays (`docker-compose.kafka|metrics|graph.yml`) layer on top of the core.

See [RUNBOOK.md](RUNBOOK.md) for the operational loop and [../LAPTOP_SETUP.md](../LAPTOP_SETUP.md)
for the extended stack.
