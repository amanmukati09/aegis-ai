"""Runtime configuration read from environment. Keys come from .env only."""
from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    default_provider: str = os.getenv("DEFAULT_AI_PROVIDER", "groq")
    default_model: str = os.getenv("DEFAULT_AI_MODEL", "openai/gpt-oss-20b")

    groq_api_key: str | None = os.getenv("GROQ_API_KEY") or None
    gemini_api_key: str | None = os.getenv("GEMINI_API_KEY") or None
    openrouter_api_key: str | None = os.getenv("OPENROUTER_API_KEY") or None
    openai_api_key: str | None = os.getenv("OPENAI_API_KEY") or None
    anthropic_api_key: str | None = os.getenv("ANTHROPIC_API_KEY") or None

    # Optional local Ollama; blank disables it.
    ollama_url: str | None = os.getenv("OLLAMA_URL") or None

    vector_store: str = os.getenv("VECTOR_STORE", "pgvector")


settings = Settings()
