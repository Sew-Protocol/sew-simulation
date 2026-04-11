"""
cli.py — Command-line interface for the SEW scenario generator.

Usage:
  sew-gen honest   --seed 42 --amount 10000 --output scenario.json
  sew-gen dispute  --seed 42 --amount 10000 --output scenario.json
  sew-gen griefing --seed 42 --amount 5000  --output scenario.json
  sew-gen batch    --seed 42 --count 50     --out-dir ./scenarios/

The generated JSON files are consumed by the Clojure replay engine:
  clojure -M:run replay-scenario scenario.json
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .schema import Scenario
from .simulation import (
    build_dispute_scenario,
    build_griefing_scenario,
    build_honest_trade,
)


def _write_scenario(scenario: Scenario, output: str | None, out_dir: str | None = None, idx: int = 0) -> Path:
    """Serialize scenario to JSON and write to disk."""
    if output:
        path = Path(output)
    elif out_dir:
        Path(out_dir).mkdir(parents=True, exist_ok=True)
        path = Path(out_dir) / f"scenario_{idx:04d}_{scenario.seed}.json"
    else:
        path = Path(f"scenario_{scenario.seed}.json")

    path.write_text(scenario.to_json(indent=2))
    return path


def cmd_honest(args: argparse.Namespace) -> None:
    scenario = build_honest_trade(
        seed=args.seed,
        amount=args.amount,
        token=args.token,
        release_delay=args.release_delay,
    )
    path = _write_scenario(scenario, args.output)
    print(f"Written: {path}  ({len(scenario.events)} events)")


def cmd_dispute(args: argparse.Namespace) -> None:
    scenario = build_dispute_scenario(
        seed=args.seed,
        amount=args.amount,
        token=args.token,
    )
    path = _write_scenario(scenario, args.output)
    print(f"Written: {path}  ({len(scenario.events)} events)")


def cmd_griefing(args: argparse.Namespace) -> None:
    scenario = build_griefing_scenario(
        seed=args.seed,
        amount=args.amount,
        token=args.token,
        max_dispute_duration=args.max_dispute_duration,
    )
    path = _write_scenario(scenario, args.output)
    print(f"Written: {path}  ({len(scenario.events)} events)")


def cmd_batch(args: argparse.Namespace) -> None:
    """Generate a batch of mixed scenarios for corpus building."""
    import random

    rng = random.Random(args.seed)
    builders = [build_honest_trade, build_dispute_scenario, build_griefing_scenario]

    for i in range(args.count):
        seed_i = rng.randint(0, 2**32 - 1)
        builder = builders[i % len(builders)]
        try:
            if builder is build_griefing_scenario:
                scenario = builder(seed=seed_i, amount=args.amount)
            else:
                scenario = builder(seed=seed_i, amount=args.amount)
            path = _write_scenario(scenario, output=None, out_dir=args.out_dir, idx=i)
            print(f"[{i+1}/{args.count}] {path.name}  ({len(scenario.events)} events)")
        except Exception as e:
            print(f"[{i+1}/{args.count}] SKIPPED (seed={seed_i}): {e}", file=sys.stderr)

    print(f"\nDone. {args.count} scenarios written to {args.out_dir}")


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="sew-gen",
        description="SEW adversarial scenario generator (Phase 1 — JSON export)",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # Common args
    def add_common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--seed", type=int, default=42, help="PRNG seed")
        p.add_argument("--amount", type=int, default=10_000, help="Escrow amount (wei)")
        p.add_argument("--token", type=str, default="0xUSDC", help="Token address")
        p.add_argument("--output", type=str, default=None, help="Output file path")

    # honest
    p_honest = sub.add_parser("honest", help="Happy-path scenario (no dispute)")
    add_common(p_honest)
    p_honest.add_argument("--release-delay", type=int, default=50, metavar="SECS")
    p_honest.set_defaults(func=cmd_honest)

    # dispute
    p_dispute = sub.add_parser("dispute", help="Mixed attacker+honest buyers with resolver")
    add_common(p_dispute)
    p_dispute.set_defaults(func=cmd_dispute)

    # griefing
    p_grief = sub.add_parser("griefing", help="Griefing buyer — tests auto-cancel timeout")
    add_common(p_grief)
    p_grief.add_argument(
        "--max-dispute-duration", type=int, default=500, metavar="SECS"
    )
    p_grief.set_defaults(func=cmd_griefing)

    # batch
    p_batch = sub.add_parser("batch", help="Generate a batch of mixed scenarios")
    p_batch.add_argument("--seed", type=int, default=42)
    p_batch.add_argument("--amount", type=int, default=10_000)
    p_batch.add_argument("--count", type=int, default=10, help="Number of scenarios")
    p_batch.add_argument("--out-dir", type=str, default="./scenarios", metavar="DIR")
    p_batch.set_defaults(func=cmd_batch)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
