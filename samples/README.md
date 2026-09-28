# Sample data & demo seeding

Ready-to-use sample logs and a one-command seed script to populate AegisAI with realistic
incidents for a demo.

## Sample log files (`samples/logs/`)

Drag-and-drop or upload these on the **Bulk Analysis** page, or paste their contents into an
incident. Each tells a coherent, realistic story:

| File | Scenario |
|---|---|
| `database-outage.log` | Connection-pool exhaustion → 503s → circuit breaker → recovery |
| `oom-kill.log` | Heap pressure → OOM kill → CrashLoopBackOff → chunked retry |
| `nginx-5xx-spike.log` | Upstream auth timeouts → 502 spike → autoscale → recovery |
| `disk-pressure.log` | Disk fills → `pg_wal` write fails → logrotate/vacuum → recovery |
| `bulk-10k.log` | ~10,000 mixed lines for the async **bulk analysis** feature |

Regenerate the big one with a different size if you like:

```bash
python3 logs/gen-bulk-log.py 20000 logs/bulk-20k.log
```

## Seed a demo org (`seed-demo.sh`)

Creates a fresh demo organization, loads ~14 incidents across components and severities,
resolves a subset (so MTTR / benchmark / RL training have signal), trains the RL triage
agent, and prints login credentials + a snapshot.

```bash
# with the stack running (core: http://localhost:8080)
BASE=http://localhost:8080 bash samples/seed-demo.sh
```

It runs entirely through the public API, so all data is correctly org-scoped. Run it again
any time — each run creates a new isolated org, so seeds never collide.

After it finishes, open the UI, log in with the printed credentials, and explore:
**Overview · Incidents · RL Triage · Insights · Copilot · Dependency Map**.

> Note: root-cause / remediation fields (which drive the benchmark's "diagnosis accuracy")
> populate when you run **AI Diagnosis** on an incident — the seed leaves those to the AI
> pipeline so you can see it work live.
