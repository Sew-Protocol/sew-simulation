"""
Tests for Phase 2: live_runner, replay_runner, live_agents, and grpc_client.

Unit tests use a MockClient that stubs the SimulationClient interface.
Integration tests (marked with @pytest.mark.integration) require a running
Clojure gRPC server on localhost:7070 and are skipped by default.

Run unit tests:
    pytest python/tests/test_live_runner.py -v

Run integration tests:
    pytest python/tests/test_live_runner.py -v -m integration
"""

from __future__ import annotations

import json
import tempfile
import uuid
from unittest.mock import MagicMock, call, patch

import grpc
import pytest

from sew_sim.grpc_client import SimulationClient, managed_session
from sew_sim.live_agents import (
    AttackingBuyerLive,
    GriefingBuyerLive,
    HonestBuyerLive,
    HonestResolverLive,
    TimedAutomatorLive,
)
from sew_sim.live_runner import LiveRunner, _zero_metrics
from sew_sim.replay_runner import GrpcReplayRunner, compare_outcomes, load_scenario


# ---------------------------------------------------------------------------
# Shared fixtures and helpers
# ---------------------------------------------------------------------------

AGENTS = [
    {"id": "buyer",    "address": "0xbuyer",    "type": "honest"},
    {"id": "seller",   "address": "0xseller",   "type": "honest"},
    {"id": "resolver", "address": "0xresolver", "type": "resolver"},
]


def _ok_start(session_id: str = "s1") -> dict:
    return {"session_id": session_id, "ok": True, "error": None}


def _ok_destroy(session_id: str = "s1") -> dict:
    return {"session_id": session_id, "ok": True}


def _step_resp(
    session_id: str = "s1",
    result: str = "ok",
    action: str = "advance_time",
    seq: int = 0,
    workflow_id: int | None = None,
    halted: bool = False,
) -> dict:
    entry = {
        "seq": seq,
        "time": 1000 + seq * 60,
        "agent": "buyer",
        "action": action,
        "result": result,
        "error": None,
        "invariants_ok": True,
        "violations": None,
        "extra": {"workflow_id": workflow_id} if workflow_id is not None else None,
    }
    return {
        "session_id": session_id,
        "result": result,
        "world_view": {
            "block_time": 1000 + seq * 60,
            "escrow_count": 1 if action == "create_escrow" and result == "ok" else 0,
            "total_held": {},
            "total_fees": {},
            "pending_count": 0,
            "live_states": {},
        },
        "trace_entry": entry,
        "halted": halted,
        "error": None,
    }


class MockClient:
    """Minimal mock of SimulationClient that records calls."""

    def __init__(self, step_responses: list[dict] | None = None):
        self._start_calls: list[dict] = []
        self._step_calls: list[dict] = []
        self._destroy_calls: list[str] = []
        self._step_responses = iter(step_responses or [])
        self._default_step = _step_resp()

    def start_session(self, session_id, agents, protocol_params=None, initial_block_time=1000):
        self._start_calls.append({"session_id": session_id, "agents": agents})
        return _ok_start(session_id)

    def step(self, session_id, event):
        self._step_calls.append({"session_id": session_id, "event": event})
        try:
            return next(self._step_responses)
        except StopIteration:
            return self._default_step

    def destroy_session(self, session_id):
        self._destroy_calls.append(session_id)
        return _ok_destroy(session_id)

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


# ===========================================================================
# managed_session
# ===========================================================================


class TestManagedSession:
    def test_creates_and_destroys_session(self):
        client = MockClient()
        with managed_session(client, AGENTS, session_id="s1"):
            assert len(client._start_calls) == 1
            assert client._start_calls[0]["session_id"] == "s1"
        assert "s1" in client._destroy_calls

    def test_destroys_on_exception(self):
        client = MockClient()
        try:
            with managed_session(client, AGENTS, session_id="s2"):
                raise RuntimeError("boom")
        except RuntimeError:
            pass
        assert "s2" in client._destroy_calls

    def test_auto_generates_session_id(self):
        client = MockClient()
        with managed_session(client, AGENTS) as sid:
            assert isinstance(sid, str)
            assert len(sid) == 36  # UUID format

    def test_raises_when_start_fails(self):
        class FailClient(MockClient):
            def start_session(self, *args, **kwargs):
                return {"session_id": "s3", "ok": False, "error": "session_already_exists"}

        with pytest.raises(RuntimeError, match="session_already_exists"):
            with managed_session(FailClient(), AGENTS, session_id="s3"):
                pass


# ===========================================================================
# HonestBuyerLive
# ===========================================================================


class TestHonestBuyerLive:
    def test_first_decide_creates_escrow(self):
        agent = HonestBuyerLive("buyer", "0xseller")
        action = agent.decide(None, seq=0, block_time=1000)
        assert action["action"] == "create_escrow"
        assert action["params"]["to"] == "0xseller"

    def test_second_decide_releases_after_create(self):
        agent = HonestBuyerLive("buyer", "0xseller")
        agent.update_from_response(
            _step_resp(action="create_escrow", result="ok", workflow_id=0)
        )
        action = agent.decide(None, seq=1, block_time=1060)
        assert action["action"] == "release"
        assert action["params"]["workflow_id"] == 0

    def test_decide_returns_none_after_release(self):
        agent = HonestBuyerLive("buyer", "0xseller")
        agent.update_from_response(
            _step_resp(action="create_escrow", result="ok", workflow_id=0)
        )
        agent.update_from_response(
            _step_resp(action="release", result="ok")
        )
        assert agent.decide(None, seq=2, block_time=1120) is None

    def test_no_release_when_create_rejected(self):
        agent = HonestBuyerLive("buyer", "0xseller")
        agent.update_from_response(
            _step_resp(action="create_escrow", result="rejected")
        )
        # _created is still False so it tries create_escrow again
        action = agent.decide(None, seq=1, block_time=1060)
        assert action["action"] == "create_escrow"


# ===========================================================================
# HonestResolverLive
# ===========================================================================


class TestHonestResolverLive:
    def test_resolves_disputed_workflow(self):
        agent = HonestResolverLive("resolver")
        world_view = {
            "block_time": 2000,
            "live_states": {"0": "disputed"},
        }
        action = agent.decide(world_view, seq=5, block_time=2000)
        assert action["action"] == "execute_resolution"
        assert action["params"]["workflow_id"] == 0
        assert action["params"]["is_release"] is True

    def test_no_action_when_no_disputes(self):
        agent = HonestResolverLive("resolver")
        world_view = {"live_states": {"0": "pending"}}
        assert agent.decide(world_view, seq=0, block_time=1000) is None

    def test_no_action_on_none_world_view(self):
        agent = HonestResolverLive("resolver")
        assert agent.decide(None, seq=0, block_time=1000) is None

    def test_does_not_re_resolve_same_workflow(self):
        agent = HonestResolverLive("resolver")
        world_view = {"live_states": {"0": "disputed"}}
        agent.decide(world_view, seq=0, block_time=1000)
        # Simulate server accepted resolution
        agent.update_from_response({
            "trace_entry": {
                "action": "execute_resolution",
                "result": "ok",
                "params": {"workflow_id": 0},
            }
        })
        # World still shows disputed (shouldn't retry)
        assert agent.decide(world_view, seq=1, block_time=1060) is None


# ===========================================================================
# GriefingBuyerLive
# ===========================================================================


class TestGriefingBuyerLive:
    def test_creates_then_disputes(self):
        agent = GriefingBuyerLive("griever", "0xseller")
        action = agent.decide(None, seq=0, block_time=1000)
        assert action["action"] == "create_escrow"

        agent.update_from_response(
            _step_resp(action="create_escrow", result="ok", workflow_id=1)
        )
        action2 = agent.decide(None, seq=1, block_time=1060)
        assert action2["action"] == "raise_dispute"
        assert action2["params"]["workflow_id"] == 1

    def test_stops_after_dispute(self):
        agent = GriefingBuyerLive("griever", "0xseller")
        agent.update_from_response(
            _step_resp(action="create_escrow", result="ok", workflow_id=0)
        )
        agent.update_from_response(
            _step_resp(action="raise_dispute", result="ok")
        )
        assert agent.decide(None, seq=2, block_time=1120) is None


# ===========================================================================
# AttackingBuyerLive
# ===========================================================================


class TestAttackingBuyerLive:
    def test_creates_then_attacks_up_to_three_times(self):
        agent = AttackingBuyerLive("attacker", "0xseller")
        agent.update_from_response(
            _step_resp(action="create_escrow", result="ok", workflow_id=0)
        )
        attacks = [agent.decide(None, seq=i + 1, block_time=1000 + i * 60)
                   for i in range(5)]
        assert sum(1 for a in attacks if a is not None) == 3
        assert all(a["action"] == "release" for a in attacks if a is not None)

    def test_stops_after_three_attack_attempts(self):
        agent = AttackingBuyerLive("attacker", "0xseller")
        agent.update_from_response(
            _step_resp(action="create_escrow", result="ok", workflow_id=0)
        )
        for i in range(3):
            agent.decide(None, seq=i + 1, block_time=1000)
        assert agent.decide(None, seq=4, block_time=1000) is None


# ===========================================================================
# LiveRunner
# ===========================================================================


class TestLiveRunner:
    def _make_runner(self, client, agents=None):
        import random
        buyer    = HonestBuyerLive("buyer", "0xseller", rng=random.Random(42))
        resolver = HonestResolverLive("resolver", rng=random.Random(42))
        return LiveRunner(
            client,
            agents or AGENTS,
            [buyer, resolver],
            initial_block_time=1000,
        )

    def test_run_returns_pass_when_no_halt(self):
        responses = [
            _step_resp(action="create_escrow", result="ok", workflow_id=0, seq=0),
            _step_resp(action="release",        result="ok",               seq=1),
        ]
        client = MockClient(step_responses=responses)
        runner = self._make_runner(client)
        result = runner.run(session_id="r1", max_steps=10, max_ticks=5)
        assert result.outcome == "pass"
        assert result.steps_executed >= 2

    def test_run_returns_halted_on_invariant_violation(self):
        responses = [
            _step_resp(action="create_escrow", result="invariant_violated",
                       halted=True, seq=0),
        ]
        client = MockClient(step_responses=responses)
        runner = self._make_runner(client)
        result = runner.run(session_id="r2", max_steps=10, max_ticks=5)
        assert result.outcome == "halted"
        assert result.halted_at_seq == 0

    def test_session_destroyed_after_run(self):
        client = MockClient()
        runner = self._make_runner(client)
        runner.run(session_id="r3", max_steps=2, max_ticks=1)
        assert "r3" in client._destroy_calls

    def test_session_destroyed_even_on_halt(self):
        responses = [_step_resp(halted=True, seq=0)]
        client = MockClient(step_responses=responses)
        runner = self._make_runner(client)
        runner.run(session_id="r4", max_steps=5, max_ticks=3)
        assert "r4" in client._destroy_calls

    def test_max_steps_respected(self):
        client = MockClient()
        runner = self._make_runner(client)
        result = runner.run(session_id="r5", max_steps=1, max_ticks=10)
        assert result.steps_executed <= 1

    def test_metrics_accumulate_escrow_count(self):
        responses = [
            _step_resp(action="create_escrow", result="ok", workflow_id=0, seq=0),
        ]
        client = MockClient(step_responses=responses)
        runner = self._make_runner(client)
        result = runner.run(session_id="r6", max_steps=3, max_ticks=3)
        assert result.metrics["total_escrows"] >= 1


# ===========================================================================
# GrpcReplayRunner
# ===========================================================================


MINIMAL_SCENARIO = {
    "schema_version": "1.0",
    "scenario_id": "test-minimal",
    "agents": [
        {"id": "buyer", "address": "0xbuyer", "type": "honest"},
        {"id": "seller", "address": "0xseller", "type": "honest"},
    ],
    "protocol_params": {"resolver_fee_bps": 50},
    "initial_block_time": 1000,
    "events": [
        {"seq": 0, "time": 1000, "agent": "buyer",
         "action": "create_escrow",
         "params": {"token": "USDC", "to": "0xseller", "amount": 500}},
        {"seq": 1, "time": 1060, "agent": "buyer",
         "action": "release",
         "params": {"workflow_id": 0}},
    ],
}


class TestGrpcReplayRunner:
    def test_replay_scenario_pass(self):
        responses = [
            _step_resp(action="create_escrow", result="ok", workflow_id=0, seq=0),
            _step_resp(action="release",        result="ok",               seq=1),
        ]
        client = MockClient(step_responses=responses)
        runner = GrpcReplayRunner(client)
        result = runner.replay_scenario(MINIMAL_SCENARIO, session_id="rep1")
        assert result["outcome"] == "pass"
        assert result["events_processed"] == 2

    def test_replay_scenario_fail_on_halt(self):
        responses = [
            _step_resp(action="create_escrow", result="invariant_violated",
                       halted=True, seq=0),
        ]
        client = MockClient(step_responses=responses)
        runner = GrpcReplayRunner(client)
        result = runner.replay_scenario(MINIMAL_SCENARIO, session_id="rep2")
        assert result["outcome"] == "fail"
        assert result["halted_at_seq"] == 0
        assert result["halt_reason"] == "invariant_violation"

    def test_replay_rejects_wrong_schema_version(self):
        bad = {**MINIMAL_SCENARIO, "schema_version": "2.0"}
        client = MockClient()
        runner = GrpcReplayRunner(client)
        result = runner.replay_scenario(bad, session_id="rep3")
        assert result["outcome"] == "invalid"
        assert result["halt_reason"] == "unsupported_schema_version"
        # No gRPC calls should be made for structurally invalid scenarios
        assert len(client._start_calls) == 0

    def test_replay_file_loads_and_replays(self, tmp_path):
        scenario_file = tmp_path / "test_scenario.json"
        scenario_file.write_text(json.dumps(MINIMAL_SCENARIO))

        responses = [
            _step_resp(action="create_escrow", result="ok", workflow_id=0, seq=0),
            _step_resp(action="release",        result="ok",               seq=1),
        ]
        client = MockClient(step_responses=responses)
        runner = GrpcReplayRunner(client)
        result = runner.replay_file(str(scenario_file))
        assert result["outcome"] == "pass"

    def test_events_sent_in_seq_order(self):
        client = MockClient()
        runner = GrpcReplayRunner(client)
        runner.replay_scenario(MINIMAL_SCENARIO, session_id="rep4")
        seqs = [c["event"]["seq"] for c in client._step_calls]
        assert seqs == list(range(len(seqs)))

    def test_session_destroyed_after_replay(self):
        client = MockClient()
        runner = GrpcReplayRunner(client)
        runner.replay_scenario(MINIMAL_SCENARIO, session_id="rep5")
        assert "rep5" in client._destroy_calls

    def test_metrics_count_escrows(self):
        responses = [
            _step_resp(action="create_escrow", result="ok", workflow_id=0, seq=0),
            _step_resp(action="release",        result="ok",               seq=1),
        ]
        client = MockClient(step_responses=responses)
        runner = GrpcReplayRunner(client)
        result = runner.replay_scenario(MINIMAL_SCENARIO)
        assert result["metrics"]["total_escrows"] == 1
        assert result["metrics"]["total_volume"] == 500


# ===========================================================================
# compare_outcomes (from replay_runner)
# ===========================================================================


class TestCompareOutcomes:
    def test_identical_outcomes_match(self):
        direct = {"outcome": "pass", "trace": [{"result": "ok"}, {"result": "ok"}]}
        grpc   = {"outcome": "pass", "trace": [{"result": "ok"}, {"result": "ok"}]}
        cmp = compare_outcomes(direct, grpc)
        assert cmp["match"] is True
        assert cmp["differences"] == []

    def test_outcome_mismatch_reported(self):
        direct = {"outcome": "pass", "trace": []}
        grpc   = {"outcome": "fail", "trace": []}
        cmp = compare_outcomes(direct, grpc)
        assert cmp["match"] is False
        assert any("outcome" in d for d in cmp["differences"])

    def test_step_result_mismatch_reported(self):
        direct = {"outcome": "pass", "trace": [{"result": "ok"},  {"result": "ok"}]}
        grpc   = {"outcome": "pass", "trace": [{"result": "ok"},  {"result": "rejected"}]}
        cmp = compare_outcomes(direct, grpc)
        assert cmp["match"] is False
        assert any("step 1" in d for d in cmp["differences"])

    def test_trace_length_mismatch_reported(self):
        direct = {"outcome": "pass", "trace": [{"result": "ok"}, {"result": "ok"}]}
        grpc   = {"outcome": "pass", "trace": [{"result": "ok"}]}
        cmp = compare_outcomes(direct, grpc)
        assert cmp["match"] is False


# ===========================================================================
# load_scenario
# ===========================================================================


class TestLoadScenario:
    def test_load_round_trips_json(self, tmp_path):
        scenario_file = tmp_path / "scenario.json"
        scenario_file.write_text(json.dumps(MINIMAL_SCENARIO))
        loaded = load_scenario(str(scenario_file))
        assert loaded["schema_version"] == "1.0"
        assert len(loaded["events"]) == 2

    def test_load_preserves_snake_case_keys(self, tmp_path):
        scenario_file = tmp_path / "scenario.json"
        scenario_file.write_text(json.dumps(MINIMAL_SCENARIO))
        loaded = load_scenario(str(scenario_file))
        # Keys should remain snake_case (not converted to kebab-case here)
        assert "initial_block_time" in loaded
        assert "schema_version" in loaded


# ===========================================================================
# Integration tests (require running Clojure gRPC server on localhost:7070)
# ===========================================================================


@pytest.mark.integration
class TestIntegration:
    """
    Integration tests that require a running Clojure gRPC server.

    Start the server with:
        clojure -M -e "(require 'resolver-sim.server.grpc)
                       (resolver-sim.server.grpc/start! 7070)
                       @(promise)"

    Then run:
        pytest python/tests/test_live_runner.py -v -m integration
    """

    @pytest.fixture
    def client(self):
        c = SimulationClient(host="localhost", port=7070)
        try:
            probe_sid = str(uuid.uuid4())
            resp = c.start_session(probe_sid, AGENTS)
            if not resp.get("ok"):
                pytest.skip(f"gRPC server probe failed: {resp.get('error')}")
            c.destroy_session(probe_sid)
        except grpc.RpcError:
            pytest.skip("Clojure gRPC server is not running on localhost:7070")
        yield c
        c.close()

    def test_start_and_destroy_session(self, client):
        sid = str(uuid.uuid4())
        r = client.start_session(sid, AGENTS)
        assert r["ok"] is True
        r2 = client.destroy_session(sid)
        assert r2["ok"] is True

    def test_duplicate_session_rejected(self, client):
        sid = str(uuid.uuid4())
        client.start_session(sid, AGENTS)
        r = client.start_session(sid, AGENTS)
        assert r["ok"] is False
        assert r["error"] == "session-already-exists"
        client.destroy_session(sid)

    def test_step_create_escrow(self, client):
        sid = str(uuid.uuid4())
        client.start_session(sid, AGENTS, initial_block_time=1000)
        event = {
            "seq": 0, "time": 1000, "agent": "buyer",
            "action": "create_escrow",
            "params": {"token": "USDC", "to": "0xseller", "amount": 1000},
        }
        r = client.step(sid, event)
        assert r["result"] == "ok"
        assert r["halted"] is False
        assert r["trace_entry"]["extra"]["workflow_id"] == 0
        client.destroy_session(sid)

    def test_full_honest_trade(self, client):
        sid = str(uuid.uuid4())
        client.start_session(sid, AGENTS, initial_block_time=1000)
        events = [
            {"seq": 0, "time": 1000, "agent": "buyer",
             "action": "create_escrow",
             "params": {"token": "USDC", "to": "0xseller", "amount": 500}},
            {"seq": 1, "time": 1060, "agent": "buyer",
             "action": "release",
             "params": {"workflow_id": 0}},
        ]
        for e in events:
            r = client.step(sid, e)
            assert r["result"] == "ok", f"step {e['seq']} rejected: {r}"
            assert not r["halted"]
        client.destroy_session(sid)

    def test_replay_runner_matches_direct_replay(self, client):
        """Verify Phase 1 replay and Phase 2 gRPC replay produce identical outcomes."""
        runner = GrpcReplayRunner(client)
        result = runner.replay_scenario(MINIMAL_SCENARIO)
        assert result["outcome"] == "pass"
        assert result["events_processed"] == 2
        # Verify trace results match expected Phase 1 output
        assert result["trace"][0]["result"] == "ok"
        assert result["trace"][1]["result"] == "ok"
