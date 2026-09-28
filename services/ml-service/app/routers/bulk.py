"""Bulk log analysis + PDF report generation."""
from __future__ import annotations

import io
import json
import logging

from fastapi import APIRouter
from fastapi.responses import Response
from pydantic import BaseModel, Field

from ..agents.prompts import DETECT_FALLBACK, DETECT_SYSTEM, parse_json
from ..providers import GenerationRequest, registry

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/v1", tags=["bulk"])


class LogBatchRequest(BaseModel):
    lines: list[str] = Field(default_factory=list)
    provider: str | None = None
    model: str | None = None


def _analyze_chunk(lines: list[str], req) -> dict:
    provider = registry.resolve(getattr(req, "provider", None))
    model = getattr(req, "model", None) or registry.default_model
    prompt = "Analyze this log batch and summarize anomalies:\n" + "\n".join(lines[:200])
    gen = GenerationRequest(prompt=prompt, model=model, system=DETECT_SYSTEM, json_mode=True, temperature=0.2)
    try:
        return parse_json(provider.generate(gen), DETECT_FALLBACK)
    except Exception as exc:
        logger.warning("bulk analyze failed: %s", exc)
        return DETECT_FALLBACK


@router.post("/analyze/log-batch")
def analyze_log_batch(req: LogBatchRequest) -> dict:
    total = len(req.lines)
    # Rule-based counts + one LLM summary over a sample (keeps it fast + cheap).
    errors = sum(1 for l in req.lines if "error" in l.lower())
    warnings = sum(1 for l in req.lines if "warn" in l.lower())
    summary = _analyze_chunk(req.lines, req)
    return {
        "total_lines": total,
        "error_count": errors,
        "warning_count": warnings,
        "summary": summary,
        "anomalies": [summary] if summary.get("anomaly_detected") else [],
    }


class PdfRequest(BaseModel):
    title: str = "AegisAI Incident Report"
    analysis: dict = Field(default_factory=dict)


@router.post("/report/pdf")
def report_pdf(req: PdfRequest) -> Response:
    """Generate a simple PDF report. Uses reportlab if available, else a text fallback."""
    try:
        from reportlab.lib.pagesizes import letter
        from reportlab.pdfgen import canvas

        buf = io.BytesIO()
        c = canvas.Canvas(buf, pagesize=letter)
        y = 760
        c.setFont("Helvetica-Bold", 16)
        c.drawString(60, y, req.title)
        c.setFont("Helvetica", 10)
        y -= 30
        for line in json.dumps(req.analysis, indent=2).splitlines()[:60]:
            c.drawString(60, y, line[:100])
            y -= 14
            if y < 60:
                c.showPage()
                y = 760
        c.showPage()
        c.save()
        pdf = buf.getvalue()
        return Response(content=pdf, media_type="application/pdf",
                        headers={"Content-Disposition": "attachment; filename=report.pdf"})
    except ImportError:
        text = (req.title + "\n\n" + json.dumps(req.analysis, indent=2)).encode()
        return Response(content=text, media_type="text/plain",
                        headers={"Content-Disposition": "attachment; filename=report.txt"})
