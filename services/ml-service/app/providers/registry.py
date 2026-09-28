"""Provider registry: builds providers from config and resolves by name.

Adding a new provider = implement AiProvider + register it here. Nothing else
in the service references a concrete provider.
"""
from __future__ import annotations

from ..config import settings
from .base import AiProvider, ModelInfo, ProviderUnavailableError
from .groq_provider import GroqProvider
from .ollama_provider import OllamaProvider


class ProviderRegistry:
    def __init__(self) -> None:
        # Register all known providers. Each is safe to construct without a key;
        # availability is reported via is_available().
        self._providers: dict[str, AiProvider] = {
            "groq": GroqProvider(settings.groq_api_key),
            "ollama": OllamaProvider(settings.ollama_url),
            # Future: "gemini", "openrouter", "openai", "anthropic"
        }
        self._default = settings.default_provider

    def resolve(self, name: str | None = None) -> AiProvider:
        key = (name or self._default).lower()
        provider = self._providers.get(key)
        if provider is None:
            raise ProviderUnavailableError(f"Unknown provider '{key}'")
        if not provider.is_available():
            # Fall back to any available provider so the demo still works.
            for candidate in self._providers.values():
                if candidate.is_available():
                    return candidate
            raise ProviderUnavailableError(
                f"Provider '{key}' has no credentials and no fallback is configured"
            )
        return provider

    def available(self) -> list[AiProvider]:
        return [p for p in self._providers.values() if p.is_available()]

    def all_models(self) -> list[ModelInfo]:
        models: list[ModelInfo] = []
        for provider in self._providers.values():
            if provider.is_available():
                models.extend(provider.list_models())
        return models

    @property
    def default_provider(self) -> str:
        return self._default

    @property
    def default_model(self) -> str:
        return settings.default_model


registry = ProviderRegistry()
