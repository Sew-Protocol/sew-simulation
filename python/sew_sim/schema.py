"""
schema.py — Pydantic v2 models for the SEW scenario v1 format.

These mirror the JSON Schema in schemas/scenario-v1.json.  Pydantic is the
single source of truth for Python; the JSON Schema is generated from it.

Design decisions:
  - snake_case fields (standard Python) map 1:1 to JSON snake_case keys.
  - The Clojure replay engine converts snake_case → kebab-case on load.
  - Addresses are plain strings (no checksum validation at the schema level).
  - workflow_id in params is an int predicted by the generator (matches the
    Clojure world's count-of-escrow-transfers assignment rule).
"""

from __future__ import annotations

import uuid
from enum import Enum
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field, model_validator


# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------


class AgentType(str, Enum):
    honest = "honest"
    attacker = "attacker"
    opportunistic = "opportunistic"


class ActionName(str, Enum):
    create_escrow = "create_escrow"
    raise_dispute = "raise_dispute"
    execute_resolution = "execute_resolution"
    execute_pending_settlement = "execute_pending_settlement"
    automate_timed_actions = "automate_timed_actions"
    release = "release"
    sender_cancel = "sender_cancel"
    recipient_cancel = "recipient_cancel"
    auto_cancel_disputed = "auto_cancel_disputed"
    advance_time = "advance_time"


# ---------------------------------------------------------------------------
# Agent
# ---------------------------------------------------------------------------


class Agent(BaseModel):
    id: str
    type: AgentType
    address: str

    model_config = {"frozen": True}


# ---------------------------------------------------------------------------
# Protocol parameters
# ---------------------------------------------------------------------------


class ProtocolParams(BaseModel):
    resolver_fee_bps: int = Field(default=50, ge=0, le=10000)
    appeal_window_duration: int = Field(default=0, ge=0)
    max_dispute_duration: int = Field(default=2_592_000, ge=0)
    appeal_bond_protocol_fee_bps: int = Field(default=0, ge=0, le=10000)

    model_config = {"frozen": True}


# ---------------------------------------------------------------------------
# Event params (typed per action)
# ---------------------------------------------------------------------------


class CreateEscrowParams(BaseModel):
    token: str
    to: str                             # literal address, not agent id
    amount: int = Field(ge=1)
    custom_resolver: Optional[str] = None

    model_config = {"frozen": True}


class WorkflowParams(BaseModel):
    """Params for actions that target an existing escrow."""
    workflow_id: int = Field(ge=0)

    model_config = {"frozen": True}


class ExecuteResolutionParams(BaseModel):
    workflow_id: int = Field(ge=0)
    is_release: bool = True
    resolution_hash: str = "0xsimhash"

    model_config = {"frozen": True}


# ---------------------------------------------------------------------------
# Event
# ---------------------------------------------------------------------------


class Event(BaseModel):
    seq: int = Field(ge=0)
    time: int = Field(ge=0)
    agent: str
    action: ActionName
    params: dict[str, Any] = Field(default_factory=dict)

    model_config = {"frozen": True}

    def is_attacker_event(self, agent_index: dict[str, Agent]) -> bool:
        agent = agent_index.get(self.agent)
        return agent is not None and agent.type == AgentType.attacker


# ---------------------------------------------------------------------------
# Scenario (root document)
# ---------------------------------------------------------------------------


class Scenario(BaseModel):
    schema_version: Literal["1.0"] = "1.0"
    scenario_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    seed: int
    agents: list[Agent]
    protocol_params: ProtocolParams = Field(default_factory=ProtocolParams)
    initial_block_time: int = Field(default=1000, ge=0)
    events: list[Event]

    model_config = {"frozen": True}

    @model_validator(mode="after")
    def validate_agent_references(self) -> "Scenario":
        known = {a.id for a in self.agents}
        for ev in self.events:
            if ev.agent not in known:
                raise ValueError(
                    f"Event seq={ev.seq} references unknown agent '{ev.agent}'. "
                    f"Known agents: {sorted(known)}"
                )
        return self

    @model_validator(mode="after")
    def validate_event_ordering(self) -> "Scenario":
        seqs = [ev.seq for ev in self.events]
        if seqs != list(range(len(seqs))):
            raise ValueError(
                "Event seq values must be monotonically increasing and contiguous "
                f"(expected {list(range(len(seqs)))}, got {seqs})."
            )
        times = [ev.time for ev in self.events]
        if any(t < self.initial_block_time for t in times):
            raise ValueError(
                f"All event times must be >= initial_block_time={self.initial_block_time}."
            )
        if times != sorted(times):
            raise ValueError("Event times must be monotonically non-decreasing.")
        return self

    def agent_index(self) -> dict[str, Agent]:
        return {a.id: a for a in self.agents}

    def to_json(self, **kwargs: Any) -> str:
        return self.model_dump_json(**kwargs)

    def to_dict(self) -> dict[str, Any]:
        return self.model_dump()
