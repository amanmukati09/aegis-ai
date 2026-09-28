"""Prompts + fallbacks for advanced ML endpoints (RCA tree, code-fix, NL->SQL)."""
from __future__ import annotations

import json

# ---- RCA tree ----
RCA_SYSTEM = (
    "You are an SRE performing hierarchical root cause analysis. Given an anomaly and "
    "logs, produce a cause tree. Return ONLY JSON: "
    '{"incident_summary": string, "severity_assessment": string, '
    '"tree": {"label": string, "children": [{"label": string, "children": []}]}, '
    '"remediation_path": [string], "affected_systems": [string]}.'
)
RCA_FALLBACK = {
    "incident_summary": "Analysis unavailable.",
    "severity_assessment": "unknown",
    "tree": {"label": "Root cause undetermined", "children": []},
    "remediation_path": [],
    "affected_systems": [],
}


def rca_prompt(anomaly: dict, root_cause: dict, logs: list[str]) -> str:
    return (f"Anomaly:\n{json.dumps(anomaly)}\n\nRoot cause:\n{json.dumps(root_cause)}\n\n"
            f"Logs:\n{chr(10).join(logs[-150:])}\n\nBuild the cause tree.")


# ---- Code fix ----
CODEFIX_SYSTEM = (
    "You are a senior engineer generating a code fix for an incident. Return ONLY JSON: "
    '{"summary": string, "language": string, "fixes": [{"title": string, "code": string, '
    '"risk": "low|medium|high", "rollback": string}], "verification_steps": [string], '
    '"estimated_implementation_time": string}.'
)
CODEFIX_FALLBACK = {
    "summary": "No automated fix could be generated.",
    "language": "unknown",
    "fixes": [],
    "verification_steps": [],
    "estimated_implementation_time": "unknown",
}


def codefix_prompt(anomaly: dict, root_cause: dict, logs: list[str], language: str) -> str:
    return (f"Language: {language}\nAnomaly:\n{json.dumps(anomaly)}\n\n"
            f"Root cause:\n{json.dumps(root_cause)}\n\nLogs:\n{chr(10).join(logs[-150:])}\n\n"
            "Generate the fix.")


# ---- NL -> SQL ----
NL2SQL_SYSTEM = (
    "You translate natural-language analytics questions into a single PostgreSQL SELECT "
    "query over this schema (incidents table has columns: id, org_id, status, severity, "
    "anomaly_description, root_cause, detected_at, resolved_at). Return ONLY JSON: "
    '{"sql": "SELECT ...", "explanation": string, "chart_type": "bar|line|table"}. '
    "The SQL MUST be a single SELECT statement. Do not include semicolons or comments. "
    "Do NOT add an org filter (the caller adds it)."
)
NL2SQL_FALLBACK = {
    "sql": "SELECT severity, count(*) AS total FROM incidents GROUP BY severity",
    "explanation": "Default: incident counts by severity.",
    "chart_type": "bar",
}


def nl2sql_prompt(question: str) -> str:
    return f"Question: {question}\n\nReturn the JSON."
