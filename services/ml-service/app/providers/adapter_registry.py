"""AdapterRegistry: maps Provider_Type -> ProviderAdapter class.

Introduced by task 9.1 (ai-provider-flexibility spec). This is a separate,
additional module from `registry.py`'s pre-existing `ProviderRegistry`
singleton -- that class/module is left completely untouched because current
(unmigrated) router call sites (`chat.py`, `copilot.py`, `diagnosis.py`,
`advanced.py`, `bulk.py`) still depend on it until tasks 16-19 migrate them.

Per design.md:

    class AdapterRegistry:
        \"\"\"Maps provider_type -> adapter class, built from the catalog's
        adapter_class field via dynamic import. Construction of a specific
        adapter INSTANCE happens elsewhere (FailoverEngine, per resolved
        candidate) -- this registry only resolves the *class* and validates
        the optional dependency is importable, early (Req 2.4) rather than
        deep inside a generation call.\"\"\"

        def get_adapter_class(self, provider_type: str) -> type[ProviderAdapter]: ...
        def check_dependency(self, provider_type: str) -> None:
            \"\"\"Raises MissingAdapterDependencyError if adapter_dependency
            isn't importable.\"\"\"

`AdapterRegistry` is deliberately simple, stateless, and metadata-driven: it
does not fetch catalog data itself (that network-polling responsibility
belongs to task 14.4's config poller/snapshot cache, which lands later). It
is constructed with the catalog metadata it needs -- a `dict[str, dict]`
keyed by `provider_type`, each entry carrying at least `adapter_class` (a
dotted import path, e.g. "app.providers.groq_provider.GroqAdapter") and
optionally `adapter_dependency` (a pip package / importable module name, or
None if the adapter is stdlib/httpx-only, as Groq and Ollama are).
"""
from __future__ import annotations

import importlib

from .base import MissingAdapterDependencyError, ProviderAdapter


class AdapterRegistry:
    """Resolves a Provider_Type's `ProviderAdapter` class and validates its
    optional dependency, both driven purely by catalog metadata supplied at
    construction time (no network access, no singleton state)."""

    def __init__(self, catalog: dict[str, dict]) -> None:
        """`catalog` is keyed by provider_type, e.g.:

            {
                "groq": {
                    "adapter_class": "app.providers.groq_provider.GroqAdapter",
                    "adapter_dependency": None,
                },
                "anthropic": {
                    "adapter_class": "app.providers.anthropic_adapter.AnthropicAdapter",
                    "adapter_dependency": "langchain-anthropic",
                },
                ...
            }
        """
        self._catalog = catalog

    def _entry(self, provider_type: str) -> dict:
        entry = self._catalog.get(provider_type)
        if entry is None:
            raise KeyError(f"Unknown provider_type '{provider_type}'")
        return entry

    def get_adapter_class(self, provider_type: str) -> type[ProviderAdapter]:
        """Dynamically imports and returns the adapter class for `provider_type`,
        resolved from the catalog's `adapter_class` dotted-path string
        (e.g. "app.providers.groq_provider.GroqAdapter")."""
        adapter_class_path = self._entry(provider_type).get("adapter_class")
        if not adapter_class_path:
            raise ValueError(f"Provider type '{provider_type}' has no adapter_class configured")

        module_path, _, class_name = adapter_class_path.rpartition(".")
        if not module_path:
            raise ValueError(f"Invalid adapter_class path '{adapter_class_path}'")

        module = importlib.import_module(module_path)
        return getattr(module, class_name)

    def check_dependency(self, provider_type: str) -> None:
        """Raises `MissingAdapterDependencyError` if the Provider_Type's
        `adapter_dependency` (e.g. "langchain-anthropic") isn't importable.
        A None/empty `adapter_dependency` (e.g. groq, ollama -- httpx-only)
        is always satisfied and performs no import check."""
        dependency = self._entry(provider_type).get("adapter_dependency")
        if not dependency:
            return

        # pip package names and import module names sometimes differ (e.g.
        # "langchain-anthropic" installs as "langchain_anthropic"); normalize
        # hyphens to underscores for the import attempt.
        module_name = dependency.replace("-", "_")
        try:
            importlib.import_module(module_name)
        except (ImportError, ModuleNotFoundError):
            raise MissingAdapterDependencyError(provider_type, dependency)
