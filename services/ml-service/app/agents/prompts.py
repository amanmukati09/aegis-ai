"""Prompt builders and safe fallbacks for the diagnosis pipeline.

Each agent asks the LLM for strict JSON and parses it; on any failure we return a
deterministic fallback dict so the pipeline never hard-fails (ported behavior from
the original AegisAI agents).
"""
from __future__ import annotations

import json
import logging

logger = logging.getLogger(__name__)


def parse_json(raw: str, fallback: dict) -> dict:
    """Parse an LLM JSON response, tolerating markdown fences; fallback on error."""
    if not raw:
        return fallback
    text = raw.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
    try:
        result = json.loads(text)
        return result if isinstance(result, dict) else fallback
    except (json.JSONDecodeError, TypeError):
        logger.warning("LLM did not return valid JSON; using fallback")
        return fallback


# ---- Anomaly detection ----

DETECT_SYSTEM = (
    "You are an SRE anomaly detection engine. Given raw logs, decide whether an "
    "anomaly is present. Return ONLY valid JSON with keys: "
    '{"anomaly_detected": bool, "anomaly_type": string, "severity": '
    '"low|medium|high|critical", "affected_component": string, "description": string}.'
)

DETECT_FALLBACK = {
    "anomaly_detected": False,
    "anomaly_type": "unknown",
    "severity": "medium",
    "affected_component": "unknown",
    "description": "Automated detection unavailable; manual review recommended.",
}


def detect_prompt(logs: list[str]) -> str:
    joined = "\n".join(logs[-200:])  # cap context
    return f"Analyze these logs and detect anomalies:\n\n{joined}"


# ---- Root cause diagnosis ----

DIAGNOSE_SYSTEM = (
    "You are a senior SRE performing root cause analysis. Given an anomaly and logs, "
    "identify the most likely root cause. Return ONLY valid JSON with keys: "
    '{"root_cause": string, "confidence": number (0-1), "evidence": [string], '
    '"contributing_factors": [string]}.'
)

DIAGNOSE_FALLBACK = {
    "root_cause": "Root cause could not be determined automatically.",
    "confidence": 0.0,
    "evidence": [],
    "contributing_factors": [],
}


def diagnose_prompt(anomaly: dict, logs: list[str]) -> str:
    joined = "\n".join(logs[-200:])
    return (
        f"Anomaly:\n{json.dumps(anomaly)}\n\nLogs:\n{joined}\n\n"
        "Determine the root cause."
    )


# ---- Remediation ----

REMEDIATION_SYSTEM = (
    "You are an expert SRE remediation agent. Based on the anomaly and root cause, "
    "produce an incident response plan with SAFE diagnostic commands only. Return ONLY "
    'valid JSON with keys: {"immediate_actions": [string], "diagnostic_commands": '
    '[safe shell commands], "escalation_needed": bool, "estimated_recovery_time": '
    'string, "prevention_measures": [string]}.'
)

REMEDIATION_FALLBACK = {
    "immediate_actions": ["Manual intervention required."],
    "diagnostic_commands": ["systemctl status", "df -h", "free -m"],
    "escalation_needed": True,
    "estimated_recovery_time": "Unknown",
    "prevention_measures": ["Investigate the AI pipeline failure."],
}


def remediation_prompt(anomaly: dict, root_cause: dict) -> str:
    return (
        f"Anomaly:\n{json.dumps(anomaly)}\n\nRoot cause:\n{json.dumps(root_cause)}\n\n"
        "Produce the remediation plan."
    )
