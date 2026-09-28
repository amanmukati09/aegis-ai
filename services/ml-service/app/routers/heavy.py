"""Track C heavy-ML status endpoint (install-to-activate)."""
from __future__ import annotations

from fastapi import APIRouter

from ..heavy import models

router = APIRouter(prefix="/v1", tags=["heavy"])


@router.get("/heavy/status")
def heavy_status() -> dict:
    """Report which heavy-ML capabilities are installed/available on this host."""
    return models.status()
