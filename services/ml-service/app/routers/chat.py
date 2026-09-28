"""Chat endpoints: non-streaming and SSE streaming, provider-agnostic."""
from __future__ import annotations

from fastapi import APIRouter
from fastapi.responses import StreamingResponse

from ..providers import GenerationRequest, registry
from ..schemas import ChatRequest, ChatResponse

router = APIRouter(prefix="/v1", tags=["chat"])

# Sentinel prefix on a streamed error's data payload. The gateway relays SSE *data* only
# (WebFlux strips the event name), so this lets it detect and surface stream errors instead
# of appending them to the assistant reply.
_ERROR_SENTINEL = "__AEGIS_STREAM_ERROR__"

_DEFAULT_SYSTEM = (
    "You are AegisAI, an expert SRE/DevOps copilot. Be concise, technical, and "
    "actionable. Never output destructive commands (rm -rf /, DROP DATABASE, etc.)."
)


def _build_prompt(req: ChatRequest) -> str:
    lines: list[str] = []
    for m in req.history[-20:]:
        speaker = "User" if m.role == "user" else "AI"
        lines.append(f"{speaker}: {m.content}")
    lines.append(f"User: {req.message}")
    lines.append("AI:")
    return "\n".join(lines)


@router.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest) -> ChatResponse:
    provider = registry.resolve(req.provider)
    model = req.model or registry.default_model
    gen = GenerationRequest(
        prompt=_build_prompt(req),
        model=model,
        system=req.system or _DEFAULT_SYSTEM,
        temperature=0.7,
    )
    reply = provider.generate(gen)
    return ChatResponse(reply=reply, model=model, provider=provider.name)


@router.post("/chat/stream")
def chat_stream(req: ChatRequest) -> StreamingResponse:
    provider = registry.resolve(req.provider)
    model = req.model or registry.default_model
    gen = GenerationRequest(
        prompt=_build_prompt(req),
        model=model,
        system=req.system or _DEFAULT_SYSTEM,
        temperature=0.7,
    )

    def event_stream():
        try:
            for token in provider.stream(gen):
                yield f"data: {token}\n\n"
        except Exception as exc:  # surface a terminal error to the client
            # Prefix with a stable sentinel so downstream consumers (the gateway relays
            # SSE data payloads only, not event names) can distinguish an error from a
            # normal token. Both the event name and the sentinel are emitted.
            yield f"event: error\ndata: {_ERROR_SENTINEL}{exc}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")
