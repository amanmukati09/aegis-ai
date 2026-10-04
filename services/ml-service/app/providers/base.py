"""AiProvider / ProviderAdapter ports and shared types.

Every LLM call in the service goes through one of these interfaces. Concrete
providers (Groq, Gemini, OpenRouter, Ollama, OpenAI, Anthropic, ...) implement
one of them. Selection is by name (per-request or the env default), so
swapping providers is config, not code.

`ProviderAdapter` is the new, catalog-driven interface introduced by the
ai-provider-flexibility feature (see .kiro/specs/ai-provider-flexibility).
`AiProvider` is the pre-existing Protocol and is kept here, unchanged, purely
for backward compatibility: `registry.py`, `groq_provider.py`, and
`ollama_provider.py` still import and subclass it directly. Those modules are
migrated to `ProviderAdapter` in tasks 8.2/8.3 (GroqAdapter/OllamaAdapter
rewrites) and 9.1 (AdapterRegistry); router call sites migrate in tasks
16-19. Once that migration lands, `AiProvider` can be removed. Until then,
both interfaces coexist so this task (8.1) does not need to touch any other
file.
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


@dataclass
class GenerationResult:
    """Replaces the bare `str` return of the old AiProvider.generate so every
    call site can report tokens for usage tracking without each router
    re-deriving them."""

    text: str
    input_tokens: int | None = None
    output_tokens: int | None = None


@runtime_checkable
class AiProvider(Protocol):
    """Provider port (pre-existing). Implementations must be safe to construct
    even without a key (they simply report unavailable via `is_available`).

    Kept for backward compatibility with `registry.py`, `groq_provider.py`,
    and `ollama_provider.py` until they are migrated to `ProviderAdapter`
    (tasks 8.2, 8.3, 9.1). New code should prefer `ProviderAdapter`.
    """

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


@runtime_checkable
class ProviderAdapter(Protocol):
    """One adapter class per Provider_Type. Constructed from an arbitrary
    credential dict (shape defined by that Provider_Type's catalog entry), so
    adding a Provider_Type never changes this Protocol or any caller of it."""

    provider_type: str

    def __init__(self, credentials: dict[str, str], settings: dict[str, str]) -> None: ...

    def is_available(self) -> bool:
        """True when required credential_fields are present and non-empty."""
        ...

    def generate(self, req: GenerationRequest) -> GenerationResult: ...

    def stream(self, req: GenerationRequest) -> Iterator[str]: ...

    def list_models(self) -> list[ModelInfo]:
        """Live model list when the Provider_Type's API supports it; otherwise
        the catalog's default_models."""
        ...

    def embed(self, texts: list[str]) -> list[list[float]] | None:
        """Optional: only Provider_Types capable of embeddings implement this
        meaningfully; default returns None so the caller falls back to the
        local EmbeddingService (Requirement 8)."""
        ...


class ProviderUnavailableError(RuntimeError):
    """Raised when a selected provider/model has no usable credentials."""


class MissingAdapterDependencyError(RuntimeError):
    """Raised when a Provider_Type's adapter_dependency (e.g. langchain-anthropic)
    is not importable. Carries provider_type and the missing package name so the
    caller can surface Req 2.4 / 8.3's actionable error instead of a generic failure."""

    def __init__(self, provider_type: str, dependency: str):
        self.provider_type = provider_type
        self.dependency = dependency
        super().__init__(
            f"Provider type '{provider_type}' requires '{dependency}', which is not "
            f"installed. Install it (see requirements-heavy.txt or pip install {dependency}) "
            f"to use this provider."
        )
