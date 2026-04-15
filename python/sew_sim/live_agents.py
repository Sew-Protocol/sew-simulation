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


# ---------------------------------------------------------------------------
# Ethereum failure-mode agents (F1–F5)
# ---------------------------------------------------------------------------


class DisputeFloodAgent(LiveAgent):
    """
    F1 — Resolver liveness extraction.

    Opens *n* escrows and raises a dispute on each. With ThrottledResolverLive
    as the only resolver, disputes pile up and time out — liveness failure.
    """

    def __init__(
        self,
        agent_id: str,
        recipient_address: str,
        n: int = 10,
        token: str = "USDC",
        amount: int = 100,
        rng: random.Random | None = None,
    ):
        super().__init__(agent_id, rng)
        self.recipient_address = recipient_address
        self.n = n
        self.token = token
        self.amount = amount
        self._attack_count = 0
        self._pending_slot = 0
        self._escrows: list[dict | None] = [None] * n

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        for i, slot in enumerate(self._escrows):
            if slot is None:
                self._pending_slot = i
                return {
                    "action": "create_escrow",
                    "params": {
                        "token": self.token,
                        "to": self.recipient_address,
                        "amount": self.amount,
                    },
                }
        for slot in self._escrows:
            if slot is not None and not slot["disputed"]:
                self._attack_count += 1
                return {
                    "action": "raise_dispute",
                    "params": {"workflow_id": slot["workflow_id"]},
                }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "create_escrow" and entry.get("result") == "ok":
            wf = (entry.get("extra") or {}).get("workflow_id")
            if wf is not None:
                self._escrows[self._pending_slot] = {"workflow_id": wf, "disputed": False}
        if entry.get("action") == "raise_dispute" and entry.get("result") == "ok":
            wf = (entry.get("params") or {}).get("workflow_id")
            for slot in self._escrows:
                if slot and slot["workflow_id"] == wf:
                    slot["disputed"] = True
                    break


class ThrottledResolverLive(LiveAgent):
    """
    F1 — Throttled honest resolver.

    Resolves at most *throughput* disputes per tick. Models a multisig or
    DAO-controlled resolver whose execution rate is bandwidth-limited.
    """

    def __init__(
        self,
        agent_id: str,
        throughput: int = 2,
        rng: random.Random | None = None,
    ):
        super().__init__(agent_id, rng)
        self.throughput = throughput
        self._resolved: set[int] = set()
        self._this_tick = 0
        self._last_tick_seq = -1

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if world_view is None:
            return None
        if seq != self._last_tick_seq:
            self._this_tick = 0
            self._last_tick_seq = seq
        if self._this_tick >= self.throughput:
            return None
        live = world_view.get("live_states") or {}
        for wf_id_str, state in live.items():
            wf_id = int(wf_id_str)
            if state == "disputed" and wf_id not in self._resolved:
                self._this_tick += 1
                return {
                    "action": "execute_resolution",
                    "params": {
                        "workflow_id": wf_id,
                        "is_release": True,
                        "resolution_hash": "0xthrottled",
                    },
                }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "execute_resolution" and entry.get("result") == "ok":
            wf = (entry.get("params") or {}).get("workflow_id")
            if wf is not None:
                self._resolved.add(int(wf))


class AppealWindowRacerLive(LiveAgent):
    """
    F2 — Appeal deadline race / MEV front-run.

    Escalates immediately whenever a pending settlement exists, clearing it
    before the honest party can execute it.
    """

    def __init__(self, agent_id: str, rng: random.Random | None = None):
        super().__init__(agent_id, rng)
        self._attack_count = 0
        self._escalated: set[int] = set()

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if world_view is None:
            return None
        if world_view.get("pending_count", 0) == 0:
            return None
        live = world_view.get("live_states") or {}
        for wf_id_str, state in live.items():
            wf_id = int(wf_id_str)
            if state == "disputed" and wf_id not in self._escalated:
                self._attack_count += 1
                self._escalated.add(wf_id)
                return {
                    "action": "escalate_dispute",
                    "params": {"workflow_id": wf_id},
                }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)


class EscalationLoopAgent(LiveAgent):
    """
    F4 — Escalation loop amplification.

    Escalates whenever a pending settlement exists. Forces the honest resolver
    to re-submit at every level. After the S23 fix, cannot loop without a
    pending settlement — but still amplifies resolver gas costs.
    """

    def __init__(self, agent_id: str, rng: random.Random | None = None):
        super().__init__(agent_id, rng)
        self._attack_count = 0
        self._escalated_at: dict[int, int] = {}

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if world_view is None:
            return None
        if world_view.get("pending_count", 0) == 0:
            return None
        live = world_view.get("live_states") or {}
        for wf_id_str, state in live.items():
            wf_id = int(wf_id_str)
            if state == "disputed" and self._escalated_at.get(wf_id, -1) != seq:
                self._attack_count += 1
                self._escalated_at[wf_id] = seq
                return {
                    "action": "escalate_dispute",
                    "params": {"workflow_id": wf_id},
                }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)


class GovernanceRotationAgent(LiveAgent):
    """
    F3 — Governance sandwich.

    Calls rotate_dispute_resolver after timelock_ticks ticks from first seeing
    a disputed workflow, simulating a governance timelock delay.
    """

    def __init__(
        self,
        agent_id: str,
        new_resolver: str,
        timelock_ticks: int = 2,
        rng: random.Random | None = None,
    ):
        super().__init__(agent_id, rng)
        self.new_resolver = new_resolver
        self.timelock_ticks = timelock_ticks
        self._attack_count = 0
        self._dispute_tick: dict[int, int] = {}
        self._rotated: set[int] = set()
        self._pending_rotation_wf: int | None = None

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if world_view is None:
            return None
        live = world_view.get("live_states") or {}
        for wf_id_str, state in live.items():
            wf_id = int(wf_id_str)
            if state == "disputed":
                if wf_id not in self._dispute_tick:
                    self._dispute_tick[wf_id] = seq
                elif (
                    seq - self._dispute_tick[wf_id] >= self.timelock_ticks
                    and wf_id not in self._rotated
                ):
                    self._pending_rotation_wf = wf_id
                    return {
                        "action": "rotate_dispute_resolver",
                        "params": {
                            "workflow_id": wf_id,
                            "new_resolver": self.new_resolver,
                        },
                    }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if (
            entry.get("action") == "rotate_dispute_resolver"
            and entry.get("result") == "ok"
            and self._pending_rotation_wf is not None
        ):
            self._attack_count += 1
            self._rotated.add(self._pending_rotation_wf)
        self._pending_rotation_wf = None


class MaliciousGovernanceResolver(LiveAgent):
    """
    F3 — Resolves any disputed escrow after governance rotation installs it
    as the authorized resolver. Tracks successful malicious resolutions.
    """

    def __init__(
        self,
        agent_id: str,
        favour_release: bool = False,
        rng: random.Random | None = None,
    ):
        super().__init__(agent_id, rng)
        self.favour_release = favour_release
        self._attack_count = 0
        self._resolved: set[int] = set()
        self._pending_wf: int | None = None

    def decide(
        self, world_view: WorldView | None, seq: int, block_time: int
    ) -> Optional[dict]:
        if world_view is None:
            return None
        live = world_view.get("live_states") or {}
        for wf_id_str, state in live.items():
            wf_id = int(wf_id_str)
            if state == "disputed" and wf_id not in self._resolved:
                self._attack_count += 1
                self._pending_wf = wf_id
                return {
                    "action": "execute_resolution",
                    "params": {
                        "workflow_id": wf_id,
                        "is_release": self.favour_release,
                        "resolution_hash": "0xmalicious",
                    },
                }
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if (
            entry.get("action") == "execute_resolution"
            and entry.get("result") == "ok"
            and self._pending_wf is not None
        ):
            self._resolved.add(self._pending_wf)
        self._pending_wf = None
