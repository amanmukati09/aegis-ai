"""RL triage + sentiment endpoints.

/v1/triage/queue   -> rank a set of incidents by learned priority
/v1/triage/prioritize -> priority for a single incident
/v1/triage/train   -> update the Q-table from resolution outcomes
/v1/triage/stats   -> Q-table size / exploration rate
/v1/sentiment      -> lexicon tone/urgency of a chat message
"""
from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel, Field

from ..agents import sentiment
from ..agents.rl_triage import agent

router = APIRouter(prefix="/v1", tags=["triage"])


class IncidentItem(BaseModel):
    id: str | None = None
    severity: str | None = None
    component: str | None = None
    status: str | None = None
    resolution_hours: float | None = None


class QueueRequest(BaseModel):
    incidents: list[IncidentItem] = Field(default_factory=list)


class PrioritizeRequest(BaseModel):
    incident: IncidentItem
    open_count: int = 0


class TrainRequest(BaseModel):
    incidents: list[IncidentItem] = Field(default_factory=list)


class SentimentRequest(BaseModel):
    text: str


@router.post("/triage/queue")
def triage_queue(req: QueueRequest) -> dict:
    items = [i.model_dump() for i in req.incidents]
    return {"queue": agent.queue(items)}


@router.post("/triage/prioritize")
def triage_prioritize(req: PrioritizeRequest) -> dict:
    return agent.prioritize(req.incident.model_dump(), req.open_count)


@router.post("/triage/train")
def triage_train(req: TrainRequest) -> dict:
    return agent.train([i.model_dump() for i in req.incidents])


@router.get("/triage/stats")
def triage_stats() -> dict:
    return agent.stats()


@router.post("/sentiment")
def analyze_sentiment(req: SentimentRequest) -> dict:
    return sentiment.analyze(req.text)
