"""Ollama provider (optional local). Disabled unless OLLAMA_URL is set.

Kept for parity with the original app and for users who run a local model.
Not part of the default core stack (too heavy for small hosts)."""
from __future__ import annotations

import json
from typing import Iterator

import httpx

from .base import AiProvider, GenerationRequest, ModelInfo, ProviderUnavailableError


class OllamaProvider(AiProvider):
    name = "ollama"

    def __init__(self, base_url: str | None):
        self._base_url = base_url.rstrip("/") if base_url else None

    def is_available(self) -> bool:
        return bool(self._base_url)

    def _url(self, path: str) -> str:
        if not self._base_url:
            raise ProviderUnavailableError("OLLAMA_URL not configured")
        return f"{self._base_url}{path}"

    def _payload(self, req: GenerationRequest, stream: bool) -> dict:
        payload: dict = {
            "model": req.model,
            "prompt": req.prompt if not req.system else f"{req.system}\n\n{req.prompt}",
            "stream": stream,
            "options": {"temperature": req.temperature},
        }
        if req.json_mode:
            payload["format"] = "json"
        if req.max_tokens:
            payload["options"]["num_predict"] = req.max_tokens
        if req.images:
            payload["images"] = req.images
        return payload

    def generate(self, req: GenerationRequest) -> str:
        with httpx.Client(timeout=120) as client:
            resp = client.post(self._url("/api/generate"), json=self._payload(req, stream=False))
            resp.raise_for_status()
            return resp.json().get("response", "")

    def stream(self, req: GenerationRequest) -> Iterator[str]:
        with httpx.Client(timeout=None) as client:
            with client.stream("POST", self._url("/api/generate"), json=self._payload(req, stream=True)) as resp:
                resp.raise_for_status()
                for line in resp.iter_lines():
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                        token = obj.get("response")
                        if token:
                            yield token
                        if obj.get("done"):
                            break
                    except json.JSONDecodeError:
                        continue

    def list_models(self) -> list[ModelInfo]:
        if not self.is_available():
            return []
        try:
            with httpx.Client(timeout=10) as client:
                resp = client.get(self._url("/api/tags"))
                resp.raise_for_status()
                models = resp.json().get("models", [])
                return [ModelInfo(id=m.get("name", ""), provider=self.name) for m in models if m.get("name")]
        except httpx.HTTPError:
            return []
