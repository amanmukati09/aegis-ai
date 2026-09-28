"""Groq provider (default, free). OpenAI-compatible chat completions API."""
from __future__ import annotations

import json
from typing import Iterator

import httpx

from .base import AiProvider, GenerationRequest, ModelInfo, ProviderUnavailableError

_GROQ_BASE = "https://api.groq.com/openai/v1"
# Fallback list used only if the live /models call fails. Verify current IDs with
# infra/scripts/list-groq-models.sh (Groq rotates model names over time).
_DEFAULT_MODELS = [
    "openai/gpt-oss-20b",
    "openai/gpt-oss-120b",
]


class GroqProvider(AiProvider):
    name = "groq"

    def __init__(self, api_key: str | None):
        self._api_key = api_key

    def is_available(self) -> bool:
        return bool(self._api_key)

    def _headers(self) -> dict[str, str]:
        if not self._api_key:
            raise ProviderUnavailableError("GROQ_API_KEY not configured")
        return {"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"}

    def _messages(self, req: GenerationRequest) -> list[dict]:
        messages: list[dict] = []
        if req.system:
            messages.append({"role": "system", "content": req.system})
        messages.append({"role": "user", "content": req.prompt})
        return messages

    def generate(self, req: GenerationRequest) -> str:
        payload: dict = {
            "model": req.model,
            "messages": self._messages(req),
            "temperature": req.temperature,
        }
        if req.max_tokens:
            payload["max_tokens"] = req.max_tokens
        if req.json_mode:
            payload["response_format"] = {"type": "json_object"}

        with httpx.Client(timeout=60) as client:
            resp = client.post(f"{_GROQ_BASE}/chat/completions", headers=self._headers(), json=payload)
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"]

    def stream(self, req: GenerationRequest) -> Iterator[str]:
        payload: dict = {
            "model": req.model,
            "messages": self._messages(req),
            "temperature": req.temperature,
            "stream": True,
        }
        with httpx.Client(timeout=None) as client:
            with client.stream(
                "POST", f"{_GROQ_BASE}/chat/completions", headers=self._headers(), json=payload
            ) as resp:
                resp.raise_for_status()
                for line in resp.iter_lines():
                    if not line or not line.startswith("data: "):
                        continue
                    chunk = line[len("data: "):]
                    if chunk.strip() == "[DONE]":
                        break
                    try:
                        delta = json.loads(chunk)["choices"][0]["delta"].get("content")
                        if delta:
                            yield delta
                    except (json.JSONDecodeError, KeyError, IndexError):
                        continue

    def list_models(self) -> list[ModelInfo]:
        if not self.is_available():
            return []
        # Prefer the live list so the UI reflects current IDs; fall back to statics.
        try:
            with httpx.Client(timeout=10) as client:
                resp = client.get(f"{_GROQ_BASE}/models", headers=self._headers())
                resp.raise_for_status()
                data = resp.json().get("data", [])
                ids = [m["id"] for m in data if m.get("id")]
                if ids:
                    return [ModelInfo(id=i, provider=self.name) for i in ids]
        except httpx.HTTPError:
            pass
        return [ModelInfo(id=m, provider=self.name) for m in _DEFAULT_MODELS]
