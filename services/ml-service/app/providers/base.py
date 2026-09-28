"""AiProvider port and shared types.

Every LLM call in the service goes through this interface. Concrete providers
(Groq, Gemini, OpenRouter, Ollama, OpenAI, Anthropic) implement it. Selection is
by name (per-request or the env default), so swapping providers is config, not code.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterator, Protocol, runtime_checkable


@dataclass(frozen=True)
class ModelInfo:
    id: str
    provider: str
    label: str | None = None


@dataclass
class GenerationRequest:
    prompt: str
    model: str
    system: str | None = None
    json_mode: bool = False
    temperature: float = 0.7
    max_tokens: int | None = None
    # base64 images for multimodal providers; empty for text-only
    images: list[str] = field(default_factory=list)


@runtime_checkable
class AiProvider(Protocol):
    """Provider port. Implementations must be safe to construct even without a key
    (they simply report unavailable via `is_available`)."""

    name: str

    def is_available(self) -> bool:
        """True when the provider is configured (key/url present) and usable."""
        ...

    def generate(self, req: GenerationRequest) -> str:
        """Return the model's completion as a string (JSON string when json_mode)."""
        ...

    def stream(self, req: GenerationRequest) -> Iterator[str]:
        """Yield tokens/chunks for streaming responses."""
        ...

    def list_models(self) -> list[ModelInfo]:
        """Return the models this provider exposes."""
        ...


class ProviderUnavailableError(RuntimeError):
    """Raised when a selected provider has no credentials configured."""
