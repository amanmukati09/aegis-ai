"""Reinforcement-learning incident triage (tabular Q-learning).

A lightweight, dependency-light agent that learns an incident prioritization
policy from resolution outcomes.

- State  = (severity bucket, component bucket, hour-of-day bucket, workload bucket)
- Action = priority level 1..5
- Reward = fast resolution of high-severity incidents scores high; slow resolution
  is penalized.

Before it has learned anything for a state it falls back to a sensible rule
(priority tracks severity), so it is useful from the first request. The Q-table is
persisted to disk so learning survives restarts.

This is intentionally a *tabular* agent: no torch, tiny memory footprint, safe on the
3.8 GB EC2 box. The interface (prioritize / learn / queue) is DQN-ready — a neural
policy can be dropped in later behind the same methods (see LAPTOP_SETUP).
"""
from __future__ import annotations

import os
import pickle
import threading
from collections import defaultdict
from datetime import datetime, timezone

import numpy as np

N_ACTIONS = 5  # priority levels 1..5

_SEVERITY_MAP = {"critical": 4, "high": 3, "medium": 2, "low": 1, "unknown": 0}
_COMPONENT_MAP = {
    "database": 0, "nginx": 1, "redis": 2, "api-gateway": 3, "gateway": 3,
    "system": 4, "auth": 5, "payment": 6, "network": 7,
}
_DEFAULT_COMPONENT = 8  # "other"
_N_COMPONENTS = 9

_DATA_DIR = os.getenv("ML_DATA_DIR", "/app/data")
_MODEL_PATH = os.path.join(_DATA_DIR, "rl_triage_qtable.pkl")


def _zeros() -> np.ndarray:
    return np.zeros(N_ACTIONS, dtype=float)


class RLTriageAgent:
    def __init__(self) -> None:
        self.q_table: dict[tuple, np.ndarray] = defaultdict(_zeros)
        self.learning_rate = 0.1
        self.discount_factor = 0.95
        self.epsilon = 0.15  # exploration rate
        self._lock = threading.Lock()
        self._load()

    # ---- state encoding -------------------------------------------------
    def _severity_idx(self, severity: str | None) -> int:
        return _SEVERITY_MAP.get((severity or "medium").lower(), 2)

    def _component_idx(self, component: str | None) -> int:
        return _COMPONENT_MAP.get((component or "").lower(), _DEFAULT_COMPONENT)

    def _state(self, incident: dict, open_count: int) -> tuple:
        sev = self._severity_idx(incident.get("severity"))
        comp = self._component_idx(incident.get("component"))
        hour = datetime.now(timezone.utc).hour // 4  # 0..5 (4-hour buckets)
        load = min(open_count // 5, 4)               # 0..4 workload buckets
        return (sev, comp, hour, load)

    # ---- inference ------------------------------------------------------
    def prioritize(self, incident: dict, open_count: int) -> dict:
        state = self._state(incident, open_count)
        with self._lock:
            q = self.q_table.get(state)
            learned = q is not None and float(np.sum(np.abs(q))) > 0.0

        if learned:
            explore = np.random.random() < self.epsilon
            action = int(np.random.randint(0, N_ACTIONS)) if explore else int(np.argmax(q))
            policy = "explore" if explore else "exploit"
            q_vals = [float(v) for v in q]
            max_q, sum_q = float(np.max(q)), float(np.sum(np.abs(q)))
            confidence = round(max_q / max(sum_q, 1e-9) * 100, 1)
        else:
            # Cold start: priority follows severity.
            action = min(self._severity_idx(incident.get("severity")), N_ACTIONS - 1)
            policy = "rule-based"
            q_vals = [0.0] * N_ACTIONS
            confidence = 0.0

        return {
            "incident_id": incident.get("id"),
            "priority": int(action) + 1,
            "state": [int(s) for s in state],
            "q_values": q_vals,
            "policy": policy,
            "confidence": confidence,
        }

    def queue(self, incidents: list[dict]) -> list[dict]:
        """Return incidents ranked by learned priority (highest first)."""
        open_count = sum(1 for i in incidents if (i.get("status") or "").lower() == "open")
        ranked = [{**inc, **self.prioritize(inc, open_count)} for inc in incidents]
        ranked.sort(key=lambda x: (x["priority"], x["confidence"]), reverse=True)
        return ranked

    # ---- learning -------------------------------------------------------
    def learn(self, incident: dict, open_count: int, resolution_hours: float) -> None:
        state = self._state(incident, open_count)
        sev = self._severity_idx(incident.get("severity"))
        reward = self._reward(sev, resolution_hours)
        with self._lock:
            q = self.q_table[state]
            action = int(np.argmax(q))
            next_max = float(np.max(q))
            q[action] += self.learning_rate * (reward + self.discount_factor * next_max - q[action])

    def train(self, incidents: list[dict]) -> dict:
        n = 0
        open_count = max(len(incidents), 1)
        for inc in incidents:
            hours = inc.get("resolution_hours")
            if hours is not None and hours > 0:
                self.learn(inc, open_count, float(hours))
                n += 1
        self._save()
        return {"trained_on": n, "q_states": len(self.q_table)}

    @staticmethod
    def _reward(severity_idx: int, hours: float) -> float:
        if hours < 1:
            return 10.0 * severity_idx
        if hours < 4:
            return 5.0 * severity_idx
        if hours < 24:
            return 2.0 * severity_idx
        return -5.0

    # ---- persistence ----------------------------------------------------
    def _save(self) -> None:
        try:
            os.makedirs(_DATA_DIR, exist_ok=True)
            with self._lock, open(_MODEL_PATH, "wb") as f:
                pickle.dump({k: v.tolist() for k, v in self.q_table.items()}, f)
        except OSError:
            pass  # persistence is best-effort

    def _load(self) -> None:
        if not os.path.exists(_MODEL_PATH):
            return
        try:
            with open(_MODEL_PATH, "rb") as f:
                raw = pickle.load(f)
            self.q_table = defaultdict(_zeros, {k: np.asarray(v, dtype=float) for k, v in raw.items()})
        except (OSError, pickle.PickleError, ValueError):
            self.q_table = defaultdict(_zeros)

    def stats(self) -> dict:
        with self._lock:
            return {"q_states": len(self.q_table), "epsilon": self.epsilon}


agent = RLTriageAgent()
