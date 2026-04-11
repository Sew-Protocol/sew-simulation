"""
Reactive live agents for Phase 2 gRPC streaming adversarial simulation.

Unlike Phase 1 agents (which plan all actions ahead of time), live agents
receive world snapshots after each step and decide their next action based
on the current state.

Interface:
  decide(world_view, seq, time) → dict | None
    Return an event params dict (without seq/time — the runner fills those),
    or None to skip this agent's turn.

  update_from_response(response) → None
    Called after every step with the full gRPC response.

Authority model: Clojure is the sole source of truth.
  - Agents only see a world_view snapshot (lean read-only copy).
  - Agents never compute state transitions or check invariants.
  - Workflow IDs returned by create_escrow are read from trace_entry.extra.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Any, Optional


WorldView = dict[str, Any]
StepResponse = dict[str, Any]


# ---------------------------------------------------------------------------
# Base class
# ---------------------------------------------------------------------------


class LiveAgent:
    """
    Base class for reactive simulation agents.

    Subclasses must implement decide().  update_from_response() is optional
    (useful for agents that track their own open positions).
    """

    def __init__(self, agent_id: str, rng: random.Random | None = None):
        self.agent_id = agent_id
        self.rng = rng or random.Random()
        self.step_count = 0

    def decide(
        self,
        world_view: WorldView | None,
        seq: int,
        block_time: int,
    ) -> Optional[dict]:
        """
        Return an action event dict (without seq/time) or None to skip.

        The returned dict must include "action" and any required "params".
        Example::

          {"action": "create_escrow",
           "params": {"token": "USDC", "to": "0xrecipient", "amount": 500}}
        """
        raise NotImplementedError

    def update_from_response(self, response: StepResponse) -> None:
        """Called after every step (including rejected actions)."""
        self.step_count += 1


# ---------------------------------------------------------------------------
# Concrete agents
# ---------------------------------------------------------------------------


class HonestBuyerLive(LiveAgent):
    """
    Creates one escrow and releases it immediately after the next block.

    Tracks own open escrow via workflow_id from create_escrow response.
    """

    def __init__(
        self,
        agent_id: str,
        recipient_address: str,
        token: str = "USDC",
        amount: int = 1000,
        rng: random.Random | None = None,
    ):
        super().__init__(agent_id, rng)
        self.recipient_address = recipient_address
        self.token = token
        self.amount = amount
        self._workflow_id: Optional[int] = None
        self._created = False
        self._released = False

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if not self._created:
            return {
                "action": "create_escrow",
                "params": {
                    "token": self.token,
                    "to": self.recipient_address,
                    "amount": self.amount,
                },
            }
        if self._workflow_id is not None and not self._released:
            return {
                "action": "release",
                "params": {"workflow_id": self._workflow_id},
            }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "create_escrow" and entry.get("result") == "ok":
            extra = entry.get("extra") or {}
            self._workflow_id = extra.get("workflow_id")
            self._created = True
        if entry.get("action") == "release" and entry.get("result") == "ok":
            self._released = True


class HonestResolverLive(LiveAgent):
    """
    Resolves any disputed escrow it sees in the world_view as a favour-buyer release.
    """

    def __init__(self, agent_id: str, rng: random.Random | None = None):
        super().__init__(agent_id, rng)
        self._resolved: set[int] = set()

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if world_view is None:
            return None
        live = world_view.get("live_states") or {}
        for wf_id_str, state in live.items():
            wf_id = int(wf_id_str)
            if state == "disputed" and wf_id not in self._resolved:
                return {
                    "action": "execute_resolution",
                    "params": {
                        "workflow_id": wf_id,
                        "is_release": True,
                        "resolution_hash": "0xsimhash",
                    },
                }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "execute_resolution" and entry.get("result") == "ok":
            params = (entry.get("params") or {})
            wf = params.get("workflow_id")
            if wf is not None:
                self._resolved.add(int(wf))


@dataclass
class _GriefingState:
    """Mutable state for GriefingBuyerLive."""
    workflow_id: Optional[int] = None
    created: bool = False
    disputed: bool = False


class GriefingBuyerLive(LiveAgent):
    """
    Creates an escrow then immediately raises a dispute (griefing attack).
    Does not release or cooperate in settlement.
    """

    def __init__(
        self,
        agent_id: str,
        recipient_address: str,
        token: str = "USDC",
        amount: int = 500,
        rng: random.Random | None = None,
    ):
        super().__init__(agent_id, rng)
        self.recipient_address = recipient_address
        self.token = token
        self.amount = amount
        self._state = _GriefingState()

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if not self._state.created:
            return {
                "action": "create_escrow",
                "params": {
                    "token": self.token,
                    "to": self.recipient_address,
                    "amount": self.amount,
                },
            }
        if self._state.workflow_id is not None and not self._state.disputed:
            return {
                "action": "raise_dispute",
                "params": {"workflow_id": self._state.workflow_id},
            }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        action = entry.get("action")
        result = entry.get("result")
        if action == "create_escrow" and result == "ok":
            extra = entry.get("extra") or {}
            self._state.workflow_id = extra.get("workflow_id")
            self._state.created = True
        if action == "raise_dispute" and result == "ok":
            self._state.disputed = True


class AttackingBuyerLive(LiveAgent):
    """
    Attempts to double-release or double-dispute by replaying the same workflow_id.
    All attempts are expected to be rejected by Clojure — attack_successes in
    metrics should remain 0.
    """

    def __init__(
        self,
        agent_id: str,
        recipient_address: str,
        token: str = "USDC",
        amount: int = 100,
        rng: random.Random | None = None,
    ):
        super().__init__(agent_id, rng)
        self.recipient_address = recipient_address
        self.token = token
        self.amount = amount
        self._workflow_id: Optional[int] = None
        self._created = False
        self._attack_count = 0

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if not self._created:
            return {
                "action": "create_escrow",
                "params": {
                    "token": self.token,
                    "to": self.recipient_address,
                    "amount": self.amount,
                },
            }
        if self._workflow_id is not None and self._attack_count < 3:
            # Repeatedly attempt to release an already-released escrow
            self._attack_count += 1
            return {
                "action": "release",
                "params": {"workflow_id": self._workflow_id},
            }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "create_escrow" and entry.get("result") == "ok":
            extra = entry.get("extra") or {}
            self._workflow_id = extra.get("workflow_id")
            self._created = True


class TimedAutomatorLive(LiveAgent):
    """
    Calls automate_timed_actions for any pending or disputed workflow it observes.
    Used to trigger time-based resolutions (e.g. auto-cancel after max-dispute-duration).
    """

    def __init__(self, agent_id: str, rng: random.Random | None = None):
        super().__init__(agent_id, rng)
        self._attempted: set[int] = set()

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if world_view is None:
            return None
        live = world_view.get("live_states") or {}
        for wf_id_str, state in live.items():
            wf_id = int(wf_id_str)
            if state in ("pending", "disputed") and wf_id not in self._attempted:
                self._attempted.add(wf_id)
                return {
                    "action": "automate_timed_actions",
                    "params": {"workflow_id": wf_id},
                }
        return None
