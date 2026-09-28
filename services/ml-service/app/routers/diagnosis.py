"""Diagnosis pipeline endpoints: detect anomaly, diagnose root cause, suggest remediation.

Each calls the selected AI provider in JSON mode and parses the result with a safe
deterministic fallback so the pipeline never hard-fails.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter

from ..agents import prompts
from ..providers import GenerationRequest, registry
from ..schemas import (
    DetectRequest,
    DetectResponse,
    DiagnoseRequest,
    DiagnoseResponse,
    RemediationRequest,
    RemediationResponse,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/v1", tags=["diagnosis"])


def _generate_json(system: str, prompt: str, req) -> str:
    provider = registry.resolve(getattr(req, "provider", None))
    model = getattr(req, "model", None) or registry.default_model
    gen = GenerationRequest(prompt=prompt, model=model, system=system, json_mode=True, temperature=0.2)
    try:
        return provider.generate(gen)
    except Exception as exc:
        logger.warning("provider.generate failed: %s", exc)
        return ""


def _build(model_cls, fallback: dict, data: dict):
    """Merge LLM data over the fallback, keeping only fields the schema knows."""
    merged = {**fallback, **data}
    allowed = model_cls.model_fields.keys()
    return model_cls(**{k: v for k, v in merged.items() if k in allowed})


@router.post("/detect-anomaly", response_model=DetectResponse)
def detect_anomaly(req: DetectRequest) -> DetectResponse:
    raw = _generate_json(prompts.DETECT_SYSTEM, prompts.detect_prompt(req.logs), req)
    data = prompts.parse_json(raw, prompts.DETECT_FALLBACK)
    return _build(DetectResponse, prompts.DETECT_FALLBACK, data)


@router.post("/diagnose", response_model=DiagnoseResponse)
def diagnose(req: DiagnoseRequest) -> DiagnoseResponse:
    raw = _generate_json(prompts.DIAGNOSE_SYSTEM, prompts.diagnose_prompt(req.anomaly, req.logs), req)
    data = prompts.parse_json(raw, prompts.DIAGNOSE_FALLBACK)
    return _build(DiagnoseResponse, prompts.DIAGNOSE_FALLBACK, data)


@router.post("/remediation/suggest", response_model=RemediationResponse)
def suggest_remediation(req: RemediationRequest) -> RemediationResponse:
    raw = _generate_json(prompts.REMEDIATION_SYSTEM, prompts.remediation_prompt(req.anomaly, req.root_cause), req)
    data = prompts.parse_json(raw, prompts.REMEDIATION_FALLBACK)
    return _build(RemediationResponse, prompts.REMEDIATION_FALLBACK, data)
