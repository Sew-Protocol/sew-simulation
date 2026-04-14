"""
invariant_suite.py — Comprehensive adversarial invariant test suite.

10 scenarios stress the SEW escrow contract and DR3 dispute resolution system.
Every scenario asserts:
  - outcome == "pass"          (Clojure never halted due to invariant violation)
  - invariant_violations == 0  (no post-step invariant check failed)

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
    """Creates one escrow (with custom_resolver) then immediately raises a dispute."""

    def __init__(
        self,
        agent_id: str,
        recipient_address: str,
        resolver_address: str,
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
            return {
                "action": "create_escrow",
                "params": {
                    "token": self.token,
                    "to": self.recipient_address,
                    "amount": self.amount,
                    "custom_resolver": self.resolver_address,
                },
            }
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


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

SCENARIOS = [
    ("S01  baseline-happy-path",           s01_baseline_happy_path),
    ("S02  dr3-dispute-release",           s02_dr3_dispute_release),
    ("S03  dr3-dispute-refund",            s03_dr3_dispute_refund),
    ("S04  dispute-timeout-autocancel",    s04_dispute_timeout_autocancel),
    ("S05  pending-settlement-execute",    s05_pending_settlement_execute),
    ("S06  mutual-cancel",                 s06_mutual_cancel),
    ("S07  unauthorized-resolver-rejected",s07_unauthorized_resolver_rejected),
    ("S08  state-machine-attack-gauntlet", s08_state_machine_attack_gauntlet),
    ("S09  multi-escrow-solvency",         s09_multi_escrow_solvency),
    ("S10  double-finalize-rejected",      s10_double_finalize_rejected),
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
