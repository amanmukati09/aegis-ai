"""Pydantic request/response models for the ML sidecar API."""
from __future__ import annotations

from pydantic import BaseModel, Field


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
