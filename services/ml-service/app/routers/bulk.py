"""Bulk log analysis + PDF report generation."""
from __future__ import annotations

import io
import json
import logging
from datetime import datetime

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
    """Generate a structured PDF incident report (title, summary, stats table, anomalies)."""
    try:
        from reportlab.lib import colors
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
        from reportlab.lib.units import mm
        from reportlab.platypus import (
            SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
        )

        a = req.analysis or {}
        summary = a.get("summary", {}) if isinstance(a.get("summary"), dict) else {}
        buf = io.BytesIO()
        doc = SimpleDocTemplate(buf, pagesize=A4, topMargin=20 * mm, bottomMargin=18 * mm)
        styles = getSampleStyleSheet()
        title_style = ParagraphStyle("t", parent=styles["Title"], textColor=colors.HexColor("#5856d6"))
        h = ParagraphStyle("h", parent=styles["Heading2"], textColor=colors.HexColor("#1d1d1f"))
        body = styles["BodyText"]

        story = [Paragraph(req.title, title_style), Spacer(1, 6)]
        story.append(Paragraph(
            f"Generated {datetime.utcnow().strftime('%Y-%m-%d %H:%M UTC')}", body))
        story.append(Spacer(1, 12))

        # Summary
        story.append(Paragraph("Summary", h))
        story.append(Paragraph(
            summary.get("description", "No summary available."), body))
        story.append(Spacer(1, 12))

        # Stats table
        stats = [
            ["Metric", "Value"],
            ["Total lines", str(a.get("total_lines", "—"))],
            ["Errors", str(a.get("error_count", "—"))],
            ["Warnings", str(a.get("warning_count", "—"))],
            ["Severity", str(summary.get("severity", "—"))],
            ["Affected component", str(summary.get("affected_component", "—"))],
        ]
        table = Table(stats, colWidths=[70 * mm, 90 * mm])
        table.setStyle(TableStyle([
            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#5856d6")),
            ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
            ("FONTSIZE", (0, 0), (-1, -1), 10),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f5f5f7")]),
            ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#e0e0e0")),
            ("PADDING", (0, 0), (-1, -1), 8),
        ]))
        story.append(table)
        story.append(Spacer(1, 14))

        # Anomalies
        anomalies = a.get("anomalies", [])
        if anomalies:
            story.append(Paragraph("Detected anomalies", h))
            for an in anomalies[:20]:
                if isinstance(an, dict):
                    story.append(Paragraph(
                        f"<b>{an.get('anomaly_type', 'anomaly')}</b> "
                        f"({an.get('severity', 'unknown')}) — {an.get('description', '')}", body))
                    story.append(Spacer(1, 4))

        doc.build(story)
        return Response(content=buf.getvalue(), media_type="application/pdf",
                        headers={"Content-Disposition": "attachment; filename=incident-report.pdf"})
    except ImportError:
        text = (req.title + "\n\n" + json.dumps(req.analysis, indent=2)).encode()
        return Response(content=text, media_type="text/plain",
                        headers={"Content-Disposition": "attachment; filename=report.txt"})
