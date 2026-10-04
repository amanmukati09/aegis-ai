"""AegisAI Copilot agent: LangChain + LangGraph (create_agent) with real tool-calling.

The agent calls back into the gateway's own REST API for incident search, knowledge-base
search, and NL-to-SQL analytics. Each call is authenticated with a short-lived JWT scoped
to the end user who started the chat (minted by the gateway's JwtService.issueToolToken
and forwarded in the request). The gateway's AuthenticationFilter resolves that token into
the exact same AuthPrincipal a browser request would get, so workspace-visibility and org
scoping apply to every tool call automatically — no new auth concept, no new credentials.

Model loading goes through init_chat_model so swapping providers later (per
DEFAULT_AI_PROVIDER) stays config, not code; Groq is the only provider wired today via
langchain-groq, matching the rest of the service's "Groq is the default, hosted, free"
convention.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

import httpx
from langchain.agents import create_agent
from langchain.chat_models import init_chat_model
from langchain.tools import ToolRuntime, tool

from ..config import settings

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = (
    "You are AegisAI, an expert SRE/DevOps copilot embedded in an incident-management "
    "platform. Be concise, technical, and actionable. Never output destructive commands "
    "(rm -rf /, DROP DATABASE, etc.).\n\n"
    "You have 5 tools. Use them whenever they'd give a better, grounded answer instead "
    "of guessing:\n"
    "- search_incidents: look up real incidents by keyword (title/description/root "
    "cause). Use for \"has this happened before\" / \"what incidents are open\" questions.\n"
    "- search_knowledge_base: find a documented fix from a past resolved incident. Use "
    "for \"how do we usually handle X\" / \"what's the known fix\" questions. This does "
    "real semantic search, so it can match on meaning, not just exact keywords.\n"
    "- ask_analytics: answer counting/aggregate/trend questions (e.g. \"how many "
    "critical incidents this week\") as a validated query, not a guess.\n"
    "- get_runbook: get operational steps for a SPECIFIC EXISTING incident (needs its "
    "id, e.g. from search_incidents). Use when the user references a real incident and "
    "wants to know what to actually run/do about it.\n"
    "- diagnose_live: the user is describing a problem happening RIGHT NOW that has NO "
    "incident yet (e.g. \"checkout is throwing 500s, what's wrong\"). This runs a live "
    "detect-root-cause-remediate pipeline and returns an immediate diagnosis WITHOUT "
    "creating an incident — perfect for fast conversational triage before anyone files "
    "anything formal. Don't use get_runbook for this case; there's no incident id yet.\n\n"
    "When a tool returns incident or KB results, cite them by id/title so the user can "
    "open the source. If a tool call fails, returns nothing, or says access is denied, "
    "say so plainly instead of inventing an answer — never fabricate incident details, "
    "severities, or counts a tool didn't actually return."
)

# Tool-call HTTP timeout to the gateway. Short — these are simple DB-backed reads/CTEs,
# not another LLM round trip, so they should return quickly; a hung gateway call must
# not stall the whole chat turn indefinitely.
_TOOL_TIMEOUT = httpx.Timeout(20.0)


@dataclass
class CopilotContext:
    """Per-invocation, immutable context (LangChain's context_schema) threaded into
    tools via ToolRuntime. Absent tool_token/gateway_base_url means tool-calling is
    unavailable for this turn (e.g. direct ML-service testing without the gateway)."""

    tool_token: str | None = None
    gateway_base_url: str | None = None


def _tool_headers(ctx: CopilotContext) -> dict[str, str] | None:
    if not ctx.tool_token or not ctx.gateway_base_url:
        return None
    return {"Authorization": f"Bearer {ctx.tool_token}"}


@tool
def search_incidents(query: str, runtime: ToolRuntime[CopilotContext]) -> str:
    """Search real incidents in this organization by keyword (title, description, root
    cause). Use this when the user asks about a specific incident, past outage, or wants
    to know if something like their current problem has happened before. Returns only
    incidents the current user is allowed to see."""
    ctx = runtime.context
    headers = _tool_headers(ctx)
    if headers is None:
        return "Incident search is unavailable right now (no gateway context for this request)."
    try:
        resp = httpx.get(
            f"{ctx.gateway_base_url}/api/incidents/search",
            params={"q": query, "limit": 10},
            headers=headers,
            timeout=_TOOL_TIMEOUT,
        )
        resp.raise_for_status()
        results = resp.json()
    except httpx.HTTPError as exc:
        logger.warning("search_incidents tool call failed: %s", exc)
        return "Incident search failed. Tell the user to try again or check the incidents page directly."
    if not results:
        return "No matching incidents found."
    lines = []
    for inc in results:
        lines.append(
            f"- [{inc.get('id', '')}] {inc.get('title', 'Untitled')} "
            f"(severity={inc.get('severity', 'unknown')}, status={inc.get('status', 'unknown')})"
        )
    return "\n".join(lines)


@tool
def search_knowledge_base(query: str, runtime: ToolRuntime[CopilotContext]) -> str:
    """Search the knowledge base for articles (symptoms, root cause, solution,
    prevention) distilled from previously resolved incidents. Use this when the user
    wants a known fix or asks "how do we usually handle X". Returns only articles whose
    source incident the current user is allowed to see."""
    ctx = runtime.context
    headers = _tool_headers(ctx)
    if headers is None:
        return "Knowledge-base search is unavailable right now (no gateway context for this request)."
    try:
        resp = httpx.get(
            f"{ctx.gateway_base_url}/api/kb/search",
            params={"q": query},
            headers=headers,
            timeout=_TOOL_TIMEOUT,
        )
        resp.raise_for_status()
        results = resp.json()
    except httpx.HTTPError as exc:
        logger.warning("search_knowledge_base tool call failed: %s", exc)
        return "Knowledge-base search failed. Tell the user to try again or check the KB page directly."
    if not results:
        return "No matching knowledge-base articles found."
    lines = []
    for art in results[:10]:
        lines.append(
            f"- [{art.get('id', '')}] {art.get('title', 'Untitled')} "
            f"(category={art.get('category', '')}): {art.get('snippet', '')}"
        )
    return "\n".join(lines)


@tool
def ask_analytics(question: str, runtime: ToolRuntime[CopilotContext]) -> str:
    """Ask a data/analytics question about this organization's incidents in plain
    English (e.g. "how many critical incidents this week", "incidents by severity").
    Runs as a validated, read-only, workspace-scoped query. Use this for counts,
    aggregates, and trends rather than guessing numbers."""
    ctx = runtime.context
    headers = _tool_headers(ctx)
    if headers is None:
        return "Analytics is unavailable right now (no gateway context for this request)."
    try:
        resp = httpx.post(
            f"{ctx.gateway_base_url}/api/analytics/ask",
            json={"question": question},
            headers=headers,
            timeout=_TOOL_TIMEOUT,
        )
        resp.raise_for_status()
        data = resp.json()
    except httpx.HTTPError as exc:
        logger.warning("ask_analytics tool call failed: %s", exc)
        return "That analytics question couldn't be run safely. Tell the user to try rephrasing it."
    rows = data.get("rows", [])
    if not rows:
        return "The query ran but returned no rows."
    # Keep this compact — rows can be wide; cap what we hand back to the model.
    preview = rows[:20]
    return f"columns={data.get('columns', [])}\nrows={preview}"


@tool
def get_runbook(incident_id: str, runtime: ToolRuntime[CopilotContext]) -> str:
    """Get (or generate) the step-by-step operational runbook for a SPECIFIC, already
    existing incident, given its incident id (e.g. one returned by search_incidents).
    Use this when the user references a real incident by id/title and wants concrete
    operational steps to run — different from diagnose_live, which is for a problem
    that has no incident yet."""
    ctx = runtime.context
    headers = _tool_headers(ctx)
    if headers is None:
        return "Runbook lookup is unavailable right now (no gateway context for this request)."
    try:
        resp = httpx.post(
            f"{ctx.gateway_base_url}/api/incidents/{incident_id}/runbook",
            headers=headers,
            timeout=_TOOL_TIMEOUT,
        )
        resp.raise_for_status()
        data = resp.json()
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code == 404:
            return "No incident found with that id (or you don't have access to it)."
        logger.warning("get_runbook tool call failed: %s", exc)
        return "Runbook lookup failed. Tell the user to try again or open the incident directly."
    except httpx.HTTPError as exc:
        logger.warning("get_runbook tool call failed: %s", exc)
        return "Runbook lookup failed. Tell the user to try again or open the incident directly."
    steps = data.get("steps") or []
    if steps:
        out = [f"Runbook: {data.get('title', 'Incident Runbook')} "
               f"(severity={data.get('severity', 'unknown')}, "
               f"est. {data.get('estimated_time', 'unknown')})"]
        for i, s in enumerate(steps):
            if isinstance(s, dict):
                phase = s.get("phase", "")
                action = s.get("action", "")
                command = s.get("command", "")
                line = f"{i + 1}. [{phase}] {action}"
                if command:
                    line += f" -- `{command}`"
                out.append(line)
            else:
                out.append(f"{i + 1}. {s}")
        validation = data.get("validation_checks") or []
        if validation:
            out.append("Validation: " + "; ".join(str(v) for v in validation))
        return "\n".join(out)
    # Fallback: hand back whatever shape the ML sidecar produced so the model can still
    # synthesize an answer instead of getting nothing.
    return str(data)


@tool
def diagnose_live(description: str, runtime: ToolRuntime[CopilotContext]) -> str:
    """Live-diagnose a problem the user is describing RIGHT NOW in plain English (e.g.
    "checkout API is throwing 500s and Redis connections look exhausted"), when there is
    NO existing incident for it yet. Runs the same detect -> diagnose root cause ->
    suggest remediation pipeline incident creation uses, but does NOT create or persist
    an incident — this is for quick conversational triage. If the user wants this
    tracked as a real incident afterwards, tell them to use the "create incident"
    feature; this tool never does that for them."""
    ctx = runtime.context
    headers = _tool_headers(ctx)
    if headers is None:
        return "Live diagnosis is unavailable right now (no gateway context for this request)."
    try:
        resp = httpx.post(
            f"{ctx.gateway_base_url}/api/diagnose/dry-run",
            json={"logs": [description]},
            headers=headers,
            timeout=_TOOL_TIMEOUT,
        )
        resp.raise_for_status()
        data = resp.json()
    except httpx.HTTPError as exc:
        logger.warning("diagnose_live tool call failed: %s", exc)
        return "Live diagnosis failed. Tell the user to try again or rephrase the description."

    anomaly = data.get("anomaly", {}) or {}
    diagnosis = data.get("diagnosis", {}) or {}
    remediation = data.get("remediation", {}) or {}

    lines = [
        f"Anomaly: {anomaly.get('anomaly_type', 'unknown')} "
        f"(severity={anomaly.get('severity', 'unknown')}, "
        f"component={anomaly.get('affected_component', 'unknown')})",
        f"Root cause: {diagnosis.get('root_cause', 'unknown')} "
        f"(confidence={diagnosis.get('confidence', 0)})",
    ]
    actions = remediation.get("immediate_actions") or []
    if actions:
        lines.append("Immediate actions: " + "; ".join(actions))
    if remediation.get("escalation_needed"):
        lines.append("Escalation recommended.")
    return "\n".join(lines)


_TOOLS = [search_incidents, search_knowledge_base, ask_analytics, get_runbook, diagnose_live]

# One agent instance per model id, so a per-request provider/model override (ChatRequest.
# model) doesn't force rebuilding the graph on every single call, but different models
# aren't sharing a stale instance either.
_agent_cache: dict[str, object] = {}


def _model_id(model: str | None) -> str:
    """Resolve a model override to an init_chat_model-compatible id. Groq is the only
    provider wired here; DEFAULT_AI_PROVIDER controls the prefix, so switching provider
    later is a config change, not a code change — add the matching langchain integration
    package and extend this mapping."""
    name = model or settings.default_model
    provider = settings.default_provider
    return f"{provider}:{name}"


def get_agent(model: str | None = None):
    """Build (or reuse) a create_agent instance for the given model override."""
    model_id = _model_id(model)
    agent = _agent_cache.get(model_id)
    if agent is not None:
        return agent

    chat_model = init_chat_model(model_id, api_key=settings.groq_api_key, temperature=0.7)
    agent = create_agent(
        model=chat_model,
        tools=_TOOLS,
        system_prompt=SYSTEM_PROMPT,
        context_schema=CopilotContext,
    )
    _agent_cache[model_id] = agent
    return agent
