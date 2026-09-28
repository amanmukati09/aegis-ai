# Security Policy

## Reporting a vulnerability

If you discover a security issue, please **do not open a public GitHub issue**. Instead,
report it privately via [GitHub Security Advisories](https://github.com/amanmukati09/cloud-hackathon-tcs-amd/security/advisories/new)
so it can be triaged before disclosure.

Please include:
- a description of the issue and its impact,
- steps to reproduce (or a proof of concept),
- affected component (gateway / ML service / frontend) and version/commit.

## Handling secrets

- No secrets are committed to the repository. All configuration is provided through a
  git-ignored `.env` file (`.env.example` documents the keys).
- Rotate `JWT_SECRET`, database/Redis passwords, and any provider API keys if you suspect
  exposure. Never paste real keys into issues, PRs, or logs.

## Built-in protections

AegisAI applies defense-in-depth by default:

- **Auth**: JWT + BCrypt, OAuth2 (Google/GitHub/Microsoft), per-user API keys.
- **RBAC & tenancy**: three roles with strict per-organization data isolation.
- **AI guardrails**: prompt-injection blocking, PII masking, destructive-output filtering.
- **NL→SQL**: SELECT-only, single-statement, and org-scoped via query rewriting — a user
  cannot read another org's data even if the model produced an unscoped query.
- **URL ingestion**: SSRF-guarded — blocks loopback, private, link-local, and cloud-metadata
  address ranges.
- **Transport**: locked CORS and Redis-backed rate limiting.

## Scope

This project is provided under the MIT License with no warranty. Deploying it to production
is your responsibility; review the configuration, rotate all default credentials, and place
it behind TLS and a trusted network boundary.
