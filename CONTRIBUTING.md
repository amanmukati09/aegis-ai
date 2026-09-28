# Contributing to AegisAI

Thanks for your interest in improving AegisAI. This guide covers how to get set up, the
conventions the codebase follows, and how to propose changes.

## Getting started

You only need **Docker + Docker Compose** to run the whole stack — no local JDK / Node /
Python required.

```bash
git clone https://github.com/amanmukati09/cloud-hackathon-tcs-amd.git aegisai
cd aegisai
cp .env.example .env          # fill in JWT_SECRET, POSTGRES_PASSWORD, GROQ_API_KEY
cd infra/docker
docker compose --env-file ../../.env up -d --build
```

See the [README](README.md) for service URLs and [LAPTOP_SETUP.md](LAPTOP_SETUP.md) for the
optional Track C stack.

## Project structure

| Path | What lives here |
|---|---|
| `services/gateway/` | Spring Boot gateway — auth, RBAC, all transactional data, orchestration |
| `services/ml-service/` | FastAPI ML sidecar — stateless inference / generation only |
| `frontend/` | Next.js 14 app |
| `infra/docker/` | Compose files (core + kafka/metrics/graph overlays) |
| `infra/scripts/` | Verification + smoke scripts |

## Conventions

**General**
- The gateway owns data and every security decision. The ML service is stateless and must
  never receive or store another org's data.
- Everything heavy is an optional adapter enabled by config — never a hard dependency of the
  core. Keep the core image lean.

**Gateway (Java 21 / Spring Boot)**
- Package by feature (`incident`, `chat`, `triage`, `insights`, …), not by layer.
- Every read/write is org-scoped; `super_admin` (no org) is the only cross-org path.
- New pluggable integrations follow the port + adapter pattern (see `alert/AlertChannel` and
  `trackc/`), discovered as Spring beans.
- Database changes are **additive** Flyway migrations — never edit an applied migration.

**ML service (Python / FastAPI)**
- Add LLM providers by implementing the `AiProvider` port and registering them; nothing else
  references a concrete provider.
- Heavy models are install-to-activate stubs under `app/heavy/` — they self-describe via
  `available()` and must not hard-fail when their dependency is absent.

**Frontend (Next.js / TypeScript)**
- Browser calls go through `/api/gateway/*` (proxied by Next) — no direct CORS to the gateway.
- Use the shared design tokens (`surface`, `surface-2`, `line`, `accent`) and UI primitives;
  don't hardcode colors.

## Making changes

1. Create a branch: `git checkout -b feature/short-description`.
2. Make focused commits with clear messages.
3. Run the relevant verification script in `infra/scripts/` (or `smoke-full.sh` for an
   end-to-end pass) against a running stack.
4. Open a pull request describing **what** changed, **why**, and **how you tested it**.

## Security

Never commit secrets. All configuration goes through the git-ignored `.env`. If you find a
vulnerability, please follow [SECURITY.md](SECURITY.md) rather than opening a public issue.
