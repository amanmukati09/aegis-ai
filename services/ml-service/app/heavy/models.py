"""Heavy-ML capability registry (install-to-activate).

Each capability declares the import it needs. `available()` returns True only when that
dependency is importable, so the /v1/heavy/status endpoint reports the real state without
ever hard-failing the service when the deps are absent (the default on the core box).

Activation (laptop): pip install -r requirements-heavy.txt, then implement run() using the
now-available library. The stubs below intentionally raise NotImplemented until then.
"""
from __future__ import annotations

import importlib.util


def _installed(module: str) -> bool:
    return importlib.util.find_spec(module) is not None


class HeavyCapability:
    key: str = ""
    label: str = ""
    requires: tuple[str, ...] = ()

    def available(self) -> bool:
        return all(_installed(m) for m in self.requires)

    def describe(self) -> dict:
        return {
            "key": self.key,
            "label": self.label,
            "requires": list(self.requires),
            "available": self.available(),
        }

    def run(self, payload: dict) -> dict:  # pragma: no cover - activated on laptop
        raise NotImplementedError(
            f"{self.label} is install-to-activate. Install {', '.join(self.requires)} "
            f"and implement run(); see LAPTOP_SETUP.md."
        )


class MtadGatDetector(HeavyCapability):
    """Multivariate time-series anomaly detection (MTAD-GAT, graph-attention)."""
    key = "mtad_gat"
    label = "MTAD-GAT multivariate anomaly detection"
    requires = ("torch",)


class PcmciCausal(HeavyCapability):
    """Causal discovery over time series (PCMCI, tigramite)."""
    key = "pcmci"
    label = "PCMCI causal discovery"
    requires = ("tigramite",)


class DqnTriage(HeavyCapability):
    """Deep-Q triage policy — the neural upgrade to the tabular Q-learning agent."""
    key = "dqn"
    label = "DQN triage policy (upgrade from tabular Q-learning)"
    requires = ("torch",)


REGISTRY: list[HeavyCapability] = [MtadGatDetector(), PcmciCausal(), DqnTriage()]


def status() -> dict:
    caps = [c.describe() for c in REGISTRY]
    return {
        "mode": "extended" if any(c["available"] for c in caps) else "core",
        "capabilities": caps,
    }
