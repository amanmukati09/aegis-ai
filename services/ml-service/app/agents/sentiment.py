"""Lightweight lexicon-based sentiment / urgency scorer for incident chat.

No model, no dependencies — a transparent keyword lexicon that flags how urgent or
frustrated a message reads. Useful for surfacing "this operator is stressed / this is
escalating" signals in the chat UI. Swappable later for a real classifier via the
same function signature.
"""
from __future__ import annotations

import re

_TOKEN_RE = re.compile(r"[a-z']+")

# weight > 0 => negative/urgent tone, weight < 0 => calm/positive.
_LEXICON: dict[str, float] = {
    # urgency / severity
    "urgent": 2.0, "critical": 2.0, "emergency": 2.5, "asap": 2.0, "immediately": 1.5,
    "down": 1.5, "outage": 2.0, "broken": 1.5, "failing": 1.5, "failed": 1.5,
    "crash": 1.5, "crashed": 1.5, "error": 1.0, "errors": 1.0, "timeout": 1.0,
    "blocked": 1.2, "stuck": 1.2, "escalate": 2.0, "escalating": 2.0, "severe": 1.5,
    # frustration
    "frustrated": 2.0, "angry": 2.0, "annoyed": 1.5, "terrible": 1.5, "awful": 1.5,
    "unacceptable": 2.0, "worst": 1.5, "hate": 1.5, "again": 0.8, "still": 0.8,
    # calm / positive
    "thanks": -1.5, "thank": -1.5, "great": -1.5, "resolved": -2.0, "fixed": -2.0,
    "working": -1.5, "good": -1.0, "perfect": -1.5, "appreciate": -1.5, "ok": -0.5,
    "stable": -1.5, "recovered": -2.0, "calm": -1.0,
}


def analyze(text: str) -> dict:
    tokens = _TOKEN_RE.findall((text or "").lower())
    if not tokens:
        return {"label": "neutral", "score": 0.0, "urgency": "normal", "signals": []}

    signals: list[str] = []
    raw = 0.0
    for tok in tokens:
        w = _LEXICON.get(tok)
        if w:
            raw += w
            signals.append(tok)

    # exclamation / all-caps amplify urgency
    exclamations = text.count("!")
    raw += min(exclamations, 3) * 0.5
    words = [w for w in text.split() if len(w) >= 3]
    caps = sum(1 for w in words if w.isupper())
    if caps >= 2:
        raw += 1.0
        signals.append("SHOUTING")

    # squash to [-1, 1]
    score = max(-1.0, min(1.0, raw / 6.0))

    if score >= 0.5:
        label, urgency = "negative", "high"
    elif score >= 0.15:
        label, urgency = "concerned", "elevated"
    elif score <= -0.3:
        label, urgency = "positive", "low"
    else:
        label, urgency = "neutral", "normal"

    return {
        "label": label,
        "score": round(score, 3),
        "urgency": urgency,
        "signals": signals[:8],
    }
