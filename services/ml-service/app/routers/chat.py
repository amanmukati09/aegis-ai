"""Chat endpoints: non-streaming and SSE streaming, powered by a LangChain/LangGraph
agent (langchain.agents.create_agent) with real tool-calling (incident search, KB
search, NL-to-SQL analytics) that calls back into the gateway. See
``app.agents.copilot`` for the agent/tool definitions.

The HTTP contract here (request/response shapes, SSE framing, error sentinel) is
unchanged from the previous provider-registry implementation so the gateway and
frontend need zero changes.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, SystemMessage

from ..agents.copilot import CopilotContext, get_agent
from ..config import settings
from ..schemas import ChatRequest, ChatResponse

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/v1", tags=["chat"])

# Sentinel prefix on a streamed error's data payload. The gateway relays SSE *data* only
# (WebFlux strips the event name), so this lets it detect and surface stream errors instead
# of appending them to the assistant reply.
_ERROR_SENTINEL = "__AEGIS_STREAM_ERROR__"

_DEFAULT_SYSTEM = (
    "You are AegisAI, an expert SRE/DevOps copilot. Be concise, technical, and "
    "actionable. Never output destructive commands (rm -rf /, DROP DATABASE, etc.)."
)


def _build_messages(req: ChatRequest) -> list:
    """Convert the gateway's flattened history + new message into LangChain messages.
    Only user/assistant turns are replayed; the system prompt is handled separately by
    create_agent's system_prompt so it isn't duplicated into the message list."""
    messages: list = []
    for m in req.history[-20:]:
        if m.role == "user":
            messages.append(HumanMessage(content=m.content))
        else:
            messages.append(AIMessage(content=m.content))
    messages.append(HumanMessage(content=req.message))
    return messages


def _context(req: ChatRequest) -> CopilotContext:
    return CopilotContext(tool_token=req.tool_token, gateway_base_url=req.gateway_base_url)


@router.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest) -> ChatResponse:
    model = req.model or settings.default_model
    agent = get_agent(model)
    system = req.system or _DEFAULT_SYSTEM

    result = agent.invoke(
        {"messages": [SystemMessage(content=system), *_build_messages(req)]},
        context=_context(req),
    )
    reply = _last_ai_text(result["messages"])
    return ChatResponse(reply=reply, model=model, provider=settings.default_provider)


@router.post("/chat/stream")
def chat_stream(req: ChatRequest) -> StreamingResponse:
    model = req.model or settings.default_model
    agent = get_agent(model)
    system = req.system or _DEFAULT_SYSTEM
    inputs = {"messages": [SystemMessage(content=system), *_build_messages(req)]}
    context = _context(req)

    def event_stream():
        try:
            for chunk, _metadata in agent.stream(
                inputs, context=context, stream_mode="messages"
            ):
                # Only the model node emits user-facing text; tool-call deltas and tool
                # results surface as other message/metadata shapes and are skipped here
                # (the model's own synthesis of tool results is what gets streamed).
                if not isinstance(chunk, AIMessageChunk):
                    continue
                text = _chunk_text(chunk)
                if text:
                    yield f"data: {text}\n\n"
        except Exception as exc:  # surface a terminal error to the client
            # Prefix with a stable sentinel so downstream consumers (the gateway relays
            # SSE data payloads only, not event names) can distinguish an error from a
            # normal token. Both the event name and the sentinel are emitted.
            logger.warning("chat stream failed: %s", exc)
            yield f"event: error\ndata: {_ERROR_SENTINEL}{exc}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")


def _chunk_text(chunk: AIMessageChunk | AIMessage) -> str:
    """Extract plain text from a message/chunk, tolerating both the simple string
    content form and the structured content-block form some providers use."""
    content = chunk.content
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, str):
                parts.append(block)
            elif isinstance(block, dict) and block.get("type") == "text":
                parts.append(block.get("text", ""))
        return "".join(parts)
    return ""


def _last_ai_text(messages: list) -> str:
    """The agent loop appends tool-call AI messages (empty/partial content) before the
    final synthesized answer; walk back to the last AI message that actually has text."""
    for msg in reversed(messages):
        if isinstance(msg, AIMessage):
            text = msg.content if isinstance(msg.content, str) else _chunk_text(msg)
            if text:
                return text
    return ""
