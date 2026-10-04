"""Pydantic request/response models for the ML sidecar API."""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class ProviderOpts(BaseModel):
    provider: str | None = None
    model: str | None = None


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(ProviderOpts):
    message: str
    history: list[ChatMessage] = Field(default_factory=list)
    system: str | None = None
    # Short-lived JWT scoped to the requesting user (gateway's JwtService.issueToolToken)
    # and the gateway's own base URL. When both are present the chat agent's tools can
    # call back into the gateway's REST API, authenticated as that same user, so
    # workspace-visibility/org scoping apply automatically. Absent for callers that
    # don't need tool-calling (e.g. direct ML-service testing).
    tool_token: str | None = Field(default=None, alias="toolToken")
    gateway_base_url: str | None = Field(default=None, alias="gatewayBaseUrl")

    model_config = ConfigDict(populate_by_name=True)


class ChatResponse(BaseModel):
    reply: str
    model: str
    provider: str


class DetectRequest(ProviderOpts):
    logs: list[str] = Field(default_factory=list)


class DetectResponse(BaseModel):
    anomaly_detected: bool = False
    anomaly_type: str = "unknown"
    severity: str = "medium"
    affected_component: str = "unknown"
    description: str = ""


class DiagnoseRequest(ProviderOpts):
    anomaly: dict = Field(default_factory=dict)
    logs: list[str] = Field(default_factory=list)


class DiagnoseResponse(BaseModel):
    root_cause: str = ""
    confidence: float = 0.0
    evidence: list[str] = Field(default_factory=list)
    contributing_factors: list[str] = Field(default_factory=list)


class RemediationRequest(ProviderOpts):
    anomaly: dict = Field(default_factory=dict)
    root_cause: dict = Field(default_factory=dict)


class RemediationResponse(BaseModel):
    immediate_actions: list[str] = Field(default_factory=list)
    diagnostic_commands: list[str] = Field(default_factory=list)
    escalation_needed: bool = False
    estimated_recovery_time: str = "Unknown"
    prevention_measures: list[str] = Field(default_factory=list)
