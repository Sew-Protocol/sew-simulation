import json
import uuid
import time
import random
import grpc
import argparse
from collections import defaultdict


def _choose_action(active_ids, valid_bias=0.7):
    """Choose action with a bias toward state-valid transitions.

    - If no active escrow ids: prefer create_escrow.
    - If there are active ids: mostly choose workflow actions, sometimes random invalid probe.
    """
    if not active_ids:
        return "create_escrow"

    valid_actions = ["raise_dispute", "release", "sender_cancel"]
    random_actions = ["create_escrow", "raise_dispute", "release", "sender_cancel"]
    if random.random() < valid_bias:
        return random.choice(valid_actions)
    return random.choice(random_actions)


def _extract_candidate_id(response, fallback_ids):
    """Try to infer a workflow id from response payload fields."""
    trace_entry = response.get("trace_entry") or {}
    extra = trace_entry.get("extra") or {}
    params = trace_entry.get("params") or {}

    for source in (extra, params, trace_entry):
        for key in ("id", "workflow_id", "workflow-id"):
            if key in source:
                return source[key]

    return (fallback_ids[0] if fallback_ids else 0)


def _weighted_choice(candidates, rejection_counts, active_ids):
    """Pick candidate with simple bandit-style weighting.

    Boosts:
      - actions on existing active workflow ids
      - raise_dispute on active ids
    Penalties:
      - recently rejected (actor, action, id) tuples
    """
    if not candidates:
        return None

    weighted = []
    for c in candidates:
        actor = c.get("actor_id", "buyer")
        action = c.get("action")
        params = c.get("params", {}) or {}
        wf_id = params.get("id")

        score = 1.0
        if wf_id in active_ids:
            score += 0.75
        if action == "raise_dispute" and wf_id in active_ids:
            score += 0.5
        if action == "create_escrow" and len(active_ids) > 2:
            score -= 0.25

        key = (actor, action, wf_id)
        score -= min(0.8, 0.2 * rejection_counts[key])
        weighted.append((max(0.05, score), c))

    total = sum(w for w, _ in weighted)
    r = random.random() * total
    acc = 0.0
    for w, c in weighted:
        acc += w
        if acc >= r:
            return c
    return weighted[-1][1]


def _apply_suppression(candidates, suppressed_until, seq):
    """Filter out temporarily suppressed (actor, action, id) tuples."""
    filtered = []
    for c in candidates:
        actor = c.get("actor_id", "buyer")
        action = c.get("action")
        wf_id = (c.get("params") or {}).get("id")
        if seq < suppressed_until[(actor, action, wf_id)]:
            continue
        filtered.append(c)
    return filtered


def run_adversarial_session(
    target_effective_steps=20,
    max_attempts=80,
    guided_ratio=0.75,
    rejected_step_weight=0.15,
    seed=None,
):
    if seed is not None:
        random.seed(seed)

    channel = grpc.insecure_channel('127.0.0.1:50051')

    # Define custom JSON codec for gRPC calls to bridge pure JSON server
    def json_serializer(data):
        return json.dumps(data).encode('utf-8')
    def json_deserializer(data):
        return json.loads(data.decode('utf-8'))

    # Build descriptors for manual RPC call (bypassing proto marshalling)
    start_session = channel.unary_unary(
        '/sew.simulation.SimulationEngine/StartSession',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    step_session = channel.unary_unary(
        '/sew.simulation.SimulationEngine/Step',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    destroy_session = channel.unary_unary(
        '/sew.simulation.SimulationEngine/DestroySession',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    suggest_actions = channel.unary_unary(
        '/sew.simulation.SimulationEngine/SuggestActions',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    session_signals = channel.unary_unary(
        '/sew.simulation.SimulationEngine/SessionSignals',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )
    evaluate_payoff = channel.unary_unary(
        '/sew.simulation.SimulationEngine/EvaluatePayoff',
        request_serializer=json_serializer,
        response_deserializer=json_deserializer
    )

    session_id = str(uuid.uuid4())
    
    # 1. Initialize with both buyer/seller so role-specific transitions are possible.
    start_session({
        "session_id": session_id,
        "agents": [
            {"id": "buyer", "address": "0xbuyer", "type": "honest"},
            {"id": "seller", "address": "0xseller", "type": "honest"},
        ],
    })

    active_ids = []
    rejection_counts = defaultdict(int)
    suppressed_until = defaultdict(int)
    attempts = 0
    ok_count = 0
    rejected_count = 0
    effective_steps = 0.0
    
    try:
        # 2. Adversarial loop with effective-step budgeting
        seq = 0
        while attempts < max_attempts and effective_steps < target_effective_steps:
            print(f"Probing seq {seq}...")
            hints_buyer = suggest_actions({"session_id": session_id, "actor_id": "buyer"})
            hints_seller = suggest_actions({"session_id": session_id, "actor_id": "seller"})

            suggested = []
            if hints_buyer.get("ok"):
                suggested.extend(hints_buyer.get("suggested_actions", []))
            if hints_seller.get("ok"):
                suggested.extend(hints_seller.get("suggested_actions", []))

            suggested = _apply_suppression(suggested, suppressed_until, seq)

            use_guided = bool(suggested) and (random.random() < guided_ratio)
            if use_guided:
                choice = _weighted_choice(suggested, rejection_counts, active_ids)
                action = choice.get("action", _choose_action(active_ids))
                suggested_actor = choice.get("actor_id", "buyer")
                suggested_params = choice.get("params", {})
            else:
                # Chaos lane: deliberately explores beyond guided suggestions.
                action = _choose_action(active_ids)
                suggested_actor = random.choice(["buyer", "seller"])
                suggested_params = {}

            # Actor selection: alternate for create; mostly buyer for adversarial probes.
            if action == "create_escrow":
                actor = suggested_actor
                params = dict(suggested_params) if suggested_params else {"token": "USDC", "to": "0xseller", "amount": 5000}
            else:
                actor = suggested_actor
                target_id = random.choice(active_ids) if active_ids else 0
                params = dict(suggested_params) if suggested_params else {"id": target_id}
                params["id"] = params.get("id", target_id)
            
            res = step_session({
                "session_id": session_id,
                "event": {
                    "seq": seq,
                    "time": 1000 + seq*10,
                    "agent": actor,
                    "action": action,
                    "params": params,
                },
            })

            result = res.get("result")
            attempts += 1

            if result == "ok" and action == "create_escrow":
                new_id = _extract_candidate_id(res, active_ids)
                if new_id not in active_ids:
                    active_ids.append(new_id)
            
            if res.get("halted"):
                print(f"!!! Invariant Violation Detected at seq {seq}!")
                break
            else:
                err = res.get("error")
                if result == "ok":
                    ok_count += 1
                    effective_steps += 1.0
                    print(f"  Step {seq} {actor} {action} result: ok (active_ids={active_ids})")
                else:
                    rejected_count += 1
                    effective_steps += rejected_step_weight
                    rejection_key = (actor, action, params.get("id"))
                    rejection_counts[rejection_key] += 1
                    if rejection_counts[rejection_key] >= 2:
                        # Short cooldown to avoid repeatedly hammering same failing tuple.
                        suppressed_until[rejection_key] = max(suppressed_until[rejection_key], seq + 3)
                    print(f"  Step {seq} {actor} {action} result: {result} error={err} (active_ids={active_ids})")

                signals = session_signals({"session_id": session_id})
                payoff = evaluate_payoff({"session_id": session_id, "actor_id": "buyer"})
                if signals.get("ok") and payoff.get("ok"):
                    print(
                        "    signals:",
                        {
                            "block_time": signals.get("block_time"),
                            "pending_count": signals.get("pending_count"),
                            "active_workflow_ids": signals.get("active_workflow_ids"),
                        },
                        "payoff:",
                        {
                            "stake_locked": payoff.get("stake_locked"),
                            "claimable": payoff.get("claimable"),
                            "bond_locked": payoff.get("bond_locked"),
                            "net_pnl": payoff.get("net_pnl"),
                        },
                    )
            seq += 1

        print("\nRun summary:")
        print(
            {
                "attempts": attempts,
                "ok_count": ok_count,
                "rejected_count": rejected_count,
                "effective_steps": round(effective_steps, 3),
                "target_effective_steps": target_effective_steps,
                "max_attempts": max_attempts,
                "guided_ratio": guided_ratio,
                "rejected_step_weight": rejected_step_weight,
                "active_ids": active_ids,
            }
        )
            
    finally:
        destroy_session({"session_id": session_id})

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Adversarial SEW gRPC probe runner")
    parser.add_argument("--target-effective-steps", type=float, default=20)
    parser.add_argument("--max-attempts", type=int, default=80)
    parser.add_argument("--guided-ratio", type=float, default=0.75)
    parser.add_argument("--rejected-step-weight", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=None)
    args = parser.parse_args()

    run_adversarial_session(
        target_effective_steps=args.target_effective_steps,
        max_attempts=args.max_attempts,
        guided_ratio=args.guided_ratio,
        rejected_step_weight=args.rejected_step_weight,
        seed=args.seed,
    )
