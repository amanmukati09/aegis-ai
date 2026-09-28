#!/usr/bin/env python3
"""Generate a large, realistic mixed log file for the bulk-analysis feature.

Usage: python3 gen-bulk-log.py [line_count] [out_path]
Default: 10000 lines -> bulk-10k.log (in this directory).
"""
from __future__ import annotations

import random
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

SERVICES = ["api-gateway", "order-service", "payment-service", "auth-service",
            "recommendation-svc", "nginx", "postgres", "redis", "kubelet"]

# (level, template) — weighted so the file is mostly healthy with realistic bursts.
NORMAL = [
    ("INFO", "{svc} GET /v1/{path} status=200 latency={lat}ms"),
    ("INFO", "{svc} POST /v1/{path} status=201 latency={lat}ms"),
    ("INFO", "{svc} healthcheck ok, uptime={up}s"),
    ("INFO", "{svc} cache hit ratio {pct}%"),
    ("DEBUG", "{svc} processed batch items={n}"),
]
WARN = [
    ("WARN", "{svc} db pool utilization {pct}% ({a}/{b} connections)"),
    ("WARN", "{svc} GC pause {lat}ms, throughput {pct}%"),
    ("WARN", "{svc} redis latency {lat}ms, cache miss rate {pct}%"),
    ("WARN", "{svc} disk / usage {pct}%"),
    ("WARN", "{svc} upstream {up2} slow, retrying"),
]
ERROR = [
    ("ERROR", "{svc} could not obtain connection from pool within 5000ms"),
    ("ERROR", "{svc} org.postgresql.util.PSQLException: connection refused"),
    ("ERROR", "{svc} java.lang.OutOfMemoryError: Java heap space"),
    ("ERROR", "{svc} 502 Bad Gateway upstream={up2} latency=5001ms"),
    ("ERROR", "{svc} No space left on device writing pg_wal"),
    ("ERROR", "{svc} i/o timeout connecting to {up2}"),
]
PATHS = ["orders", "profile", "login", "checkout", "recommendations", "search", "cart"]


def main() -> None:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 10000
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(__file__).with_name("bulk-10k.log")

    rng = random.Random(42)  # deterministic output
    t = datetime(2026, 6, 18, 0, 0, 0, tzinfo=timezone.utc)
    lines: list[str] = []

    for i in range(n):
        t += timedelta(seconds=rng.randint(1, 4))
        # ~4% error, ~14% warn, rest normal; add correlated error bursts.
        roll = rng.random()
        bucket = ERROR if roll < 0.04 else WARN if roll < 0.18 else NORMAL
        level, tmpl = rng.choice(bucket)
        svc = rng.choice(SERVICES)
        line = tmpl.format(
            svc=svc, path=rng.choice(PATHS), lat=rng.randint(5, 900),
            up=rng.randint(1000, 900000), pct=rng.randint(40, 100),
            a=rng.randint(1, 50), b=50, n=rng.randint(100, 50000),
            up2=rng.choice(SERVICES),
        )
        lines.append(f"{t.strftime('%Y-%m-%dT%H:%M:%SZ')} {level:5s} {line}")

    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {n} lines -> {out}")


if __name__ == "__main__":
    main()
