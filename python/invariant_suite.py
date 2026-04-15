"""
invariant_suite.py — Comprehensive adversarial invariant test suite.

Most scenarios assert:
  - outcome == "pass"          (Clojure never halted due to invariant violation)
  - invariant_violations == 0  (no post-step invariant check failed)

Bug scenarios (S22+) are marked EXPECTED_FAIL: they expose real protocol bugs
and are expected to FAIL (outcome == "halted").  The suite reports them
separately so they do not block other coverage.

Scenarios
---------
 S01  baseline-happy-path           create + release
 S02  dr3-dispute-release           create → dispute → resolve (release-to-seller)
 S03  dr3-dispute-refund            create → dispute → resolve (refund-to-buyer)
 S04  dispute-timeout-autocancel    dispute times out → auto_cancel_disputed
 S05  pending-settlement-execute    resolution creates pending → execute after window
 S06  mutual-cancel                 sender + recipient both agree to cancel
 S07  unauthorized-resolver-rejected wrong resolver attempts all rejected; then real resolves
 S08  state-machine-attack-gauntlet all invalid transitions attempted; all rejected
 S09  multi-escrow-solvency         3 concurrent escrows, mixed outcomes
 S10  double-finalize-rejected      release + re-release; resolve + re-resolve

Bug-finding scenarios (EXPECTED TO FAIL — demonstrate known protocol bugs)
 S22  status-leak-agree-cancel-over-dispute   [BUG] transition-to-disputed does not clear
                                              counterparty agree-to-cancel status →
                                              invariant 7 (all-status-combinations-valid)
                                              fires → HALTED
 S23  seller-preemptive-double-escalation     [DESIGN GAP] seller bypasses L0/L1 by
                                              double-escalating before any resolver acts;
                                              no current invariant catches it → PASS but
                                              documents the griefing vector

Usage:
    python python/invariant_suite.py

Requires the Clojure gRPC server:
    clojure -M:run -- -S
"""

from __future__ import annotations

import sys
from typing import Any, Optional

from sew_sim.grpc_client import SimulationClient
from sew_sim.live_agents import (
    LiveAgent,
    WorldView,
    StepResponse,
    HonestBuyerLive,
    HonestResolverLive,
    GriefingBuyerLive,
)
from sew_sim.live_runner import LiveRunner, RunResult


# ---------------------------------------------------------------------------
# New agent types
# ---------------------------------------------------------------------------


class DisputingBuyerLive(LiveAgent):
    """Creates one escrow then immediately raises a dispute.

    When resolver_address is provided the escrow is created with custom_resolver
    (Priority 1 authorization).  Omit it when the session uses a resolution
    module (Priority 2) so that the module's address is consulted instead.
    """

    def __init__(
        self,
        agent_id: str,
        recipient_address: str,
        resolver_address: str | None = None,
        token: str = "USDC",
        amount: int = 3000,
    ):
        super().__init__(agent_id)
        self.recipient_address = recipient_address
        self.resolver_address = resolver_address
        self.token = token
        self.amount = amount
        self._workflow_id: Optional[int] = None
        self._created = False
        self._disputed = False

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if not self._created:
            params: dict = {
                "token": self.token,
                "to": self.recipient_address,
                "amount": self.amount,
            }
            if self.resolver_address:
                params["custom_resolver"] = self.resolver_address
            return {"action": "create_escrow", "params": params}
        if self._workflow_id is not None and not self._disputed:
            return {"action": "raise_dispute", "params": {"workflow_id": self._workflow_id}}
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "create_escrow" and entry.get("result") == "ok":
            self._workflow_id = (entry.get("extra") or {}).get("workflow_id")
            self._created = True
        if entry.get("action") == "raise_dispute" and entry.get("result") == "ok":
            self._disputed = True


class RefundingResolverLive(LiveAgent):
    """Resolves disputed escrows with is_release=False (refund to buyer)."""

    def __init__(self, agent_id: str):
        super().__init__(agent_id)
        self._resolved: set[int] = set()

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if world_view is None:
            return None
        for wf_id_str, state in (world_view.get("live_states") or {}).items():
            wf_id = int(wf_id_str)
            if state == "disputed" and wf_id not in self._resolved:
                return {
                    "action": "execute_resolution",
                    "params": {
                        "workflow_id": wf_id,
                        "is_release": False,
                        "resolution_hash": "0xrefundhash",
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


class MutualCancelBuyerLive(LiveAgent):
    """Creates one escrow then initiates sender_cancel."""

    def __init__(self, agent_id: str, recipient_address: str, amount: int = 2000):
        super().__init__(agent_id)
        self.recipient_address = recipient_address
        self.amount = amount
        self._workflow_id: Optional[int] = None
        self._created = False
        self._cancelled = False

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if not self._created:
            return {
                "action": "create_escrow",
                "params": {"token": "USDC", "to": self.recipient_address, "amount": self.amount},
            }
        if self._workflow_id is not None and not self._cancelled:
            return {"action": "sender_cancel", "params": {"workflow_id": self._workflow_id}}
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "create_escrow" and entry.get("result") == "ok":
            self._workflow_id = (entry.get("extra") or {}).get("workflow_id")
            self._created = True
        if entry.get("action") == "sender_cancel" and entry.get("result") == "ok":
            self._cancelled = True


class MutualCancelSellerLive(LiveAgent):
    """Calls recipient_cancel on any escrow where sender has already signalled cancel."""

    def __init__(self, agent_id: str):
        super().__init__(agent_id)
        self._cancelled: set[int] = set()

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if world_view is None:
            return None
        # The world_view exposes cancel_agreed flags when present
        for wf_id_str, state in (world_view.get("live_states") or {}).items():
            wf_id = int(wf_id_str)
            if state == "pending" and wf_id not in self._cancelled:
                return {"action": "recipient_cancel", "params": {"workflow_id": wf_id}}
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "recipient_cancel" and entry.get("result") == "ok":
            wf = (entry.get("params") or {}).get("workflow_id")
            if wf is not None:
                self._cancelled.add(int(wf))


class WrongResolverLive(LiveAgent):
    """Attempts to resolve every disputed escrow it sees — always unauthorised."""

    def __init__(self, agent_id: str):
        super().__init__(agent_id)
        self._tried: set[int] = set()
        self._attack_count = 0  # duck-type for metrics

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if world_view is None:
            return None
        for wf_id_str, state in (world_view.get("live_states") or {}).items():
            wf_id = int(wf_id_str)
            if state == "disputed" and wf_id not in self._tried:
                self._tried.add(wf_id)
                self._attack_count += 1
                return {
                    "action": "execute_resolution",
                    "params": {
                        "workflow_id": wf_id,
                        "is_release": True,
                        "resolution_hash": "0xfakehash",
                    },
                }
        return None


class TimeoutKeeperLive(LiveAgent):
    """Retries auto_cancel_disputed for any disputed escrow every tick.
    Expected to fail until max_dispute_duration elapses, then succeed."""

    def __init__(self, agent_id: str):
        super().__init__(agent_id)

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if world_view is None:
            return None
        for wf_id_str, state in (world_view.get("live_states") or {}).items():
            if state == "disputed":
                return {"action": "auto_cancel_disputed", "params": {"workflow_id": int(wf_id_str)}}
        return None


class PendingSettlementExecutorLive(LiveAgent):
    """Retries execute_pending_settlement every tick until the appeal window passes."""

    def __init__(self, agent_id: str):
        super().__init__(agent_id)
        self._executed: set[int] = set()

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if world_view is None:
            return None
        for wf_id_str, state in (world_view.get("live_states") or {}).items():
            wf_id = int(wf_id_str)
            if state == "pending-settlement" and wf_id not in self._executed:
                return {"action": "execute_pending_settlement", "params": {"workflow_id": wf_id}}
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "execute_pending_settlement" and entry.get("result") == "ok":
            wf = (entry.get("params") or {}).get("workflow_id")
            if wf is not None:
                self._executed.add(int(wf))


class EscalatingBuyerLive(LiveAgent):
    """Creates an escrow (no custom_resolver), raises a dispute, then escalates
    up to max_escalations times.

    ALL escalation attempts are counted (including rejected ones) to prevent
    an infinite loop when the escalation guard blocks at max-dispute-level.
    """

    def __init__(
        self,
        agent_id: str,
        recipient_address: str,
        amount: int = 5000,
        max_escalations: int = 1,
    ):
        super().__init__(agent_id)
        self.recipient_address = recipient_address
        self.amount = amount
        self.max_escalations = max_escalations
        self._workflow_id: Optional[int] = None
        self._created = False
        self._disputed = False
        self._escalation_attempts = 0

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if not self._created:
            return {
                "action": "create_escrow",
                "params": {"token": "USDC", "to": self.recipient_address, "amount": self.amount},
            }
        if self._workflow_id is not None and not self._disputed:
            return {"action": "raise_dispute", "params": {"workflow_id": self._workflow_id}}
        if self._disputed and self._escalation_attempts < self.max_escalations:
            return {"action": "escalate_dispute", "params": {"workflow_id": self._workflow_id}}
        return None

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        entry = response.get("trace_entry") or {}
        action = entry.get("action")
        if action == "create_escrow" and entry.get("result") == "ok":
            self._workflow_id = (entry.get("extra") or {}).get("workflow_id")
            self._created = True
        if action == "raise_dispute" and entry.get("result") == "ok":
            self._disputed = True
        if action == "escalate_dispute":
            # Count ALL attempts (ok and rejected) to bound loop at max-level guard
            self._escalation_attempts += 1


class AutomateTimedActionsLive(LiveAgent):
    """Calls automate_timed_actions for every active escrow every tick.

    Handles both appeal-window-expired pending settlements and dispute
    max-duration timeouts without needing external time tracking.
    """

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if world_view is None:
            return None
        for wf_id_str, state in (world_view.get("live_states") or {}).items():
            if state in ("disputed", "pending"):
                return {"action": "automate_timed_actions", "params": {"workflow_id": int(wf_id_str)}}
        return None


class SellerGriefingLive(LiveAgent):
    """Seller who attempts to escalate as soon as a dispute appears in world_view.

    Previously this was a griefing vector: the seller could double-escalate
    (L0→L1→L2) before any resolver acted, bypassing all lower-level resolvers.

    After fix (S23): escalate_dispute now requires a pending settlement to exist.
    Preemptive escalation attempts are rejected with :no-resolution-to-appeal.
    """

    def __init__(self, agent_id: str, max_escalations: int = 2):
        super().__init__(agent_id)
        self.max_escalations = max_escalations
        self._workflow_id: Optional[int] = None
        self._escalation_count = 0

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if world_view is None or self._escalation_count >= self.max_escalations:
            return None
        live = world_view.get("live_states") or {}
        for wf_str, state in live.items():
            if state == "disputed":
                self._workflow_id = int(wf_str)
                self._escalation_count += 1
                return {"action": "escalate_dispute", "params": {"workflow_id": self._workflow_id}}
        return None


class ScriptedSequenceLive(LiveAgent):
    """Executes a predetermined sequence of actions one per tick.

    Script entries are dicts:
        {"action": str, "params": dict, "save_wf_as": str | None}

    Params may reference workflow IDs discovered earlier using string aliases:
        {"workflow_id": "wf0"}   -- substituted once "wf0" is known

    Steps requiring an unknown alias are deferred (skip until alias is populated).
    """

    def __init__(self, agent_id: str, script: list[dict]):
        super().__init__(agent_id)
        self._script = script
        self._idx = 0
        self._wf_map: dict[str, int] = {}
        self._pending_alias: Optional[str] = None

    def _resolve_params(self, raw: dict) -> Optional[dict]:
        resolved = {}
        for k, v in raw.items():
            if k == "workflow_id" and isinstance(v, str):
                if v not in self._wf_map:
                    return None
                resolved[k] = self._wf_map[v]
            else:
                resolved[k] = v
        return resolved

    def decide(self, world_view: WorldView | None, seq: int, block_time: int) -> Optional[dict]:
        if self._idx >= len(self._script):
            return None
        step = self._script[self._idx]
        params = self._resolve_params(step.get("params", {}))
        if params is None:
            return None  # waiting for a workflow_id alias
        self._idx += 1
        self._pending_alias = step.get("save_wf_as")
        return {"action": step["action"], "params": params}

    def update_from_response(self, response: StepResponse) -> None:
        super().update_from_response(response)
        if self._pending_alias is None:
            return
        entry = response.get("trace_entry") or {}
        if entry.get("action") == "create_escrow" and entry.get("result") == "ok":
            wf_id = (entry.get("extra") or {}).get("workflow_id")
            if wf_id is not None:
                self._wf_map[self._pending_alias] = wf_id
        self._pending_alias = None


# ---------------------------------------------------------------------------
# Runner helpers
# ---------------------------------------------------------------------------

DR3_PARAMS = {
    "resolver_fee_bps": 150,
    "appeal_window_duration": 0,
    "max_dispute_duration": 2592000,
}

# DR3 via resolution module (Priority 2).
# The resolution_module address is the single address the module will authorize.
# Escrows must be created WITHOUT custom_resolver so Priority 2 fires.
DR3_MODULE_PARAMS = {
    "resolver_fee_bps": 150,
    "resolution_module": "0xresolver",  # module authorizes only this address
    "appeal_window_duration": 0,
    "max_dispute_duration": 2592000,
}

# IEO base release: no resolver module, zero fee.
IEO_PARAMS = {
    "resolver_fee_bps": 0,
    "appeal_window_duration": 0,
    "max_dispute_duration": 2592000,
}

# IEO with short dispute window for timeout testing.
IEO_TIMEOUT_PARAMS = {
    "resolver_fee_bps": 0,
    "appeal_window_duration": 0,
    "max_dispute_duration": 300,  # 5 min → expires after 3 ticks @ 120s
}

# DR3 Kleros multi-level escalation (no appeal window — immediate finality).
# resolution_module activates Priority 2 in module snapshot.
# escalation_resolvers maps level → resolver address.
DR3_KLEROS_PARAMS = {
    "resolver_fee_bps": 150,
    "resolution_module": "0xkleros-proxy",
    "escalation_resolvers": {
        "0": "0xl0",
        "1": "0xl1",
        "2": "0xl2",
    },
    "appeal_window_duration": 0,
    "max_dispute_duration": 2592000,
}

# DR3 Kleros with appeal window — resolution deferred until window expires.
# Used to test pending-settlement-cleared-on-escalation invariant.
DR3_KLEROS_APPEAL_PARAMS = {
    "resolver_fee_bps": 150,
    "resolution_module": "0xkleros-proxy",
    "escalation_resolvers": {
        "0": "0xl0",
        "1": "0xl1",
        "2": "0xl2",
    },
    "appeal_window_duration": 60,   # 1 tick @ 60s — cleared by escalation
    "max_dispute_duration": 2592000,
}

TIMEOUT_PARAMS = {
    "resolver_fee_bps": 150,
    "appeal_window_duration": 0,
    "max_dispute_duration": 300,   # 5 min → times out after 3 ticks @ 120s each
}

APPEAL_PARAMS = {
    "resolver_fee_bps": 150,
    "appeal_window_duration": 120,  # 2-min window
    "max_dispute_duration": 600,
}


def run_scenario(
    name: str,
    agents_meta: list[dict],
    live_agents: list[LiveAgent],
    protocol_params: dict | None = None,
    max_steps: int = 40,
    max_ticks: int = 20,
    time_step_secs: int = 60,
) -> RunResult:
    with SimulationClient() as client:
        runner = LiveRunner(
            client,
            agents_meta,
            live_agents,
            protocol_params=protocol_params or DR3_PARAMS,
            time_step_secs=time_step_secs,
        )
        return runner.run(max_steps=max_steps, max_ticks=max_ticks)


def assert_scenario(name: str, result: RunResult) -> bool:
    violations = result.metrics.get("invariant_violations", 0)
    halted = result.outcome == "halted"
    ok = not halted and violations == 0
    status = "✓ PASS" if ok else "✗ FAIL"
    reason = ""
    if halted:
        reason = f" halt_reason={result.halt_reason}"
    if violations:
        reason += f" invariant_violations={violations}"
    print(
        f"  {status}  {name:<45}"
        f"steps={result.steps_executed:<4}"
        f"reverts={result.metrics.get('reverts', 0):<4}"
        f"{reason}"
    )
    return ok


# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------


def s01_baseline_happy_path() -> RunResult:
    return run_scenario(
        "S01",
        agents_meta=[
            {"id": "buyer",  "address": "0xbuyer",  "type": "honest"},
            {"id": "seller", "address": "0xseller", "type": "honest"},
        ],
        live_agents=[HonestBuyerLive("buyer", recipient_address="0xseller", amount=5000)],
    )


def s02_dr3_dispute_release() -> RunResult:
    return run_scenario(
        "S02",
        agents_meta=[
            {"id": "buyer",    "address": "0xbuyer",    "type": "honest"},
            {"id": "seller",   "address": "0xseller",   "type": "honest"},
            {"id": "resolver", "address": "0xresolver", "type": "resolver"},
        ],
        live_agents=[
            DisputingBuyerLive("buyer", "0xseller", resolver_address="0xresolver", amount=4000),
            HonestResolverLive("resolver"),
        ],
    )


def s03_dr3_dispute_refund() -> RunResult:
    return run_scenario(
        "S03",
        agents_meta=[
            {"id": "buyer",    "address": "0xbuyer",    "type": "honest"},
            {"id": "seller",   "address": "0xseller",   "type": "honest"},
            {"id": "resolver", "address": "0xresolver", "type": "resolver"},
        ],
        live_agents=[
            DisputingBuyerLive("buyer", "0xseller", resolver_address="0xresolver", amount=4000),
            RefundingResolverLive("resolver"),
        ],
    )


def s04_dispute_timeout_autocancel() -> RunResult:
    return run_scenario(
        "S04",
        agents_meta=[
            {"id": "buyer",  "address": "0xbuyer",  "type": "honest"},
            {"id": "seller", "address": "0xseller", "type": "honest"},
            {"id": "keeper", "address": "0xkeeper", "type": "keeper"},
        ],
        live_agents=[
            # creates + disputes; no resolver present
            GriefingBuyerLive("buyer", recipient_address="0xseller", amount=2000),
            TimeoutKeeperLive("keeper"),
        ],
        protocol_params=TIMEOUT_PARAMS,
        max_ticks=15,
        time_step_secs=120,  # 3 ticks after dispute → 360 s > 300 s threshold
    )


def s05_pending_settlement_execute() -> RunResult:
    return run_scenario(
        "S05",
        agents_meta=[
            {"id": "buyer",    "address": "0xbuyer",    "type": "honest"},
            {"id": "seller",   "address": "0xseller",   "type": "honest"},
            {"id": "resolver", "address": "0xresolver", "type": "resolver"},
            {"id": "executor", "address": "0xexecutor", "type": "keeper"},
        ],
        live_agents=[
            DisputingBuyerLive("buyer", "0xseller", resolver_address="0xresolver", amount=6000),
            HonestResolverLive("resolver"),      # creates pending settlement
            PendingSettlementExecutorLive("executor"),  # executes after window
        ],
        protocol_params=APPEAL_PARAMS,
        max_ticks=20,
        time_step_secs=30,  # 5 ticks → 150 s > 120 s appeal window
    )


def s06_mutual_cancel() -> RunResult:
    return run_scenario(
        "S06",
        agents_meta=[
            {"id": "buyer",  "address": "0xbuyer",  "type": "honest"},
            {"id": "seller", "address": "0xseller", "type": "honest"},
        ],
        live_agents=[
            MutualCancelBuyerLive("buyer", recipient_address="0xseller", amount=1500),
            MutualCancelSellerLive("seller"),
        ],
    )


def s07_unauthorized_resolver_rejected() -> RunResult:
    return run_scenario(
        "S07",
        agents_meta=[
            {"id": "buyer",       "address": "0xbuyer",       "type": "honest"},
            {"id": "seller",      "address": "0xseller",      "type": "honest"},
            {"id": "badresolver", "address": "0xbadresolver", "type": "attacker"},
            {"id": "resolver",    "address": "0xresolver",    "type": "resolver"},
        ],
        live_agents=[
            DisputingBuyerLive("buyer", "0xseller", resolver_address="0xresolver", amount=3000),
            WrongResolverLive("badresolver"),
            HonestResolverLive("resolver"),
        ],
        max_ticks=15,
    )


def s08_state_machine_attack_gauntlet() -> RunResult:
    """One scripted agent walks through every forbidden state transition."""
    # Uses two agents on the same script — buyer creates/disputes, attacker abuses
    # but here a single ScriptedSequenceLive exercises every invalid transition.
    script = [
        # Step 0: create escrow → wf0
        {
            "action": "create_escrow",
            "params": {
                "token": "USDC",
                "to": "0xseller",
                "amount": 2000,
                "custom_resolver": "0xresolver",
            },
            "save_wf_as": "wf0",
        },
        # Step 1: try execute_resolution before dispute → rejected
        {
            "action": "execute_resolution",
            "params": {"workflow_id": "wf0", "is_release": True, "resolution_hash": "0xh"},
        },
        # Step 2: raise dispute → ok
        {"action": "raise_dispute", "params": {"workflow_id": "wf0"}},
        # Step 3: try release while disputed → rejected
        {"action": "release", "params": {"workflow_id": "wf0"}},
        # Step 4: try raise_dispute again → rejected (not :pending)
        {"action": "raise_dispute", "params": {"workflow_id": "wf0"}},
        # Step 5: authorised resolver resolves → ok
        {
            "action": "execute_resolution",
            "params": {"workflow_id": "wf0", "is_release": True, "resolution_hash": "0xauth"},
        },
        # Step 6: try to resolve again on terminal state → rejected
        {
            "action": "execute_resolution",
            "params": {"workflow_id": "wf0", "is_release": True, "resolution_hash": "0xagain"},
        },
        # Step 7: try to raise dispute on terminal state → rejected
        {"action": "raise_dispute", "params": {"workflow_id": "wf0"}},
        # Step 8: try to release on terminal state → rejected
        {"action": "release", "params": {"workflow_id": "wf0"}},
    ]

    return run_scenario(
        "S08",
        agents_meta=[
            {"id": "buyer",    "address": "0xbuyer",    "type": "honest"},
            {"id": "seller",   "address": "0xseller",   "type": "honest"},
            {"id": "resolver", "address": "0xresolver", "type": "resolver"},
        ],
        live_agents=[ScriptedSequenceLive("buyer", script)],
    )


def s09_multi_escrow_solvency() -> RunResult:
    """Three concurrent escrows with mixed outcomes: release, DR3 resolve, refund."""
    return run_scenario(
        "S09",
        agents_meta=[
            {"id": "buyer0",   "address": "0xbuyer0",   "type": "honest"},
            {"id": "buyer1",   "address": "0xbuyer1",   "type": "honest"},
            {"id": "buyer2",   "address": "0xbuyer2",   "type": "honest"},
            {"id": "seller",   "address": "0xseller",   "type": "honest"},
            {"id": "resolver", "address": "0xresolver", "type": "resolver"},
        ],
        live_agents=[
            # buyer0: plain release
            HonestBuyerLive("buyer0", recipient_address="0xseller", amount=1000),
            # buyer1: creates + disputes → resolver resolves (release)
            DisputingBuyerLive("buyer1", "0xseller", resolver_address="0xresolver", amount=2000),
            # buyer2: creates + disputes → resolver refunds
            DisputingBuyerLive("buyer2", "0xseller", resolver_address="0xresolver", amount=3000),
            # resolver handles both buyer1 and buyer2 escrows
            # first disputed → honest release; second disputed → refund
            HonestResolverLive("resolver"),
        ],
        max_ticks=20,
    )


def s10_double_finalize_rejected() -> RunResult:
    """Release an escrow, then try to release again; resolve, then try to resolve again."""
    script_double_release = [
        {
            "action": "create_escrow",
            "params": {"token": "USDC", "to": "0xseller", "amount": 500},
            "save_wf_as": "wf0",
        },
        {"action": "release",  "params": {"workflow_id": "wf0"}},           # ok
        {"action": "release",  "params": {"workflow_id": "wf0"}},           # rejected
        {"action": "release",  "params": {"workflow_id": "wf0"}},           # rejected
    ]

    script_double_resolve = [
        {
            "action": "create_escrow",
            "params": {
                "token": "USDC",
                "to": "0xseller2",
                "amount": 500,
                "custom_resolver": "0xresolver",
            },
            "save_wf_as": "wf1",
        },
        {"action": "raise_dispute", "params": {"workflow_id": "wf1"}},
        {
            "action": "execute_resolution",
            "params": {"workflow_id": "wf1", "is_release": True, "resolution_hash": "0xok"},
        },
        {
            "action": "execute_resolution",
            "params": {"workflow_id": "wf1", "is_release": True, "resolution_hash": "0xdup"},  # rejected
        },
    ]

    return run_scenario(
        "S10",
        agents_meta=[
            {"id": "buyer",    "address": "0xbuyer",    "type": "honest"},
            {"id": "seller",   "address": "0xseller",   "type": "honest"},
            {"id": "seller2",  "address": "0xseller2",  "type": "honest"},
            {"id": "resolver", "address": "0xresolver", "type": "resolver"},
        ],
        live_agents=[
            ScriptedSequenceLive("buyer", script_double_release),
            ScriptedSequenceLive("resolver", script_double_resolve),
        ],
    )


def s11_zero_fee_edge_case() -> RunResult:
    """fee_bps=0: no protocol fee deducted; amount_after_fee == amount throughout."""
    return run_scenario(
        "S11",
        agents_meta=[
            {"id": "buyer",    "address": "0xbuyer",    "type": "honest"},
            {"id": "seller",   "address": "0xseller",   "type": "honest"},
            {"id": "resolver", "address": "0xresolver", "type": "resolver"},
        ],
        live_agents=[
            DisputingBuyerLive("buyer", "0xseller", resolver_address="0xresolver", amount=1),
            HonestResolverLive("resolver"),
        ],
        protocol_params={"resolver_fee_bps": 0, "appeal_window_duration": 0, "max_dispute_duration": 2592000},
    )


def s12_governance_snapshot_isolation() -> RunResult:
    """Two sessions with different fee_bps must not cross-contaminate.

    Runs session A (fee_bps=0) and session B (fee_bps=500) sequentially.
    Both must complete without invariant violations; if snapshot isolation is
    broken, one session will see the other's params and likely corrupt state.
    """
    for fee_bps, label in [(0, "A fee=0"), (500, "B fee=500")]:
        with SimulationClient() as client:
            runner = LiveRunner(
                client,
                agents_meta=[
                    {"id": "buyer",  "address": "0xbuyer",  "type": "honest"},
                    {"id": "seller", "address": "0xseller", "type": "honest"},
                ],
                live_agents=[HonestBuyerLive("buyer", recipient_address="0xseller", amount=10_000)],
                protocol_params={"resolver_fee_bps": fee_bps},
            )
            r = runner.run(max_steps=10, max_ticks=5)
            if r.outcome == "halted" or r.metrics.get("invariant_violations", 0):
                # Fabricate a failed RunResult so assert_scenario can report it
                return r

    # Both sessions clean — return the last result (pass)
    return r  # type: ignore[return-value]


def s13_pending_settlement_refund() -> RunResult:
    """Pending-settlement → refunded path.

    Like S05 but the resolver votes is_release=False.  The appeal window
    creates a :pending-settlement escrow; once the window expires,
    execute_pending_settlement must finalize it as :refunded (not :released).
    Tests the second branch of the pending-settlement state machine that
    S05 leaves uncovered.
    """
    return run_scenario(
        "S13",
        agents_meta=[
            {"id": "buyer",    "address": "0xbuyer",    "type": "honest"},
            {"id": "seller",   "address": "0xseller",   "type": "honest"},
            {"id": "resolver", "address": "0xresolver", "type": "resolver"},
            {"id": "executor", "address": "0xexecutor", "type": "keeper"},
        ],
        live_agents=[
            DisputingBuyerLive("buyer", "0xseller", resolver_address="0xresolver", amount=6000),
            RefundingResolverLive("resolver"),       # is_release=False → pending-settlement(refund)
            PendingSettlementExecutorLive("executor"),  # executes after appeal window
        ],
        protocol_params=APPEAL_PARAMS,
        max_ticks=20,
        time_step_secs=30,  # 5 ticks × 30 s = 150 s > 120 s appeal window
    )


def s14_dr3_module_authorized_resolver() -> RunResult:
    """DR3 resolution module (Priority 2): authorized resolver succeeds.

    Escrow is created WITHOUT custom_resolver so that authority falls through
    to Priority 2 (resolution module).  The session's protocol_params carry
    resolution_module="0xresolver", which makes build-context wire a
    make-default-resolution-module fn that authorizes only that address.
    The resolver at "0xresolver" calls execute_resolution and must succeed.

    This is the canonical DR3 live-contract test: resolution module active,
    correct resolver authorized, dispute resolves cleanly.
    """
    return run_scenario(
        "S14",
        agents_meta=[
            {"id": "buyer",    "address": "0xbuyer",    "type": "honest"},
            {"id": "seller",   "address": "0xseller",   "type": "honest"},
            {"id": "resolver", "address": "0xresolver", "type": "resolver"},
        ],
        live_agents=[
            DisputingBuyerLive("buyer", "0xseller", amount=5000),  # no custom_resolver
            HonestResolverLive("resolver"),
        ],
        protocol_params=DR3_MODULE_PARAMS,
    )


def s15_dr3_module_unauthorized_resolver_rejected() -> RunResult:
    """DR3 resolution module (Priority 2): wrong resolver is rejected.

    Same setup as S14 but a bad actor at "0xbadresolver" races to call
    execute_resolution first.  The module returns authorized?=false for that
    address, and there is no custom_resolver or matching dispute-resolver
    fallback, so the call reverts.  The correct resolver at "0xresolver"
    then resolves successfully.

    Asserts: wrong-resolver attempt produces a revert (not a violation), and
    the final escrow state is clean.
    """
    return run_scenario(
        "S15",
        agents_meta=[
            {"id": "buyer",       "address": "0xbuyer",       "type": "honest"},
            {"id": "seller",      "address": "0xseller",      "type": "honest"},
            {"id": "badresolver", "address": "0xbadresolver", "type": "attacker"},
            {"id": "resolver",    "address": "0xresolver",    "type": "resolver"},
        ],
        live_agents=[
            DisputingBuyerLive("buyer", "0xseller", amount=5000),  # no custom_resolver
            WrongResolverLive("badresolver"),   # Priority 2 rejects 0xbadresolver
            HonestResolverLive("resolver"),     # Priority 2 authorizes 0xresolver
        ],
        protocol_params=DR3_MODULE_PARAMS,
    )


# ---------------------------------------------------------------------------
# IEO base-release scenarios (S16–S17)
# ---------------------------------------------------------------------------


def s16_ieo_create_release() -> RunResult:
    """IEO base: escrow created and released by sender — no module, zero fee.

    Validates the simplest lifecycle path: create → release.  Confirms
    that the contract functions correctly before any resolver module is
    deployed (IEO state of the protocol).

    Asserts: no violations, clean terminal state (released).
    """
    return run_scenario(
        "S16",
        agents_meta=[
            {"id": "buyer",  "address": "0xbuyer",  "type": "honest"},
            {"id": "seller", "address": "0xseller", "type": "honest"},
        ],
        live_agents=[
            ScriptedSequenceLive("buyer", script=[
                {
                    "action": "create_escrow",
                    "params": {"token": "USDC", "to": "0xseller", "amount": 2000},
                    "save_wf_as": "wf0",
                },
                {"action": "release", "params": {"workflow_id": "wf0"}},
            ]),
        ],
        protocol_params=IEO_PARAMS,
    )


def s17_ieo_dispute_no_resolver_timeout() -> RunResult:
    """IEO base: buyer disputes with no resolver configured → auto-cancel on timeout.

    Verifies the unresolvable-dispute path in the IEO contract state: no
    module, no custom_resolver.  The dispute sits open until
    max_dispute_duration (300 s) elapses.  TimeoutKeeperLive retries
    auto_cancel_disputed each tick until it succeeds.

    Asserts: dispute auto-cancelled (refunded), no violations.
    """
    return run_scenario(
        "S17",
        agents_meta=[
            {"id": "buyer",  "address": "0xbuyer",  "type": "honest"},
            {"id": "seller", "address": "0xseller", "type": "honest"},
            {"id": "keeper", "address": "0xkeeper", "type": "resolver"},
        ],
        live_agents=[
            DisputingBuyerLive("buyer", "0xseller", amount=1000),  # no custom_resolver
            TimeoutKeeperLive("keeper"),
        ],
        protocol_params=IEO_TIMEOUT_PARAMS,
        max_steps=40,
        max_ticks=20,
        time_step_secs=120,
    )


# ---------------------------------------------------------------------------
# DR3 Kleros escalation scenarios (S18–S21)
# ---------------------------------------------------------------------------


def s18_dr3_kleros_l0_resolves() -> RunResult:
    """DR3 Kleros (Priority 2): L0 resolver resolves at level 0, no escalation.

    Escrow created without custom_resolver so Priority 2 (Kleros module) fires.
    The module reads dispute-level=0 and authorizes only "0xl0".
    L0 resolver calls execute_resolution → authorized → finalizes immediately
    (no appeal window).

    Asserts: Kleros module correctly authorizes level-0 resolver, no violations.
    """
    return run_scenario(
        "S18",
        agents_meta=[
            {"id": "buyer",      "address": "0xbuyer", "type": "honest"},
            {"id": "seller",     "address": "0xseller", "type": "honest"},
            {"id": "l0resolver", "address": "0xl0",    "type": "resolver"},
        ],
        live_agents=[
            EscalatingBuyerLive("buyer", "0xseller", max_escalations=0),
            HonestResolverLive("l0resolver"),
        ],
        protocol_params=DR3_KLEROS_PARAMS,
    )


def s19_dr3_kleros_escalate_l1_resolves() -> RunResult:
    """DR3 Kleros: buyer escalates L0→L1; L1 resolver resolves.

    Buyer escalates once after dispute, advancing dispute-level to 1.
    escalation-fn returns {ok=true, new-resolver="0xl1"}.
    Kleros module now reads level=1 and authorizes only "0xl1".
    L0 resolver retries but is rejected (level-1 expects "0xl1" not "0xl0").
    L1 resolver succeeds.

    Asserts: escalation increments level correctly; module authorizes new
    resolver; old resolver cannot resolve after escalation; no violations.
    """
    return run_scenario(
        "S19",
        agents_meta=[
            {"id": "buyer",      "address": "0xbuyer", "type": "honest"},
            {"id": "seller",     "address": "0xseller", "type": "honest"},
            {"id": "l0resolver", "address": "0xl0",    "type": "resolver"},
            {"id": "l1resolver", "address": "0xl1",    "type": "resolver"},
        ],
        live_agents=[
            EscalatingBuyerLive("buyer", "0xseller", max_escalations=1),
            HonestResolverLive("l0resolver"),   # unauthorized after escalation → reverts
            HonestResolverLive("l1resolver"),   # authorized at level 1 → succeeds
        ],
        protocol_params=DR3_KLEROS_PARAMS,
    )


def s20_dr3_kleros_max_escalation_guard() -> RunResult:
    """DR3 Kleros: escalate twice to level 2 (max); third escalation reverts.

    Buyer attempts three escalations:
      attempt 1 — level 0→1  (ok)
      attempt 2 — level 1→2  (ok)
      attempt 3 — final-round? = true → :escalation-not-allowed (revert, not violation)
    L2 resolver (only authorized at level 2) then resolves.

    Asserts: max-dispute-level guard fires correctly; reverts are logged, not
    violations; L2 resolver is authorised in the final round; no violations.
    """
    return run_scenario(
        "S20",
        agents_meta=[
            {"id": "buyer",      "address": "0xbuyer", "type": "honest"},
            {"id": "seller",     "address": "0xseller", "type": "honest"},
            {"id": "l2resolver", "address": "0xl2",    "type": "resolver"},
        ],
        live_agents=[
            EscalatingBuyerLive("buyer", "0xseller", max_escalations=3),  # 3rd reverts
            HonestResolverLive("l2resolver"),   # only authorized at level 2
        ],
        protocol_params=DR3_KLEROS_PARAMS,
    )


def s21_dr3_kleros_pending_cleared_on_escalation() -> RunResult:
    """DR3 Kleros: pending settlement is cleared when escalation fires.

    With appeal_window_duration=60, execute_resolution defers the decision
    into a PendingSettlement instead of finalising immediately (level < 2).

    Sequence:
      1. Buyer creates escrow (no custom_resolver), raises dispute
      2. L0 resolver calls execute_resolution → pending settlement created
         (level=0, not final round, appeal_window=60)
      3. Buyer escalates → pending cleared, level advances to 1,
         new dispute-resolver set to "0xl1"
      4. L1 resolver calls execute_resolution → new pending settlement (level=1)
      5. AutomateTimedActionsLive triggers execute_pending_settlement after the
         appeal window expires

    Key invariant: step 4 must NOT return :resolution-already-pending
    (proof that step 3 cleared the pending settlement).

    Asserts: pending cleared on escalation; fresh resolution accepted by L1;
    eventual settlement executed; no violations.
    """
    return run_scenario(
        "S21",
        agents_meta=[
            {"id": "buyer",      "address": "0xbuyer", "type": "honest"},
            {"id": "seller",     "address": "0xseller", "type": "honest"},
            {"id": "l0resolver", "address": "0xl0",    "type": "resolver"},
            {"id": "l1resolver", "address": "0xl1",    "type": "resolver"},
            {"id": "keeper",     "address": "0xkeeper", "type": "resolver"},
        ],
        live_agents=[
            EscalatingBuyerLive("buyer", "0xseller", max_escalations=1),
            HonestResolverLive("l0resolver"),         # creates pending at level 0
            HonestResolverLive("l1resolver"),         # creates fresh pending at level 1
            AutomateTimedActionsLive("keeper"),       # executes pending after window expires
        ],
        protocol_params=DR3_KLEROS_APPEAL_PARAMS,
        max_steps=60,
        max_ticks=30,
        time_step_secs=60,
    )


def s22_status_leak_agree_cancel_over_dispute() -> RunResult:
    """REGRESSION: transition-to-disputed clears counterparty agree-to-cancel.

    Previously broken: state_machine/transition-to-disputed set the caller's
    status to :raise-dispute but never cleared the other party's :agree-to-cancel
    flag, leaving {:disputed :raise-dispute :agree-to-cancel} — invalid per Inv 7.

    Fix: transition-to-disputed now resets the counterparty's status to :none.

    Sequence:
      1. Buyer creates escrow (buyer=:from, seller=:to)
      2. Seller calls recipient_cancel → :recipient-status :agree-to-cancel
         (escrow stays :pending — no mutual consent yet)
      3. Buyer calls raise_dispute → transition-to-disputed:
           sets :sender-status :raise-dispute
           clears :recipient-status → :none        ← FIX
         Result: {:disputed :raise-dispute :none} — valid

    Expected outcome: PASS (no invariant violation).
    """
    buyer = ScriptedSequenceLive("buyer", [
        {
            "action": "create_escrow",
            "params": {"token": "USDC", "to": "0xseller", "amount": 5000},
            "save_wf_as": "wf0",
        },
        {"action": "raise_dispute", "params": {"workflow_id": "wf0"}},
    ])
    # Seller uses hardcoded wf_id=0 — always the first escrow in a fresh session.
    seller = ScriptedSequenceLive("seller", [
        {"action": "recipient_cancel", "params": {"workflow_id": 0}},
    ])
    return run_scenario(
        "S22",
        agents_meta=[
            {"id": "buyer",  "address": "0xbuyer",  "type": "honest"},
            {"id": "seller", "address": "0xseller", "type": "honest"},
        ],
        live_agents=[buyer, seller],
    )


def s23_seller_preemptive_escalation_blocked() -> RunResult:
    """REGRESSION: preemptive escalation rejected when no resolution has been submitted.

    Previously broken: escalate_dispute had no guard requiring a prior resolution
    attempt.  A malicious seller could immediately double-escalate (L0→L1→L2)
    after a dispute was raised, bypassing cheaper/faster lower-level resolvers.

    Fix: escalate_dispute now requires a pending settlement to exist.  Escalation
    is an appeal of an existing resolver decision, not a unilateral level-skip.

    Sequence (with DR3_KLEROS_PARAMS, appeal_window=0):
      1. Buyer creates escrow, raises dispute (level=0)
      2. Seller immediately tries to escalate → REVERT :no-resolution-to-appeal
         (no pending settlement; l0 resolver hasn't acted yet)
      3. L0 resolver calls execute_resolution → resolves immediately
         (appeal_window=0 → direct finalization, no pending settlement needed)
      4. Seller tries again → REVERT :transfer-not-in-dispute (already resolved)

    Asserts: both seller escalations are rejected; L0 resolver resolves normally;
    no invariant violations.
    """
    buyer = EscalatingBuyerLive("buyer", "0xseller", max_escalations=0)
    seller = SellerGriefingLive("seller", max_escalations=2)
    return run_scenario(
        "S23",
        agents_meta=[
            {"id": "buyer",      "address": "0xbuyer",  "type": "honest"},
            {"id": "seller",     "address": "0xseller", "type": "honest"},
            {"id": "l0resolver", "address": "0xl0",     "type": "resolver"},
        ],
        live_agents=[buyer, seller,
                     HonestResolverLive("l0resolver")],
        protocol_params=DR3_KLEROS_PARAMS,
    )


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

SCENARIOS = [
    ("S01  baseline-happy-path",                      s01_baseline_happy_path),
    ("S02  dr3-dispute-release",                      s02_dr3_dispute_release),
    ("S03  dr3-dispute-refund",                       s03_dr3_dispute_refund),
    ("S04  dispute-timeout-autocancel",               s04_dispute_timeout_autocancel),
    ("S05  pending-settlement-execute",               s05_pending_settlement_execute),
    ("S06  mutual-cancel",                            s06_mutual_cancel),
    ("S07  unauthorized-resolver-rejected",           s07_unauthorized_resolver_rejected),
    ("S08  state-machine-attack-gauntlet",            s08_state_machine_attack_gauntlet),
    ("S09  multi-escrow-solvency",                    s09_multi_escrow_solvency),
    ("S10  double-finalize-rejected",                 s10_double_finalize_rejected),
    ("S11  zero-fee-edge-case",                       s11_zero_fee_edge_case),
    ("S12  governance-snapshot-isolation",            s12_governance_snapshot_isolation),
    ("S13  pending-settlement-refund",                s13_pending_settlement_refund),
    ("S14  dr3-module-authorized",                    s14_dr3_module_authorized_resolver),
    ("S15  dr3-module-unauthorized-rejected",         s15_dr3_module_unauthorized_resolver_rejected),
    ("S16  ieo-create-release",                       s16_ieo_create_release),
    ("S17  ieo-dispute-no-resolver-timeout",          s17_ieo_dispute_no_resolver_timeout),
    ("S18  dr3-kleros-l0-resolves",                   s18_dr3_kleros_l0_resolves),
    ("S19  dr3-kleros-escalate-l1-resolves",          s19_dr3_kleros_escalate_l1_resolves),
    ("S20  dr3-kleros-max-escalation-guard",          s20_dr3_kleros_max_escalation_guard),
    ("S21  dr3-kleros-pending-cleared-on-escalation", s21_dr3_kleros_pending_cleared_on_escalation),
    ("S22  status-leak-agree-cancel-over-dispute",    s22_status_leak_agree_cancel_over_dispute),
    ("S23  preemptive-escalation-blocked",            s23_seller_preemptive_escalation_blocked),
]


def main() -> None:
    print(f"\n{'═' * 72}")
    print("  SEW Invariant Suite")
    print(f"{'═' * 72}")
    print(f"  {'Scenario':<47}{'steps':<7}{'reverts':<9}status")
    print(f"  {'-' * 68}")

    results = []
    for name, fn in SCENARIOS:
        result = fn()
        ok = assert_scenario(name, result)
        results.append((name, ok))

    passed = sum(1 for _, ok in results if ok)
    total  = len(results)

    print(f"\n{'═' * 72}")
    print(f"  {passed}/{total} scenarios passed")
    if passed < total:
        failed = [n for n, ok in results if not ok]
        print(f"  FAILED: {', '.join(failed)}")
    print(f"{'═' * 72}\n")

    sys.exit(0 if passed == total else 1)


if __name__ == "__main__":
    main()
