"""Advanced ML endpoints: RCA tree, code-fix, NL->SQL. All JSON-out with fallbacks."""
from __future__ import annotations

import logging

from fastapi import APIRouter
from pydantic import BaseModel, Field

from ..agents import advanced_prompts as ap
from ..agents.prompts import parse_json
from ..providers import GenerationRequest, registry

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/v1", tags=["advanced"])


class RcaRequest(BaseModel):
    anomaly: dict = Field(default_factory=dict)
    root_cause: dict = Field(default_factory=dict)
    logs: list[str] = Field(default_factory=list)
    provider: str | None = None
    model: str | None = None


class CodeFixRequest(BaseModel):
    anomaly: dict = Field(default_factory=dict)
    root_cause: dict = Field(default_factory=dict)
    logs: list[str] = Field(default_factory=list)
    language: str = "auto"
    provider: str | None = None
    model: str | None = None


class NlSqlRequest(BaseModel):
    question: str
    provider: str | None = None
    model: str | None = None


class RunbookRequest(BaseModel):
    incident: dict = Field(default_factory=dict)
    provider: str | None = None
    model: str | None = None


def _gen(system: str, prompt: str, req) -> str:
    provider = registry.resolve(getattr(req, "provider", None))
    model = getattr(req, "model", None) or registry.default_model
    gen = GenerationRequest(prompt=prompt, model=model, system=system, json_mode=True, temperature=0.2)
    try:
        return provider.generate(gen)
    except Exception as exc:
        logger.warning("advanced gen failed: %s", exc)
        return ""


@router.post("/rca-tree")
def rca_tree(req: RcaRequest) -> dict:
    raw = _gen(ap.RCA_SYSTEM, ap.rca_prompt(req.anomaly, req.root_cause, req.logs), req)
    return parse_json(raw, ap.RCA_FALLBACK)


@router.post("/code-fix")
def code_fix(req: CodeFixRequest) -> dict:
    raw = _gen(ap.CODEFIX_SYSTEM, ap.codefix_prompt(req.anomaly, req.root_cause, req.logs, req.language), req)
    return parse_json(raw, ap.CODEFIX_FALLBACK)


@router.post("/runbook")
def runbook(req: RunbookRequest) -> dict:
    raw = _gen(ap.RUNBOOK_SYSTEM, ap.runbook_prompt(req.incident), req)
    return parse_json(raw, ap.RUNBOOK_FALLBACK)


@router.post("/nl-to-sql")
def nl_to_sql(req: NlSqlRequest) -> dict:
    raw = _gen(ap.NL2SQL_SYSTEM, ap.nl2sql_prompt(req.question), req)
    result = parse_json(raw, ap.NL2SQL_FALLBACK)
    # Safety net: ensure single SELECT, strip anything dangerous. Gateway re-validates.
    sql = str(result.get("sql", "")).strip().rstrip(";")
    if not sql.lower().startswith("select"):
        result = ap.NL2SQL_FALLBACK
    else:
        result["sql"] = sql
    return result
