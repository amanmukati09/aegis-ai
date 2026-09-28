"""AegisAI ML service — FastAPI application entry point."""
from __future__ import annotations

import logging

from fastapi import FastAPI

from . import __version__
from .routers import chat, diagnosis, health, models

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="AegisAI ML Service", version=__version__)

app.include_router(health.router)
app.include_router(models.router)
app.include_router(chat.router)
app.include_router(diagnosis.router)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=8001, reload=False)
