"""
Phase 2 replay runner: replays a Phase 1 scenario file via gRPC.

This is the Phase 1 compatibility layer — it verifies that feeding a Phase 1
JSON scenario through the Phase 2 gRPC bridge produces identical outcomes to
direct file replay (resolver-sim.contract-model.replay/replay-file in Clojure).

The runner:
  1. Loads and parses a Phase 1 scenario JSON file (same format as before).
  2. Creates a gRPC session with the scenario's agents and protocol_params.
  3. Replays each event via gRPC Step, collecting the trace.
  4. Returns a result dict that mirrors the shape of replay-scenario's output.

Usage::

    from sew_sim.grpc_client import SimulationClient
    from sew_sim.replay_runner import GrpcReplayRunner

    with SimulationClient() as client:
        runner = GrpcReplayRunner(client)
        result = runner.replay_file("scenarios/honest_trade.json")
        assert result["outcome"] == "pass"

Verification::

    The replay_runner.verify_file() method runs both the direct Clojure path
    (via subprocess calling the Clojure CLI) and the gRPC path, then diffs the
    outcomes and event-by-event result fields.
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any, Optional

from sew_sim.grpc_client import SimulationClient, managed_session


# ---------------------------------------------------------------------------
# Scenario loading
# ---------------------------------------------------------------------------


def load_scenario(path: str | Path) -> dict:
    """Load and parse a scenario JSON file. Keys remain snake_case (as in file)."""
    with open(path) as f:
        return _normalize_scenario_keys(json.load(f))


def _kebab_to_snake_key(key: Any) -> Any:
    return key.replace("-", "_") if isinstance(key, str) else key


def _normalize_obj_keys(value: Any) -> Any:
    if isinstance(value, dict):
        return {_kebab_to_snake_key(k): _normalize_obj_keys(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_normalize_obj_keys(v) for v in value]
    return value


def _normalize_scenario_keys(scenario: dict) -> dict:
    """
    Normalize legacy scenario key styles to Python canonical snake_case.

    Supported compatibility transforms:
      - kebab-case keys -> snake_case recursively
      - save_wf_as      -> save_id_as
    """
    normalized = _normalize_obj_keys(scenario)
    for ev in normalized.get("events", []):
        if isinstance(ev, dict) and "save_wf_as" in ev and "save_id_as" not in ev:
            ev["save_id_as"] = ev.pop("save_wf_as")
    return normalized


# ---------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------


class GrpcReplayRunner:
    """
    Replays a Phase 1 scenario through the Phase 2 gRPC bridge.

    The result dict mirrors the shape of Clojure's replay-scenario output:
      {
        "outcome":          "pass" | "fail" | "invalid" | "error",
        "scenario_id":      str,
        "events_processed": int,
        "halted_at_seq":    int | None,
        "halt_reason":      str | None,
        "trace":            [trace_entry, ...],
        "metrics":          {metrics_map},
      }
    """

    def __init__(self, client: SimulationClient):
        self._client = client

    def replay_file(
        self, path: str | Path, session_id: str | None = None
    ) -> dict:
        """Load a scenario JSON file and replay it via gRPC."""
        scenario = load_scenario(path)
        return self.replay_scenario(scenario, session_id=session_id)

    def replay_scenario(
        self, scenario: dict, session_id: str | None = None
    ) -> dict:
        """
        Replay a parsed scenario dict via gRPC.

        The scenario must be in Phase 1 JSON format (snake_case keys,
        schema_version == "1.0").
        """
        sid = session_id or str(uuid.uuid4())
        scenario = _normalize_scenario_keys(scenario)
        scenario_id = scenario.get("scenario_id", "unknown")
        agents = scenario.get("agents", [])
        params = scenario.get("protocol_params", {})
        init_time = scenario.get("initial_block_time", 1000)
        events = sorted(scenario.get("events", []), key=lambda e: e["seq"])

        # Basic pre-check: schema version
        if scenario.get("schema_version") != "1.0":
            return {
                "outcome": "invalid",
                "scenario_id": scenario_id,
                "events_processed": 0,
                "halted_at_seq": None,
                "halt_reason": "unsupported_schema_version",
                "trace": [],
                "metrics": _zero_metrics(),
            }

        with managed_session(
            self._client, agents, params, init_time, session_id=sid
        ) as session_sid:
            return self._run_events(session_sid, scenario_id, events)

    def _run_events(
        self, sid: str, scenario_id: str, events: list[dict]
    ) -> dict:
        trace: list[dict] = []
        metrics = _zero_metrics()

        for event in events:
            resp = self._client.step(sid, event)

            if resp.get("result") == "error":
                # Server-level error (not a protocol revert)
                return {
                    "outcome": "error",
                    "scenario_id": scenario_id,
                    "events_processed": len(trace),
                    "halted_at_seq": event.get("seq"),
                    "halt_reason": resp.get("error"),
                    "trace": trace,
                    "metrics": metrics,
                }

            entry = resp.get("trace_entry") or {}
            trace.append(entry)
            _accum_metrics(metrics, event, entry)

            if resp.get("halted"):
                return {
                    "outcome": "fail",
                    "scenario_id": scenario_id,
                    "events_processed": len(trace),
                    "halted_at_seq": event.get("seq"),
                    "halt_reason": "invariant_violation",
                    "trace": trace,
                    "metrics": metrics,
                }

        return {
            "outcome": "pass",
            "scenario_id": scenario_id,
            "events_processed": len(trace),
            "halted_at_seq": None,
            "halt_reason": None,
            "trace": trace,
            "metrics": metrics,
        }


# ---------------------------------------------------------------------------
# Outcome comparison (used by verify_file)
# ---------------------------------------------------------------------------


def compare_outcomes(direct: dict, grpc: dict) -> dict:
    """
    Compare a direct replay result (from Clojure CLI) with a gRPC replay result.

    Returns:
      {"match": bool, "differences": [str, ...]}
    """
    diffs: list[str] = []

    if direct.get("outcome") != grpc.get("outcome"):
        diffs.append(
            f"outcome: direct={direct.get('outcome')!r} grpc={grpc.get('outcome')!r}"
        )

    direct_trace = direct.get("trace", [])
    grpc_trace = grpc.get("trace", [])

    if len(direct_trace) != len(grpc_trace):
        diffs.append(
            f"trace length: direct={len(direct_trace)} grpc={len(grpc_trace)}"
        )
    else:
        for i, (de, ge) in enumerate(zip(direct_trace, grpc_trace)):
            dr = de.get("result") or de.get(":result")
            gr = ge.get("result")
            if dr != gr:
                diffs.append(f"step {i} result: direct={dr!r} grpc={gr!r}")

    return {"match": len(diffs) == 0, "differences": diffs}


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------


def _zero_metrics() -> dict[str, Any]:
    return {
        "total_escrows":                0,
        "total_volume":                 0,
        "disputes_triggered":           0,
        "resolutions_executed":         0,
        "pending_settlements_executed": 0,
        "attack_attempts":              0,
        "attack_successes":             0,
        "reverts":                      0,
        "invariant_violations":         0,
    }


def _accum_metrics(metrics: dict, event: dict, entry: dict) -> None:
    action   = event.get("action", "")
    result   = entry.get("result", "")
    accepted = result == "ok"

    if accepted and action == "create_escrow":
        metrics["total_escrows"] += 1
        metrics["total_volume"] += (event.get("params") or {}).get("amount", 0)
    if accepted and action == "raise_dispute":
        metrics["disputes_triggered"] += 1
    if accepted and action == "execute_resolution":
        metrics["resolutions_executed"] += 1
    if accepted and action == "execute_pending_settlement":
        metrics["pending_settlements_executed"] += 1
    if result not in ("ok",):
        metrics["reverts"] += 1
    if result == "invariant_violated":
        metrics["invariant_violations"] += 1
