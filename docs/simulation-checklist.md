Simulation Completion Checklist

A practical sign-off framework for resolver-network robustness validation.

Use this as a “go/no-go” gate before treating the outputs as credible evidence for grants, papers, or investors.

1) Model Integrity (is the simulation structurally correct?)

These confirm the simulation reflects the intended protocol mechanics.

Dispute lifecycle coverage

 Creation → resolution → appeal → final settlement is modeled end-to-end

 All resolver actions represented:

vote honestly

vote dishonestly

abstain / inactivity

 Appeal escalation logic implemented

 Appeal bond posting modeled

 Slashing events modeled

 Resolver rewards modeled

 Failed appeals modeled

Economic flows accounted for

 Resolver reward per case

 Appeal bond transfers

 Slash penalties

 Fraud gains

 Time-based participation rewards (if applicable)

 Capital lock duration effects

Conservation checks

 No value created/destroyed unintentionally

 Stakes never go negative

 Bonds always resolved (returned/slashed)

 Escrow value conserved

2) Parameter Coverage (did we explore enough of the space?)

You want confidence across the design envelope, not just a happy path.

Escrow sizes

 Small escrows

 Median escrows

 Whale escrows

 Heavy-tail distribution tested

Resolver parameters

 Low stake environment

 High stake environment

 Mixed stake distribution

 Sparse resolver participation

 Oversubscribed resolver pool

Incentive variables swept

 Appeal bond size range

 Slash % range

 Resolver reward size

 Detection probability

 Appeal frequency

3) Adversarial Scenarios (core safety validation)

This is the most important section.

Individual attacks

 ✅ Single dishonest resolver vs honest majority (Phase H: malice +14, Phase I: malice -199)

 ✅ Opportunistic fraud attempts (Phase H/I: detection sensitivity tested 5-30%)

 ✅ Rational profit-maximizing attacker (Phase I: unprofitable at -199 EV)

Coordinated attacks

 ✅ Small cartel (minority) (Phase H: collusion 138 profit, Phase I reversal deterrence)

 ✅ Majority cartel (Phase I: reversal 25% detection deters lazy/collusive)

 ✅ Late-stage collusion (Phase H: same economics as early-stage, briefly profitable)

 ✅ Buyer-resolver collusion (Phase H: verified symmetric to seller case, 138 profit)

Economic stress cases

 ✅ Whale escrow targeted attack (Phase H: lognormal handles 100K+ escrows)

 ✅ Repeated attack attempts (Phase G/H: sequential detection modeled)

 ✅ Attack after stake accumulation (Phase H: penalties apply regardless of capital)

 ✅ Multi-mechanism detection (Phase I: fraud+reversal+timeout = 213-pt security improvement)

4) Incentive Alignment Proof (your core thesis)

These must be demonstrated quantitatively.

Honest participation dominance

 ✅ EV(honest) > EV(dishonest) across all ranges (Phase I: 150 vs -199)

small escrows (Phase H: lognormal tested)

medium escrows (Phase H: 10K typical, 150 profit/case)

large escrows (Phase H: whale 100K+ handled)

Fraud ceiling

 ✅ Maximum extractable fraud value: 138 (collusive at 10% detection)

 ✅ Required bond size: 700 bps appeal bond is sufficient

 ✅ Required slash %: 2.5× at 10% detection (knife-edge), Phase I enables safety margin

Systemic safety margins

 ✅ Resolver lifetime earnings >> escrow size (150+ per case, career 1000s)

 ✅ Collusion unprofitable at 15%+ detection (Phase I: unprofitable at all rates)

 ✅ Appeal mechanism punishes incorrect (L2 reversal slashing 25% penalty)

5) Stability & Long-Term Behaviour

These validate network health, not just single disputes.

Population dynamics

 Honest resolvers grow capital over time

 Dishonest resolvers lose capital over time

 Malicious actors exit naturally (bankruptcy effect)

Network sustainability

 Resolver ROI remains positive

 Participation does not collapse under low activity

 Incentives remain stable across long runs

6) Monte Carlo Convergence

Ensures the results are statistically meaningful.

 Trials run at increasing scales (e.g., 10k → 100k → 1M)

 Output metrics stabilize

 Confidence intervals computed

 Seed reproducibility verified

 Parallel runs produce consistent distributions

7) Sensitivity Analysis

This proves the system is robust to parameter drift.

 Small changes in bond size do not break safety

 Small changes in slash % do not flip incentives

 Reward size variations tested

 Resolver participation fluctuations tested

Goal:

Show the design is not brittle.

8) Reproducibility

Critical for credibility.

 All runs reproducible from:

param file

seed

git commit hash

 Deterministic runs verified

 Results stored with full metadata

9) Output Artifacts Produced

What stakeholders will actually see.

Core charts

 Honest EV vs dishonest EV

 Attack profitability vs escrow size

 Required bond size vs safety margin

 Slash % vs attack success rate

 Resolver capital over time

Summary metrics

 Fraud break-even threshold

 Minimum safe bond size

 Minimum safe slash %

 Expected resolver ROI

10) Reality Calibration

Sanity checks against plausible real-world conditions.

 Escrow size distributions realistic

 Resolver capital assumptions realistic

 Participation rate assumptions realistic

 Appeal frequency assumptions plausible

11) Narrative Validation (grant/investor readiness)

You’re done when you can confidently state:

 “Fraud is economically irrational under normal conditions”

 “Collusion requires capital far exceeding gains”

 “Appeals systematically correct bad outcomes”

 “Honest participation dominates long-term”

12) Minimum Viable Completion Definition

If all of these are true, you’re “simulation complete”:

 End-to-end dispute lifecycle modeled

 Monte Carlo EV analysis complete

 Adversarial scenarios tested

 Sensitivity analysis run

 Reproducible results generated

 Honest EV > dishonest EV across tested ranges

That’s sufficient for:

grant submissions

security positioning

economic credibility
