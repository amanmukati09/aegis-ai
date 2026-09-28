from fastapi import APIRouter

from .. import __version__

router = APIRouter(tags=["health"])


@router.get("/healthz")
def healthz() -> dict:
    return {"service": "aegisai-ml", "status": "ok", "version": __version__}
