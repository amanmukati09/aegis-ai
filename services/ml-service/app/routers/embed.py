"""Deterministic text embedding (feature hashing) to a 384-dim unit vector.

No heavy model — a hashing embedding that captures token overlap, so similar incident
descriptions land near each other under cosine similarity. Swappable later for a real
sentence-transformer via the same endpoint (see LAPTOP_SETUP for enabling that).
"""
from __future__ import annotations

import hashlib
import math
import re

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(prefix="/v1", tags=["embed"])

DIM = 384
_token_re = re.compile(r"[a-z0-9]+")


class EmbedRequest(BaseModel):
    text: str


def _embed(text: str) -> list[float]:
    vec = [0.0] * DIM
    tokens = _token_re.findall((text or "").lower())
    for tok in tokens:
        h = int(hashlib.md5(tok.encode()).hexdigest(), 16)
        idx = h % DIM
        sign = 1.0 if (h >> 8) % 2 == 0 else -1.0
        vec[idx] += sign
    norm = math.sqrt(sum(v * v for v in vec))
    if norm > 0:
        vec = [v / norm for v in vec]
    return vec


@router.post("/embed")
def embed(req: EmbedRequest) -> dict:
    return {"embedding": _embed(req.text), "dim": DIM}
