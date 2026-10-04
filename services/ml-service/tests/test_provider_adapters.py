"""Tests for the catalog-driven ProviderAdapter implementations (GroqAdapter,
OllamaAdapter) introduced by tasks 8.2/8.3 of the ai-provider-flexibility spec.

Covers:
- Task 8.4: Property 27 (provider adapter interface conformance)
- Task 8.5: unit tests confirming the rewritten adapters preserve the
  pre-rewrite GroqProvider/OllamaProvider behavior.

All HTTP calls are mocked via httpx.MockTransport -- no real network access.
"""
from __future__ import annotations

import json
from typing import Iterator

import httpx
import pytest
from hypothesis import given, settings, strategies as st

from app.providers.base import GenerationRequest, GenerationResult, ModelInfo, ProviderAdapter
from app.providers.groq_provider import GroqAdapter, GroqProvider
from app.providers.ollama_provider import OllamaAdapter, OllamaProvider

GROQ_CHAT_RESPONSE = {
    "choices": [{"message": {"content": "hello from groq"}}],
    "usage": {"prompt_tokens": 11, "completion_tokens": 4},
}

GROQ_MODELS_RESPONSE = {"data": [{"id": "openai/gpt-oss-20b"}, {"id": "openai/gpt-oss-120b"}]}

GROQ_STREAM_BODY = (
    'data: {"choices": [{"delta": {"content": "hel"}}]}\n'
    'data: {"choices": [{"delta": {"content": "lo"}}]}\n'
    "data: [DONE]\n"
)

OLLAMA_GENERATE_RESPONSE = {
    "response": "hello from ollama",
    "prompt_eval_count": 7,
    "eval_count": 3,
    "done": True,
}

OLLAMA_TAGS_RESPONSE = {"models": [{"name": "llama3"}, {"name": "mistral"}]}

OLLAMA_STREAM_BODY = (
    json.dumps({"response": "hel", "done": False}) + "\n" +
    json.dumps({"response": "lo", "done": True}) + "\n"
)


def _groq_transport() -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/openai/v1/chat/completions":
            body = request.read()
            payload = json.loads(body)
            if payload.get("stream"):
                return httpx.Response(200, text=GROQ_STREAM_BODY)
            return httpx.Response(200, json=GROQ_CHAT_RESPONSE)
        if request.url.path == "/openai/v1/models":
            return httpx.Response(200, json=GROQ_MODELS_RESPONSE)
        return httpx.Response(404, json={"error": "not found"})

    return httpx.MockTransport(handler)


def _ollama_transport() -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/generate":
            body = request.read()
            payload = json.loads(body)
            if payload.get("stream"):
                return httpx.Response(200, text=OLLAMA_STREAM_BODY)
            return httpx.Response(200, json=OLLAMA_GENERATE_RESPONSE)
        if request.url.path == "/api/tags":
            return httpx.Response(200, json=OLLAMA_TAGS_RESPONSE)
        return httpx.Response(404, json={"error": "not found"})

    return httpx.MockTransport(handler)


@pytest.fixture(autouse=True)
def _mock_httpx_client(monkeypatch, request):
    """Route every httpx.Client constructed during this test through the
    MockTransport bound to this test node via `_bind_transport`, so adapters
    under test never make a real network call.

    Each test binds its transport (groq or ollama) with `_bind_transport`
    before invoking the adapter; this fixture injects that transport into
    any `httpx.Client(...)` call the adapter makes internally, by defaulting
    the `transport=` kwarg at construction time.
    """
    real_client_init = httpx.Client.__init__

    def patched_init(self, *args, **kwargs):
        kwargs.setdefault("transport", request.node._adapter_transport)
        return real_client_init(self, *args, **kwargs)

    monkeypatch.setattr(httpx.Client, "__init__", patched_init)
    yield


def _bind_transport(request, transport: httpx.MockTransport) -> None:
    request.node._adapter_transport = transport


# ---------------------------------------------------------------------------
# Task 8.4 - Property 27: Provider adapter interface conformance
# Validates: Requirements 11.3
# ---------------------------------------------------------------------------


def _make_groq_adapter() -> GroqAdapter:
    return GroqAdapter({"api_key": "test-key"}, {})


def _make_ollama_adapter() -> OllamaAdapter:
    return OllamaAdapter({}, {"base_url": "http://localhost:11434"})


ADAPTER_FACTORIES = {
    "groq": (_make_groq_adapter, _groq_transport, "groq"),
    "ollama": (_make_ollama_adapter, _ollama_transport, "ollama"),
}


@given(adapter_key=st.sampled_from(sorted(ADAPTER_FACTORIES)))
@settings(max_examples=100)
def test_property_27_adapter_interface_conformance(adapter_key, request):
    """Property 27: for every registered Provider_Type's adapter instance,
    is_available()/generate()/stream()/list_models() succeed with a uniform
    shape and no caller-side branching is required to use them."""
    factory, transport_factory, expected_provider_type = ADAPTER_FACTORIES[adapter_key]
    _bind_transport(request, transport_factory())

    adapter = factory()

    # Uniform runtime-checkable Protocol conformance -- no isinstance branching
    # on concrete adapter class needed by any caller.
    assert isinstance(adapter, ProviderAdapter)

    assert isinstance(adapter.provider_type, str) and adapter.provider_type == expected_provider_type

    available = adapter.is_available()
    assert isinstance(available, bool)
    assert available is True

    req = GenerationRequest(prompt="say hi", model="any-model")

    result = adapter.generate(req)
    assert isinstance(result, GenerationResult)
    assert isinstance(result.text, str)
    assert len(result.text) > 0

    stream_iter = adapter.stream(req)
    assert isinstance(stream_iter, Iterator)
    chunks = list(stream_iter)
    assert all(isinstance(c, str) for c in chunks)
    assert len(chunks) > 0

    models = adapter.list_models()
    assert isinstance(models, list)
    assert all(isinstance(m, ModelInfo) for m in models)

    embeddings = adapter.embed(["some text"])
    assert embeddings is None or (
        isinstance(embeddings, list) and all(isinstance(v, list) for v in embeddings)
    )


# A plain parametrized (non-Hypothesis) mirror kept for fast, explicit failure
# messages per adapter -- complements the property test above.
@pytest.mark.parametrize("adapter_key", sorted(ADAPTER_FACTORIES))
def test_adapter_interface_conformance_example(adapter_key, request):
    factory, transport_factory, expected_provider_type = ADAPTER_FACTORIES[adapter_key]
    _bind_transport(request, transport_factory())

    adapter = factory()
    assert isinstance(adapter, ProviderAdapter)
    assert adapter.provider_type == expected_provider_type
    assert adapter.is_available() is True

    req = GenerationRequest(prompt="say hi", model="any-model")
    result = adapter.generate(req)
    assert isinstance(result, GenerationResult)

    assert list(adapter.stream(req))
    assert isinstance(adapter.list_models(), list)
    assert adapter.embed(["x"]) is None


# ---------------------------------------------------------------------------
# Task 8.5: unit tests for GroqAdapter/OllamaAdapter behavior preservation
# Validates: Requirements 2.2
# ---------------------------------------------------------------------------


def test_groq_adapter_preserves_generate_text(request):
    _bind_transport(request, _groq_transport())

    req = GenerationRequest(prompt="say hi", model="openai/gpt-oss-20b")

    old_provider = GroqProvider("test-key")
    old_text = old_provider.generate(req)

    new_adapter = GroqAdapter({"api_key": "test-key"}, {})
    new_result = new_adapter.generate(req)

    assert isinstance(old_text, str)
    assert isinstance(new_result, GenerationResult)
    assert new_result.text == old_text == "hello from groq"


def test_groq_adapter_preserves_list_models(request):
    _bind_transport(request, _groq_transport())

    old_provider = GroqProvider("test-key")
    new_adapter = GroqAdapter({"api_key": "test-key"}, {})

    old_ids = sorted(m.id for m in old_provider.list_models())
    new_ids = sorted(m.id for m in new_adapter.list_models())
    assert old_ids == new_ids == sorted(["openai/gpt-oss-20b", "openai/gpt-oss-120b"])


def test_ollama_adapter_preserves_generate_text(request):
    _bind_transport(request, _ollama_transport())

    req = GenerationRequest(prompt="say hi", model="llama3")

    old_provider = OllamaProvider("http://localhost:11434")
    old_text = old_provider.generate(req)

    new_adapter = OllamaAdapter({}, {"base_url": "http://localhost:11434"})
    new_result = new_adapter.generate(req)

    assert isinstance(old_text, str)
    assert isinstance(new_result, GenerationResult)
    assert new_result.text == old_text == "hello from ollama"


def test_ollama_adapter_preserves_list_models(request):
    _bind_transport(request, _ollama_transport())

    old_provider = OllamaProvider("http://localhost:11434")
    new_adapter = OllamaAdapter({}, {"base_url": "http://localhost:11434"})

    old_ids = sorted(m.id for m in old_provider.list_models())
    new_ids = sorted(m.id for m in new_adapter.list_models())
    assert old_ids == new_ids == sorted(["llama3", "mistral"])


def test_groq_adapter_adds_token_counts_not_present_on_old_provider(request):
    """The constructor/return-type shape changes (Req 11.3) but the text itself
    is identical; the adapter additionally exposes token counts the old bare-str
    provider could not."""
    _bind_transport(request, _groq_transport())

    req = GenerationRequest(prompt="say hi", model="openai/gpt-oss-20b")
    new_adapter = GroqAdapter({"api_key": "test-key"}, {})
    result = new_adapter.generate(req)

    assert result.input_tokens == 11
    assert result.output_tokens == 4


def test_ollama_adapter_adds_token_counts_not_present_on_old_provider(request):
    _bind_transport(request, _ollama_transport())

    req = GenerationRequest(prompt="say hi", model="llama3")
    new_adapter = OllamaAdapter({}, {"base_url": "http://localhost:11434"})
    result = new_adapter.generate(req)

    assert result.input_tokens == 7
    assert result.output_tokens == 3
