"""
Tests for schema validation and scenario construction.

These tests verify that:
1. Valid scenarios pass Pydantic validation.
2. Invalid scenarios (bad agent refs, non-monotonic times, etc.) raise ValidationError.
3. The simulation factories produce valid scenarios.
4. Hypothesis strategies produce structurally valid scenarios (property-based).
"""

from __future__ import annotations

import pytest
from hypothesis import given, settings, HealthCheck
from pydantic import ValidationError

from sew_sim.schema import Agent, AgentType, Event, ProtocolParams, Scenario
from sew_sim.simulation import (
    build_dispute_scenario,
    build_griefing_scenario,
    build_honest_trade,
)
from sew_sim.generator import scenarios


# ---------------------------------------------------------------------------
# Schema unit tests
# ---------------------------------------------------------------------------


def make_minimal_scenario(**kwargs) -> dict:
    base = {
        "schema_version": "1.0",
        "scenario_id": "test-001",
        "seed": 42,
        "agents": [
            {"id": "alice", "type": "honest", "address": "0xAlice"},
            {"id": "bob", "type": "honest", "address": "0xBob"},
        ],
        "protocol_params": {"resolver_fee_bps": 50},
        "initial_block_time": 1000,
        "events": [
            {
                "seq": 0,
                "time": 1000,
                "agent": "alice",
                "action": "create_escrow",
                "params": {"token": "0xUSDC", "to": "0xBob", "amount": 5000},
            }
        ],
    }
    base.update(kwargs)
    return base


def test_minimal_scenario_valid():
    sc = Scenario(**make_minimal_scenario())
    assert sc.scenario_id == "test-001"
    assert len(sc.events) == 1


def test_unknown_agent_reference_raises():
    data = make_minimal_scenario(
        events=[
            {
                "seq": 0,
                "time": 1000,
                "agent": "nonexistent",  # not in agents list
                "action": "create_escrow",
                "params": {"token": "0xUSDC", "to": "0xBob", "amount": 5000},
            }
        ]
    )
    with pytest.raises(ValidationError, match="unknown agent"):
        Scenario(**data)


def test_non_monotonic_seq_raises():
    data = make_minimal_scenario(
        events=[
            {"seq": 0, "time": 1000, "agent": "alice", "action": "create_escrow",
             "params": {"token": "0xUSDC", "to": "0xBob", "amount": 5000}},
            {"seq": 0, "time": 1001, "agent": "alice", "action": "release",
             "params": {"workflow_id": 0}},
        ]
    )
    with pytest.raises(ValidationError, match="monotonically increasing"):
        Scenario(**data)


def test_event_time_before_initial_block_time_raises():
    data = make_minimal_scenario(
        initial_block_time=2000,
        events=[
            {"seq": 0, "time": 999, "agent": "alice", "action": "create_escrow",
             "params": {"token": "0xUSDC", "to": "0xBob", "amount": 5000}},
        ],
    )
    with pytest.raises(ValidationError, match="initial_block_time"):
        Scenario(**data)


def test_non_monotonic_time_raises():
    data = make_minimal_scenario(
        events=[
            {"seq": 0, "time": 1005, "agent": "alice", "action": "create_escrow",
             "params": {"token": "0xUSDC", "to": "0xBob", "amount": 5000}},
            {"seq": 1, "time": 1000, "agent": "alice", "action": "release",
             "params": {"workflow_id": 0}},
        ]
    )
    with pytest.raises(ValidationError, match="non-decreasing"):
        Scenario(**data)


def test_scenario_to_json_round_trip():
    sc = Scenario(**make_minimal_scenario())
    json_str = sc.to_json()
    assert '"schema_version"' in json_str
    assert '"create_escrow"' in json_str


def test_agent_index():
    sc = Scenario(**make_minimal_scenario())
    idx = sc.agent_index()
    assert "alice" in idx
    assert idx["alice"].address == "0xAlice"


# ---------------------------------------------------------------------------
# Simulation factory tests
# ---------------------------------------------------------------------------


def test_honest_trade_produces_valid_scenario():
    sc = build_honest_trade(seed=1, amount=5000)
    assert sc.schema_version == "1.0"
    assert len(sc.events) >= 1
    actions = [e.action.value for e in sc.events]
    assert "create_escrow" in actions


def test_dispute_scenario_produces_valid_scenario():
    sc = build_dispute_scenario(seed=2, amount=8000)
    assert sc.schema_version == "1.0"
    assert len(sc.events) >= 1


def test_griefing_scenario_produces_valid_scenario():
    sc = build_griefing_scenario(seed=3, amount=3000, max_dispute_duration=100)
    assert sc.schema_version == "1.0"
    events = [e.action.value for e in sc.events]
    assert "raise_dispute" in events


# ---------------------------------------------------------------------------
# Protocol params bounds
# ---------------------------------------------------------------------------


def test_fee_bps_out_of_range_raises():
    with pytest.raises(ValidationError):
        ProtocolParams(resolver_fee_bps=10_001)


def test_negative_appeal_window_raises():
    with pytest.raises(ValidationError):
        ProtocolParams(appeal_window_duration=-1)


# ---------------------------------------------------------------------------
# Hypothesis property: all generated scenarios are structurally valid
# ---------------------------------------------------------------------------


@given(scenarios())
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
)
def test_generated_scenarios_are_valid(scenario: Scenario):
    """Every scenario produced by the generator passes Pydantic validation."""
    # If Hypothesis can draw it, it's already validated by Pydantic.
    # These assertions double-check the structural invariants.
    assert scenario.schema_version == "1.0"
    assert len(scenario.agents) >= 1
    assert len(scenario.events) >= 1

    known_agent_ids = {a.id for a in scenario.agents}
    for ev in scenario.events:
        assert ev.agent in known_agent_ids, f"Event {ev.seq} references unknown agent {ev.agent}"

    times = [ev.time for ev in scenario.events]
    assert times == sorted(times), "Event times must be non-decreasing"

    seqs = [ev.seq for ev in scenario.events]
    assert seqs == sorted(seqs), "Event seqs must be monotonically increasing"
    assert seqs == list(range(len(seqs))), "Event seqs must be contiguous from 0"
