"""
Compatibility tests for interface-key and alias transformations at the
Python adversarial bridge boundary.

These tests are intentionally non-integration and focus on canonicalization
behavior only.
"""

from __future__ import annotations

from sew_sim.replay_runner import _normalize_scenario_keys


def test_normalize_kebab_case_scenario_and_event_keys() -> None:
    scenario = {
        "schema-version": "1.0",
        "scenario-id": "legacy-kebab",
        "initial-block-time": 1000,
        "protocol-params": {"resolver-fee-bps": 50},
        "agents": [{"id": "buyer", "address": "0xb", "type": "honest"}],
        "events": [
            {
                "seq": 0,
                "time": 1000,
                "agent": "buyer",
                "action": "create_escrow",
                "save-wf-as": "wf0",
                "params": {"token": "USDC", "to": "0xs", "amount": 100},
            },
            {
                "seq": 1,
                "time": 1060,
                "agent": "buyer",
                "action": "raise_dispute",
                "params": {"workflow-id": "wf0"},
            },
            {
                "seq": 2,
                "time": 1120,
                "agent": "resolver",
                "action": "execute_resolution",
                "params": {"workflow-id": "wf0", "is-release": True},
            },
        ],
    }

    normalized = _normalize_scenario_keys(scenario)

    assert normalized["schema_version"] == "1.0"
    assert normalized["scenario_id"] == "legacy-kebab"
    assert normalized["initial_block_time"] == 1000
    assert normalized["protocol_params"]["resolver_fee_bps"] == 50

    ev0 = normalized["events"][0]
    ev1 = normalized["events"][1]
    ev2 = normalized["events"][2]

    assert ev0["save_id_as"] == "wf0"
    assert "save_wf_as" not in ev0
    assert ev1["params"]["workflow_id"] == "wf0"
    assert ev2["params"]["workflow_id"] == "wf0"
    assert ev2["params"]["is_release"] is True


def test_preserve_existing_save_id_as_over_legacy_alias() -> None:
    scenario = {
        "schema_version": "1.0",
        "scenario_id": "alias-precedence",
        "events": [
            {
                "seq": 0,
                "time": 1000,
                "agent": "buyer",
                "action": "create_escrow",
                "save_id_as": "canonical",
                "save_wf_as": "legacy",
                "params": {"token": "USDC", "to": "0xs", "amount": 100},
            }
        ],
    }

    normalized = _normalize_scenario_keys(scenario)
    ev0 = normalized["events"][0]

    assert ev0["save_id_as"] == "canonical"
    # legacy key can remain present only if originally snake_case; canonical key wins
    assert ev0["save_wf_as"] == "legacy"
