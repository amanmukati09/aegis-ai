## Summary

<!-- What does this PR change, and why? -->

## Changes

<!-- Bullet the key changes. Note which service(s) are touched: gateway / ml-service / frontend / infra -->

-

## How was this tested?

<!-- Commands run, verification scripts, manual checks. A green smoke-full.sh is ideal for cross-cutting changes. -->

- [ ] Ran the stack locally (`docker compose up -d --build`)
- [ ] Ran the relevant `infra/scripts/verify-*.sh` / `smoke-full.sh`
- [ ] Added/updated migrations are additive (no edits to applied migrations)

## Checklist

- [ ] No secrets committed; config stays in `.env`
- [ ] Org-scoping preserved for any new data access
- [ ] New integrations follow the port + adapter pattern
- [ ] Docs updated if behavior or setup changed
