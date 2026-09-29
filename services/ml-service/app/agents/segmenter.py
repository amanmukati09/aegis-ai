"""Segment a raw log stream into distinct incidents.

The previous AegisAI chunked logs into fixed line blocks and let an LLM (Ollama) emit
incidents per block, capping persistence at 3. This is a deterministic, dependency-light
replacement that groups related log lines into *distinct* incidents by
(component, severity-class, error-signature) and works fully offline (no LLM required).

Each detected incident carries: title, severity, component, description, a representative
set of evidence lines, first/last line indexes, and an occurrence count — enough for the
gateway to create real incident records and for a combined PDF report.
"""
from __future__ import annotations

import re

# Ordered component keyword -> label; first hit wins. Mirrors the gateway/RL component map.
_COMPONENTS: list[tuple[str, str]] = [
    ("postgres", "Database"), ("psql", "Database"), ("database", "Database"),
    ("sql", "Database"), ("hikari", "Database"), ("pg_wal", "Database"),
    ("redis", "Redis"), ("cache", "Redis"),
    ("nginx", "Nginx"), ("upstream", "Nginx"), ("proxy", "Nginx"),
    ("gateway", "API Gateway"), ("api-gateway", "API Gateway"), ("api ", "API Gateway"),
    ("auth", "Auth"), ("login", "Auth"), ("token", "Auth"),
    ("payment", "Payment"), ("checkout", "Payment"), ("billing", "Payment"),
    ("kubelet", "Orchestration"), ("pod ", "Orchestration"), ("oom", "Memory"),
    ("out of memory", "Memory"), ("heap", "Memory"), ("memory", "Memory"),
    ("disk", "Disk"), ("no space", "Disk"), ("cpu", "CPU"),
    ("dns", "Network"), ("network", "Network"), ("connection", "Network"),
    ("timeout", "Network"),
]

# severity signature -> (severity, incident-type label). Order = priority (first match wins).
_SIGNATURES: list[tuple[re.Pattern, str, str]] = [
    (re.compile(r"out of memory|oom|oomkill|heap space|memory cgroup", re.I), "critical", "Memory exhaustion"),
    (re.compile(r"no space left|disk (?:full|/ usage 100)|pg_wal", re.I), "critical", "Disk exhaustion"),
    (re.compile(r"panic|fatal|segfault|data loss|corrupt", re.I), "critical", "Fatal fault"),
    (re.compile(r"connection (?:refused|pool exhausted|not available)|too many clients", re.I), "critical", "Connection pool exhaustion"),
    (re.compile(r"\b5\d\d\b|bad gateway|no live upstreams|service unavailable", re.I), "high", "Upstream/gateway failure"),
    (re.compile(r"crash|crashloop|killed process|terminated", re.I), "high", "Service crash"),
    (re.compile(r"timeout|timed out|i/o timeout|deadline exceeded", re.I), "high", "Timeout"),
    (re.compile(r"evict|maxmemory|throttl|backpressure", re.I), "high", "Resource pressure"),
    (re.compile(r"exception|error|failed|failure|denied", re.I), "medium", "Errors"),
    (re.compile(r"warn|warning|degrad|slow|elevated|lag", re.I), "low", "Degradation"),
]

_SEV_RANK = {"low": 1, "medium": 2, "high": 3, "critical": 4}


def _component(line: str) -> str:
    low = line.lower()
    for kw, label in _COMPONENTS:
        if kw in low:
            return label
    return "Other"


def _classify(line: str) -> tuple[str, str] | None:
    """Return (severity, incident_type) for a noteworthy line, or None if it's benign."""
    for pattern, severity, itype in _SIGNATURES:
        if pattern.search(line):
            return severity, itype
    return None


def _title(component: str, itype: str) -> str:
    return f"{itype} on {component}" if component != "Other" else itype


def segment(lines: list[str], max_incidents: int = 25) -> dict:
    """Group log lines into distinct incidents.

    Grouping key = (component, incident_type). Lines that share a key are the same incident;
    its severity is the max severity seen, and evidence is a few representative lines.
    """
    total = len(lines)
    groups: dict[tuple[str, str], dict] = {}
    error_lines = 0

    for idx, raw in enumerate(lines):
        line = raw.strip()
        if not line:
            continue
        hit = _classify(line)
        if hit is None:
            continue
        severity, itype = hit
        if _SEV_RANK[severity] >= _SEV_RANK["medium"]:
            error_lines += 1
        component = _component(line)
        key = (component, itype)

        g = groups.get(key)
        if g is None:
            g = {
                "title": _title(component, itype),
                "component": component,
                "type": itype,
                "severity": severity,
                "count": 0,
                "first_line": idx + 1,
                "last_line": idx + 1,
                "evidence": [],
            }
            groups[key] = g
        g["count"] += 1
        g["last_line"] = idx + 1
        # keep the highest severity observed for the group
        if _SEV_RANK[severity] > _SEV_RANK[g["severity"]]:
            g["severity"] = severity
        if len(g["evidence"]) < 4:
            g["evidence"].append(line[:300])

    # Rank incidents: severity desc, then frequency desc.
    incidents = sorted(
        groups.values(),
        key=lambda g: (_SEV_RANK[g["severity"]], g["count"]),
        reverse=True,
    )[:max_incidents]

    for g in incidents:
        g["description"] = (
            f"{g['count']} related log entries indicate a {g['type'].lower()} affecting "
            f"{g['component']} (lines {g['first_line']}-{g['last_line']})."
        )

    severity_breakdown: dict[str, int] = {}
    for g in incidents:
        severity_breakdown[g["severity"]] = severity_breakdown.get(g["severity"], 0) + 1

    top_components: dict[str, int] = {}
    for g in incidents:
        top_components[g["component"]] = top_components.get(g["component"], 0) + g["count"]

    return {
        "total_lines": total,
        "error_lines": error_lines,
        "incident_count": len(incidents),
        "incidents": incidents,
        "severity_breakdown": severity_breakdown,
        "top_components": dict(sorted(top_components.items(), key=lambda kv: kv[1], reverse=True)[:6]),
    }
