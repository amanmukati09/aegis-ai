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


# ---- Runbook ----
RUNBOOK_SYSTEM = (
    "You are an SRE writing an operational runbook to resolve an incident. Return ONLY "
    "JSON: {\"title\": string, \"severity\": string, "
    "\"steps\": [{\"phase\": \"detect|diagnose|mitigate|resolve|verify\", \"action\": string, "
    "\"command\": string}], \"rollback_steps\": [string], \"validation_checks\": [string], "
    "\"estimated_time\": string}. Commands must be safe, read-only diagnostics where possible."
)
RUNBOOK_FALLBACK = {
    "title": "Incident Runbook",
    "severity": "unknown",
    "steps": [
        {"phase": "detect", "action": "Confirm the alert and scope", "command": "systemctl status"},
        {"phase": "diagnose", "action": "Inspect logs and resource usage", "command": "df -h && free -m"},
    ],
    "rollback_steps": ["Revert the most recent change"],
    "validation_checks": ["Service responds to health check"],
    "estimated_time": "Unknown",
}


def runbook_prompt(incident: dict) -> str:
    return f"Incident:\n{json.dumps(incident)}\n\nWrite the runbook."


# ---- Knowledge base article extraction ----
KB_SYSTEM = (
    "You are a technical writer turning a resolved incident into a reusable knowledge-base "
    "article for other engineers. Return ONLY JSON: "
    '{"title": string, "category": string, "tags": [string], "symptoms": string, '
    '"root_cause": string, "solution": string, "prevention": string, '
    '"difficulty": "Beginner|Intermediate|Advanced"}. Be concise and actionable; '
    "write for someone seeing this class of incident for the first time."
)
KB_FALLBACK = {
    "title": "Incident review",
    "category": "General",
    "tags": [],
    "symptoms": "See incident details.",
    "root_cause": "Not automatically determined.",
    "solution": "See resolution notes.",
    "prevention": "Review after resolution.",
    "difficulty": "Intermediate",
}


def kb_prompt(incident: dict) -> str:
    return f"Resolved incident:\n{json.dumps(incident)}\n\nWrite the knowledge-base article."
