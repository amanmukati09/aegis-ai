"""Feature enum and FeatureResolver: the precedence-chain logic for mapping a
Feature + org to an ordered list of candidate (Provider_Configuration,
Model_Entry) pairs.

Introduced by task 10.1 (ai-provider-flexibility spec). Per design.md's
"Feature Resolution (precedence chain)" section and Property 15, resolution
checks five levels in order and returns the full Priority_List for the first
level that is defined:

    1. explicit per-request override (req.provider/req.model, back-compat)
    2. Feature_Model_Assignment for (org, feature), if present
    3. Organization's Global_Default, if present
    4. platform-wide (org_id IS NULL) Global_Default / Priority_List
    5. GROQ_API_KEY env var fallback (if no platform-wide row exists yet)

Each level returns a *list* of ResolvedPair (a Priority_List), not a single
pair, so a later FailoverEngine (task 12.1) still has failover candidates
even when the resolution came from a Global_Default rather than an explicit
per-pair Priority_List.

Like `AdapterRegistry` (task 9.1), this module is pure, dependency-free logic
over an in-memory snapshot supplied at construction time -- it does not talk
to Postgres or HTTP itself. The actual snapshot-fetching/polling from
Gateway's `/api/internal/providers/resolved` endpoint is task 14.4, out of
scope here.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum

from ..config import settings


class Feature(str, Enum):
    """The 10 named Features (Req 5.4). String values match exactly what
    Gateway's FeatureAssignmentService.VALID_FEATURES sends in snapshot
    payloads' feature_model_assignments rows."""

    COPILOT_CHAT = "copilot_chat"
    DIAGNOSIS = "diagnosis"
    REMEDIATION = "remediation"
    RUNBOOK = "runbook"
    KB_EXTRACTION = "kb_extraction"
    NL_TO_SQL = "nl_to_sql"
    TRIAGE = "triage"
    SENTIMENT = "sentiment"
    CODE_FIX = "code_fix"
    RCA_TREE = "rca_tree"


# Sentinel used for the synthetic pair produced by the level-5 (GROQ_API_KEY
# env var) fallback, since no real provider_configurations/model_entries rows
# exist for it.
_ENV_FALLBACK_ID = "env_fallback"


@dataclass(frozen=True)
class ResolvedPair:
    """One candidate (Provider_Configuration, Model_Entry) pair, with the
    credentials/connection settings needed to actually invoke it. `pair_key`
    matches design.md's `CircuitState.pair_key` format
    (f"{provider_configuration_id}:{model_entry_id}") so a later
    FailoverEngine (task 12.1) can use ResolvedPair instances directly as
    circuit-breaker keys.

    `credentials` is treated as already in final usable form for this task --
    decrypting ciphertext received from Gateway is task 14.4's concern, not
    this one. The resolver just passes through whatever is in the snapshot.
    """

    provider_configuration_id: str
    model_entry_id: str
    provider_type: str
    model_id: str
    credentials: dict[str, str] = field(default_factory=dict)
    connection_settings: dict[str, str] = field(default_factory=dict)

    @property
    def pair_key(self) -> str:
        return f"{self.provider_configuration_id}:{self.model_entry_id}"


# ---------------------------------------------------------------------------
# Snapshot input shape
#
# These dataclasses represent the subset of the Gateway snapshot (shaped like
# the V8 migration's tables) that FeatureResolver actually reads. Kept flat
# and minimal -- this only needs to support `FeatureResolver.resolve(...)`'s
# lookups, not be a general-purpose ORM.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ProviderConfigurationRow:
    """Mirrors `provider_configurations`. `org_id` is None for platform-wide
    (org-null) rows."""

    id: str
    org_id: str | None
    provider_type: str
    enabled: bool
    credentials: dict[str, str] = field(default_factory=dict)
    connection_settings: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class ModelEntryRow:
    """Mirrors `model_entries`."""

    id: str
    provider_configuration_id: str
    model_id: str


@dataclass(frozen=True)
class AssignedPair:
    """A bare (Provider_Configuration, Model_Entry) id pair, as stored by
    `global_default_assignments`, `platform_default_assignment`, and
    `feature_model_assignments`."""

    provider_configuration_id: str
    model_entry_id: str


@dataclass
class ProviderSnapshot:
    """Plain-data snapshot of the subset of Gateway's provider-configuration
    tables FeatureResolver needs. Supplied at `FeatureResolver` construction
    time -- this class does not fetch anything itself.

    - `provider_configurations`: keyed by id.
    - `model_entries`: keyed by id.
    - `global_default_assignments`: keyed by org_id.
    - `platform_default_assignment`: the singleton row, or None if not yet
      configured.
    - `feature_model_assignments`: keyed by (org_id, feature value).
    - `priority_list_entries`: keyed by (org_id_or_None, feature_or_None),
      each value an ordered list of AssignedPair (already sorted by
      priority_order).
    """

    provider_configurations: dict[str, ProviderConfigurationRow] = field(default_factory=dict)
    model_entries: dict[str, ModelEntryRow] = field(default_factory=dict)
    global_default_assignments: dict[str, AssignedPair] = field(default_factory=dict)
    platform_default_assignment: AssignedPair | None = None
    feature_model_assignments: dict[tuple[str, str], AssignedPair] = field(default_factory=dict)
    priority_list_entries: dict[tuple[str | None, str | None], list[AssignedPair]] = field(
        default_factory=dict
    )


class FeatureResolver:
    """Implements the 5-level precedence chain (design.md, Property 15) over
    an in-memory `ProviderSnapshot`. Pure logic, no network/DB access."""

    def __init__(self, snapshot: ProviderSnapshot) -> None:
        self._snapshot = snapshot

    # -- public API ---------------------------------------------------

    def resolve(
        self,
        feature: Feature,
        org_id: str | None,
        explicit_override: tuple[str, str] | None = None,
    ) -> list[ResolvedPair]:
        """Returns the Priority_List (ordered candidates) for one feature+org,
        checking each precedence level in order and returning the full
        Priority_List for the first level that is defined. Returns an empty
        list if nothing resolves at any level -- it is up to the caller
        (a future FailoverEngine) to decide how to surface that."""

        # Level 1: explicit per-request override.
        if explicit_override is not None:
            pair = self._resolve_explicit_override(explicit_override, org_id)
            if pair is not None:
                return [pair]
            # No matching enabled configuration/model for this org -- fall
            # through to level 2 rather than silently failing.

        # Level 2: Feature_Model_Assignment for (org, feature).
        if org_id is not None:
            assignment = self._snapshot.feature_model_assignments.get((org_id, feature.value))
            if assignment is not None:
                priority_list = self._snapshot.priority_list_entries.get((org_id, feature.value))
                if priority_list:
                    resolved = self._resolve_many(priority_list)
                    if resolved:
                        return resolved
                resolved_single = self._resolve_pair(assignment)
                if resolved_single is not None:
                    return [resolved_single]

        # Level 3: organization's Global_Default.
        if org_id is not None:
            org_default = self._snapshot.global_default_assignments.get(org_id)
            if org_default is not None:
                global_priority_list = self._snapshot.priority_list_entries.get((org_id, None))
                if global_priority_list:
                    resolved = self._resolve_many(global_priority_list)
                    if resolved:
                        return resolved
                resolved_single = self._resolve_pair(org_default)
                if resolved_single is not None:
                    return [resolved_single]

        # Level 4: platform-wide default.
        platform_default = self._snapshot.platform_default_assignment
        if platform_default is not None:
            # Prefer a platform-wide + feature-specific Priority_List, then a
            # platform-wide + global one, in that preference order.
            platform_feature_list = self._snapshot.priority_list_entries.get((None, feature.value))
            if platform_feature_list:
                resolved = self._resolve_many(platform_feature_list)
                if resolved:
                    return resolved
            platform_global_list = self._snapshot.priority_list_entries.get((None, None))
            if platform_global_list:
                resolved = self._resolve_many(platform_global_list)
                if resolved:
                    return resolved
            resolved_single = self._resolve_pair(platform_default)
            if resolved_single is not None:
                return [resolved_single]

        # Level 5: GROQ_API_KEY env var fallback. Only applies if no
        # platform-wide row exists yet (Req 10.1).
        if platform_default is None and settings.groq_api_key:
            return [
                ResolvedPair(
                    provider_configuration_id=_ENV_FALLBACK_ID,
                    model_entry_id=_ENV_FALLBACK_ID,
                    provider_type="groq",
                    model_id=settings.default_model,
                    credentials={"api_key": settings.groq_api_key},
                    connection_settings={},
                )
            ]

        return []

    # -- internal helpers -----------------------------------------------

    def _resolve_explicit_override(
        self, explicit_override: tuple[str, str], org_id: str | None
    ) -> ResolvedPair | None:
        """Resolves an (provider_type, model_id) override to a single
        matching ResolvedPair from the snapshot's provider_configurations/
        model_entries for this org. Returns None if no matching enabled
        configuration/model exists for that org."""
        provider_type, model_id = explicit_override
        for config in self._snapshot.provider_configurations.values():
            if not config.enabled:
                continue
            if config.provider_type != provider_type:
                continue
            if config.org_id != org_id:
                continue
            for model in self._snapshot.model_entries.values():
                if model.provider_configuration_id != config.id:
                    continue
                if model.model_id != model_id:
                    continue
                return self._to_resolved_pair(config, model)
        return None

    def _resolve_pair(self, assigned: AssignedPair) -> ResolvedPair | None:
        """Looks up a provider_configuration + model_entry by id and builds a
        ResolvedPair. Returns None if either row is missing or the
        configuration is disabled."""
        config = self._snapshot.provider_configurations.get(assigned.provider_configuration_id)
        if config is None or not config.enabled:
            return None
        model = self._snapshot.model_entries.get(assigned.model_entry_id)
        if model is None:
            return None
        return self._to_resolved_pair(config, model)

    def _resolve_many(self, assigned_pairs: list[AssignedPair]) -> list[ResolvedPair]:
        resolved: list[ResolvedPair] = []
        for assigned in assigned_pairs:
            pair = self._resolve_pair(assigned)
            if pair is not None:
                resolved.append(pair)
        return resolved

    @staticmethod
    def _to_resolved_pair(config: ProviderConfigurationRow, model: ModelEntryRow) -> ResolvedPair:
        return ResolvedPair(
            provider_configuration_id=config.id,
            model_entry_id=model.id,
            provider_type=config.provider_type,
            model_id=model.model_id,
            credentials=config.credentials,
            connection_settings=config.connection_settings,
        )
