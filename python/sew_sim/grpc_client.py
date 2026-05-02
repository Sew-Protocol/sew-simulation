"""
Thin gRPC client for the SEW SimulationEngine.

Uses grpcio's channel.unary_unary with custom JSON serializers — no protoc
compilation is required.  The Clojure server and this client agree on a
snake_case JSON wire format.

Authority model: Clojure is the sole source of truth.
  - This client sends events; it never computes state or enforces invariants.
  - World state is received per-step as a world_view snapshot.
"""

from __future__ import annotations

import json
import uuid
from contextlib import contextmanager
from typing import Any

import grpc


# ---------------------------------------------------------------------------
# Serializers
# ---------------------------------------------------------------------------

def _encode(obj: Any) -> bytes:
    return json.dumps(obj).encode("utf-8")


def _decode(data: bytes) -> Any:
    return json.loads(data.decode("utf-8"))


# ---------------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------------

_SERVICE = "sew.simulation.SimulationEngine"


class SimulationClient:
    """
    Thin wrapper around a gRPC channel for the SEW SimulationEngine.

    All methods are synchronous unary calls.  For streaming adversarial use,
    call step() repeatedly from your own loop (see live_runner.py).

    Example::

        with SimulationClient() as client:
            r = client.start_session("s1", agents=[...])
            assert r["ok"]
            r = client.step("s1", {"seq": 0, "time": 1000, "agent": "buyer",
                                   "action": "create_escrow",
                                   "params": {"token": "USDC", "to": "0xabc",
                                              "amount": 1000}})
            assert r["result"] == "ok"
    """

    def __init__(self, host: str = "localhost", port: int = 7070):
        self._channel = grpc.insecure_channel(f"{host}:{port}")
        self._start = self._channel.unary_unary(
            f"/{_SERVICE}/StartSession",
            request_serializer=_encode,
            response_deserializer=_decode,
        )
        self._step = self._channel.unary_unary(
            f"/{_SERVICE}/Step",
            request_serializer=_encode,
            response_deserializer=_decode,
        )
        self._destroy = self._channel.unary_unary(
            f"/{_SERVICE}/DestroySession",
            request_serializer=_encode,
            response_deserializer=_decode,
        )

    # ------------------------------------------------------------------
    # RPC methods
    # ------------------------------------------------------------------

    def start_session(
        self,
        session_id: str,
        agents: list[dict],
        protocol_params: dict | None = None,
        initial_block_time: int = 1000,
    ) -> dict:
        """
        Allocate a new simulation session on the Clojure server.

        agents — list of dicts: [{"id": "buyer1", "address": "0x...", "type": "honest"}]
        Returns {"session_id": str, "ok": bool, "error": str|None}
        """
        return self._start({
            "session_id": session_id,
            "agents": agents,
            "protocol_params": protocol_params or {},
            "initial_block_time": initial_block_time,
        })

    def step(self, session_id: str, event: dict) -> dict:
        """
        Execute one event against the session's canonical world state.

        event — dict:
          {"seq": int, "time": int, "agent": str, "action": str, "params": dict}

        Returns:
          {"session_id": str,
           "result": "ok"|"rejected"|"invariant_violated"|"error",
           "world_view": dict|None,   # lean world snapshot
           "trace_entry": dict|None,  # full step trace
           "halted": bool,
           "error": str|None}

        The workflow_id assigned by a create_escrow action is in:
          response["trace_entry"]["extra"]["workflow_id"]
        """
        return self._step({"session_id": session_id, "event": event})

    def destroy_session(self, session_id: str) -> dict:
        """Free session resources. Returns {"session_id": str, "ok": bool}."""
        return self._destroy({"session_id": session_id})

    # ------------------------------------------------------------------
    # Context manager
    # ------------------------------------------------------------------

    def close(self) -> None:
        self._channel.close()

    def __enter__(self) -> "SimulationClient":
        return self

    def __exit__(self, *_args: Any) -> None:
        self.close()


# ---------------------------------------------------------------------------
# Convenience: session context manager
# ---------------------------------------------------------------------------

@contextmanager
def managed_session(
    client: SimulationClient,
    agents: list[dict],
    protocol_params: dict | None = None,
    initial_block_time: int = 1000,
    session_id: str | None = None,
):
    """
    Context manager that creates a session on enter and destroys it on exit.

    Usage::

        with managed_session(client, agents, session_id="my-run") as sid:
            resp = client.step(sid, event)
    """
    sid = session_id or str(uuid.uuid4())
    resp = client.start_session(sid, agents, protocol_params, initial_block_time)
    if not resp.get("ok"):
        raise RuntimeError(f"StartSession failed: {resp.get('error')}")
    try:
        yield sid
    finally:
        client.destroy_session(sid)
