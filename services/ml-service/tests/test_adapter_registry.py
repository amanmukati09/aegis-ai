"""Tests for AdapterRegistry.check_dependency and MissingAdapterDependencyError
(task 9.1), covering task 9.2's Property 8.

Property 8: Missing adapter/embedding dependency produces an actionable error.
Validates: Requirements 2.4, 8.3
"""
from __future__ import annotations

import uuid

import pytest
from hypothesis import given, settings, strategies as st

from app.providers.adapter_registry import AdapterRegistry
from app.providers.base import MissingAdapterDependencyError, ProviderUnavailableError

# Provider_Type identifiers: catalog uses simple lowercase slugs (groq, ollama,
# anthropic, ...); generate realistic-looking slugs rather than arbitrary text.
_provider_type_strategy = st.text(
    alphabet=st.characters(whitelist_categories=("Ll",)), min_size=1, max_size=20
)

# Dependency names guaranteed not to be importable: a fixed unimportable prefix
# plus a random suffix, so collisions with real installed packages are
# effectively impossible.
_dependency_suffix_strategy = st.text(
    alphabet=st.characters(whitelist_categories=("Ll", "Nd")), min_size=1, max_size=20
)


def _unimportable_dependency(suffix: str) -> str:
    return f"definitely_not_a_real_package_{suffix}"


# ---------------------------------------------------------------------------
# Property 8: missing dependency -> MissingAdapterDependencyError, distinguishable
# ---------------------------------------------------------------------------


@given(provider_type=_provider_type_strategy, suffix=_dependency_suffix_strategy)
@settings(max_examples=100)
def test_property_8_missing_dependency_raises_actionable_error(provider_type, suffix):
    dependency = _unimportable_dependency(suffix)
    catalog = {
        provider_type: {
            "adapter_class": "app.providers.groq_provider.GroqAdapter",
            "adapter_dependency": dependency,
        }
    }
    registry = AdapterRegistry(catalog)

    with pytest.raises(MissingAdapterDependencyError) as exc_info:
        registry.check_dependency(provider_type)

    err = exc_info.value
    # Distinguishable from ProviderUnavailableError and unrelated exceptions.
    assert not isinstance(err, ProviderUnavailableError)
    assert type(err) is MissingAdapterDependencyError

    # Actionable: names the Provider_Type and the missing dependency.
    assert err.provider_type == provider_type
    assert err.dependency == dependency
    assert provider_type in str(err) or dependency in str(err)
    assert dependency in str(err)


@given(provider_type=_provider_type_strategy)
@settings(max_examples=100)
def test_property_8_no_dependency_declared_never_raises(provider_type):
    """Provider_Types with adapter_dependency = None (e.g. groq, ollama) are
    always satisfied -- no import check, no error."""
    catalog = {
        provider_type: {
            "adapter_class": "app.providers.groq_provider.GroqAdapter",
            "adapter_dependency": None,
        }
    }
    registry = AdapterRegistry(catalog)

    # Should not raise.
    registry.check_dependency(provider_type)


def test_missing_adapter_dependency_error_distinguishable_from_provider_unavailable():
    """The two error types must be independently catchable: a missing optional
    dependency is a different failure mode than a configured-but-unusable
    provider, per Requirements 2.4/8.3."""
    assert not issubclass(MissingAdapterDependencyError, ProviderUnavailableError)
    assert not issubclass(ProviderUnavailableError, MissingAdapterDependencyError)


def test_check_dependency_with_real_unimportable_package_example():
    """Concrete (non-property) example mirroring the exact scenario the
    requirement describes: a Provider_Type whose adapter_dependency names a
    package that is not installed."""
    dependency = f"definitely_not_a_real_package_{uuid.uuid4().hex}"
    catalog = {
        "anthropic": {
            "adapter_class": "app.providers.groq_provider.GroqAdapter",
            "adapter_dependency": dependency,
        }
    }
    registry = AdapterRegistry(catalog)

    with pytest.raises(MissingAdapterDependencyError) as exc_info:
        registry.check_dependency("anthropic")

    assert exc_info.value.provider_type == "anthropic"
    assert exc_info.value.dependency == dependency


def test_check_dependency_for_groq_and_ollama_never_raises():
    """Groq and Ollama are httpx-only (adapter_dependency = None in the real
    catalog seed) and must never trigger a dependency error."""
    catalog = {
        "groq": {"adapter_class": "app.providers.groq_provider.GroqAdapter", "adapter_dependency": None},
        "ollama": {"adapter_class": "app.providers.ollama_provider.OllamaAdapter", "adapter_dependency": None},
    }
    registry = AdapterRegistry(catalog)

    registry.check_dependency("groq")
    registry.check_dependency("ollama")
