"""
generator.py — Hypothesis property-based scenario generators.

Provides Hypothesis strategies (composable generators) for:
  - Protocol parameter spaces
  - Agent populations
  - Full adversarial scenarios

Usage in tests:
  from hypothesis import given, settings
  from sew_sim.generator import scenarios

  @given(scenarios())
  @settings(max_examples=200)
  def test_scenario_is_valid(scenario):
      result = run_clojure_replay(scenario)
      assert result["outcome"] == "pass"

Design:
  - All strategies are composable Hypothesis strategies.
  - Scenarios are deterministic given the drawn seed.
  - Strategies generate structurally valid scenarios; semantic validity
    (e.g. correct workflow-id references) is enforced by construction.
"""

from __future__ import annotations

import random
import uuid
from typing import Any

import simpy
from hypothesis import strategies as st
from hypothesis.strategies import SearchStrategy

from .agents import (
    AttackingBuyer,
    AttackingResolver,
    GriefingBuyer,
    HonestBuyer,
    HonestResolver,
    OpportunisticBuyer,
    PlannedAction,
    SimContext,
)
from .schema import Agent, AgentType, Event, ProtocolParams, Scenario


# ---------------------------------------------------------------------------
# Primitive strategies
# ---------------------------------------------------------------------------

# Escrow amounts: 100 wei to 1M wei
escrow_amounts: SearchStrategy[int] = st.integers(min_value=100, max_value=1_000_000)

# Fee bps: 0 to 200 (0–2%)
fee_bps: SearchStrategy[int] = st.integers(min_value=0, max_value=200)

# Appeal window duration: 0 (no window) to 7 days
appeal_window: SearchStrategy[int] = st.one_of(
    st.just(0),  # most common: no appeal window
    st.integers(min_value=60, max_value=604_800),
)

# Max dispute duration: 1 day to 90 days
max_dispute_duration: SearchStrategy[int] = st.integers(
    min_value=86_400,
    max_value=7_776_000,
)

# PRNG seeds
seeds: SearchStrategy[int] = st.integers(min_value=0, max_value=2**32 - 1)


# ---------------------------------------------------------------------------
# ProtocolParams strategy
# ---------------------------------------------------------------------------


@st.composite
def protocol_params(draw: st.DrawFn) -> ProtocolParams:
    """Draw a valid ProtocolParams."""
    return ProtocolParams(
        resolver_fee_bps=draw(fee_bps),
        appeal_window_duration=draw(appeal_window),
        max_dispute_duration=draw(max_dispute_duration),
        appeal_bond_protocol_fee_bps=draw(st.integers(min_value=0, max_value=100)),
    )


# ---------------------------------------------------------------------------
# Agent set strategies
# ---------------------------------------------------------------------------


@st.composite
def honest_agents(draw: st.DrawFn, n: int = 2) -> list[Agent]:
    """Draw n honest agents with unique addresses."""
    return [
        Agent(
            id=f"agent_{i}",
            type=AgentType.honest,
            address=f"0xAgent{i:04X}",
        )
        for i in range(n)
    ]


@st.composite
def mixed_agents(
    draw: st.DrawFn,
    min_agents: int = 2,
    max_agents: int = 5,
    attacker_ratio: float = 0.3,
) -> list[Agent]:
    """
    Draw a mixed population of honest, attacker, and opportunistic agents.

    min_agents — minimum total agent count (excluding resolver)
    max_agents — maximum total agent count (excluding resolver)
    """
    n = draw(st.integers(min_value=min_agents, max_value=max_agents))
    agents = []
    for i in range(n):
        roll = draw(st.floats(min_value=0.0, max_value=1.0))
        if roll < attacker_ratio:
            agent_type = AgentType.attacker
        elif roll < attacker_ratio + 0.15:
            agent_type = AgentType.opportunistic
        else:
            agent_type = AgentType.honest
        agents.append(
            Agent(
                id=f"agent_{i}",
                type=agent_type,
                address=f"0xAgent{i:04X}",
            )
        )
    # Always add an honest resolver
    agents.append(
        Agent(id="resolver", type=AgentType.honest, address="0xResolver")
    )
    return agents


# ---------------------------------------------------------------------------
# Scenario builder: constructs from drawn params using SimPy
# ---------------------------------------------------------------------------


def _strategy_for_agent(agent: Agent, rng: random.Random) -> Any:
    """Return an appropriate strategy object for the given agent type."""
    if agent.type == AgentType.attacker:
        return draw_attacker_strategy(rng)
    elif agent.type == AgentType.opportunistic:
        return OpportunisticBuyer(
            dispute_prob=rng.uniform(0.1, 0.6),
            action_delay=rng.randint(5, 100),
        )
    else:
        return HonestBuyer(release_delay=rng.randint(10, 200))


def draw_attacker_strategy(rng: random.Random) -> Any:
    """Randomly pick an attacker strategy."""
    strategies = [
        AttackingBuyer(dispute_delay=rng.randint(0, 5)),
        GriefingBuyer(),
    ]
    return rng.choice(strategies)


@st.composite
def scenarios(
    draw: st.DrawFn,
    min_buyers: int = 1,
    max_buyers: int = 4,
    attacker_ratio: float = 0.4,
    token: str = "0xUSDC",
) -> Scenario:
    """
    Hypothesis strategy that generates a complete, valid SEW adversarial scenario.

    Each drawn scenario:
      - Has a unique scenario_id.
      - Has at least one buyer and one resolver.
      - Has monotonically non-decreasing event times.
      - References only valid agent IDs.
      - Is fully deterministic given the same seed.
    """
    seed = draw(seeds)
    pp = draw(protocol_params())
    n_buyers = draw(st.integers(min_value=min_buyers, max_value=max_buyers))
    amount = draw(escrow_amounts)
    rng = random.Random(seed)

    # Build agent list
    resolver_agent = Agent(id="resolver", type=AgentType.honest, address="0xResolver")
    seller = Agent(id="seller", type=AgentType.honest, address="0xSeller")
    buyers: list[Agent] = []
    for i in range(n_buyers):
        roll = rng.random()
        if roll < attacker_ratio:
            atype = AgentType.attacker
        elif roll < attacker_ratio + 0.15:
            atype = AgentType.opportunistic
        else:
            atype = AgentType.honest
        buyers.append(
            Agent(id=f"buyer_{i}", type=atype, address=f"0xBuyer{i:04X}")
        )

    all_agents = buyers + [seller, resolver_agent]

    # Run SimPy simulation
    env = simpy.Environment()
    ctx = SimContext(
        token=token,
        resolver_address=resolver_agent.address,
        rng=rng,
        protocol_params=pp.model_dump(),
    )
    wf_counter: list[int] = [0]
    raw_events: list[tuple[float, PlannedAction]] = []
    honest_resolver = HonestResolver(resolution_delay=rng.randint(5, 50))
    resolver_queue: list[tuple[float, int, bool]] = []

    def buyer_proc(buyer: Agent, strategy: Any, amount_: int):
        actions = strategy.plan(
            agent_id=buyer.id,
            counterparty_addr=seller.address,
            amount=amount_,
            ctx=ctx,
            wf_counter=wf_counter,
        )
        actions = sorted(actions, key=lambda a: a.delay)
        current = 0.0
        for pa in actions:
            wait = pa.delay - current
            if wait > 0:
                yield env.timeout(wait)
            current = pa.delay
            raw_events.append((env.now, pa))
            # Schedule resolver response for disputes
            if pa.action == "raise_dispute":
                wf_id = pa.params.get("workflow_id", 0)
                t = env.now + honest_resolver.resolution_delay
                resolver_queue.append((t, wf_id, True))

    def resolver_proc():
        while True:
            if resolver_queue:
                target_t, wf_id, is_release = resolver_queue.pop(0)
                wait = max(0.0, target_t - env.now)
                if wait > 0:
                    yield env.timeout(wait)
                pa = honest_resolver.plan_resolution(
                    agent_id=resolver_agent.id,
                    workflow_id=wf_id,
                    is_release=is_release,
                )
                raw_events.append((env.now, pa))
            else:
                yield env.timeout(1)
                if env.now > 50_000:
                    break

    for buyer in buyers:
        buyer_amount = amount + rng.randint(0, 1000)
        strategy_obj = _strategy_for_agent(buyer, rng)
        env.process(buyer_proc(buyer, strategy_obj, buyer_amount))

    env.process(resolver_proc())

    max_sim_time = max(pp.max_dispute_duration, 5_000)
    env.run(until=min(max_sim_time, 50_000))

    # Sort and assign seq numbers
    initial_block_time = draw(st.integers(min_value=0, max_value=10_000))
    sorted_raw = sorted(raw_events, key=lambda x: x[0])
    events = [
        Event(
            seq=i,
            time=initial_block_time + int(sim_time),
            agent=pa.agent_id,
            action=pa.action,  # type: ignore[arg-type]
            params=pa.params,
        )
        for i, (sim_time, pa) in enumerate(sorted_raw)
    ]

    if not events:
        # Degenerate: no events (rare); return a minimal valid scenario
        buyer = buyers[0]
        events = [
            Event(
                seq=0,
                time=initial_block_time,
                agent=buyer.id,
                action="create_escrow",  # type: ignore[arg-type]
                params={
                    "token": token,
                    "to": seller.address,
                    "amount": amount,
                    "custom_resolver": resolver_agent.address,
                },
            )
        ]
        wf_counter[0] = 1

    return Scenario(
        scenario_id=str(uuid.uuid4()),
        seed=seed,
        agents=all_agents,
        protocol_params=pp,
        initial_block_time=initial_block_time,
        events=events,
    )
