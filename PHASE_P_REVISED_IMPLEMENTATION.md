# Phase P Revised: Corrected Sequential Appeal Model Implementation

**Date**: February 13, 2026  
**Status**: ✅ IMPLEMENTATION COMPLETE  
**Location**: `src/resolver_sim/model/` and `src/resolver_sim/sim/`

---

## What Was Built

Phase P Revised corrects the Phase P Lite model mismatch by implementing the **actual** contract architecture:

- ✅ Single-resolver sequential appeals (not 3-resolver parallel panel)
- ✅ Information cascade dynamics (not herding coordination)
- ✅ Escalation economics (not majority voting game)
- ✅ Time pressure effects (not attention budget games)

---

## Module 1: Decision Quality (12.5K)

**File**: `src/resolver_sim/model/decision_quality.clj`

### Purpose
Model per-round decision accuracy under time constraints and evidence availability.

### Key Functions

**Round Configurations**:
- Round 0 (Initial Resolver)
  - Time: 24 hours
  - Baseline accuracy: 70%
  - Evidence: Limited
  - Task: Fast decision under deadline

- Round 1 (Senior Resolver)
  - Time: 48 hours
  - Baseline accuracy: 85%
  - Evidence: Full (can review prior)
  - Task: Thorough review

- Round 2 (External/Kleros)
  - Time: Unlimited
  - Baseline accuracy: 95%
  - Evidence: Complete
  - Task: Final arbitration

**Core Calculations**:
```clojure
(decision-accuracy-by-round round time-pressure-factor)
→ Returns accuracy [0.5 - 1.0]

(detect-error-in-prior-decision round prior-round prior-was-wrong? difficulty evidence)
→ Returns detection probability

(honest-resolver-decision rng round ground-truth time-pressure ...)
→ Returns decision (true/false)

(simulate-round rng round ground-truth resolver-honest? ...)
→ Returns {:decision, :was-error, :round, :resolver-honest}

(simulate-full-appeal rng ground-truth resolver-corruption time-pressures ...)
→ Returns {:final-decision, :rounds, :escalation-count, :was-error}
```

### Model Design

**Time Pressure Impact**:
- Accuracy loss quadratic with time pressure
- More time = better accuracy (diminishing returns)
- Models: Resolvers must trade off speed vs thoroughness

**Evidence Availability**:
- Round 0: Time-limited, can't fully review
- Rounds 1+: Can see prior decision + new evidence
- Bonus accuracy for review access (10-15%)

**Error Detection**:
- Hard cases: 20% detection probability
- Medium cases: 60% detection probability
- Easy cases: 95% detection probability
- Later rounds detect easier (more time, more expertise)

---

## Module 2: Information Cascades (8.6K)

**File**: `src/resolver_sim/model/information_cascade.clj`

### Purpose
Model information cascade dynamics where later resolvers see prior decisions.

### Key Functions

**Cascade Theory**:
```clojure
(cascade-following-probability prior-alignment reputation-weight confidence)
→ Returns probability [0.0 - 1.0] of following prior

(detect-cascade-error rounds-in-cascade evidence-strength time-available)
→ Returns probability of breaking cascade

(information-cascade-risk num-rounds reputation-weight evidence-quality)
→ Returns risk score [0.0 - 1.0]

(analyze-cascade-trajectory ground-truth round-decisions reputation-weight evidence)
→ Returns comprehensive cascade analysis
```

### Model Design

**Cascade Mechanics**:
- Based on Banerjee (1992) and Welch (1992) information cascade theory
- NOT panel herding (which requires simultaneous voting)
- Instead: Sequential reviews with prior decision visibility

**Following Behavior**:
- Honest resolver sees prior decision
- Reputational pressure to agree or escalate
- Aligns with prior alignment (agrees if prior seems right)
- Reputation weight parameter [0.0 - 1.0]

**Cascade Breaking**:
- External evidence can break cascade
- More rounds = stickier cascade
- Evidence strength helps break locks
- Time pressure reduces ability to break

**Prevention Strategies**:
- Reputation weighting (reduce weight by 50%)
- Evidence oracles (reduce weight by 70%)
- Juror rotation (reduce weight by 60%)
- Challenge periods (reduce weight by 40%)
- Combined (reduce weight by 80%)

### Theory Connection

Information cascades arise when:
1. Sequential decision-makers (✓ appeals system)
2. Each sees prior outcomes (✓ later rounds review prior)
3. Reputational pressure to follow (✓ cascades sticky)
4. But can be broken by evidence (✓ honesty incentivized)

---

## Module 3: Escalation Economics (10K)

**File**: `src/resolver_sim/model/escalation_economics.clj`

### Purpose
Model the economic incentives of sequential corruption and escalation.

### Key Functions

**Stake & Bond Calculations**:
```clojure
(resolver-stake-at-round round config)
→ Returns stake amount in wei
   R0: 10,000 | R1: 20,000 | R2: 30,000

(appeal-bond-at-round from-round config)
→ Returns bond cost to escalate

(total-appeal-cost-to-round target-round config)
→ Returns cumulative cost to reach round

(slashing-loss-if-wrong round config)
→ Returns loss if resolver is slashed

(bribe-cost-to-corrupt-resolver round appeal-probability config)
→ Returns cost to bribe resolver
```

**Attack Analysis**:
```clojure
(attacker-roi-per-corruption round dispute-value appeal-probability ...)
→ Returns {:bribe-cost, :expected-profit, :roi-percentage, :profitable?}

(sequential-escalation-attack-cost dispute-value r0-corruption r1-corruption ...)
→ Returns total cost for multi-level attack

(compare-attack-costs dispute-value config)
→ Returns cost comparison and attacker's preferred route
```

### Model Design

**Default Configuration**:
- Resolver stakes: 10K (R0) → 20K (R1) → 30K (R2)
- Appeal bonds: Escalate with multiplier (1.5x per level)
- Slashing: 50% of stake if wrong
- Bond escalation: Becomes more expensive to appeal

**Corruption Cost**:
- Must pay resolver enough to overcome slashing risk
- Must account for probability of escalation
- Each level is independent corruption (temporal game)

**ROI Analysis**:
- Calculates attacker profit vs bribe cost
- Negative ROI = attack not economical
- Positive ROI = system vulnerable

**Escalation Security**:
- Higher stake per round
- Appeal bond cost accumulates
- External resolver (Kleros) not corruptible
- Sequential attacks require multiple independent bribes

---

## Module 4: Test Harness (11.5K)

**File**: `src/resolver_sim/sim/phase_p_revised.clj`

### Purpose
Run full parametric tests of sequential appeal system.

### Test Parameters

**3D Parameter Sweep**:
- Time pressure: [0.5, 1.0, 1.5] (1.0 = normal)
- Reputation weight: [0.0, 0.3, 0.6] (pressure to follow)
- Evidence quality: [0.3, 0.6, 1.0] (case clarity)
- Appeal probability: [0.1, 0.3, 0.5] (escalation likelihood)
- Total: 3 × 3 × 3 × 3 = 81 scenarios

### Scenario Classification

**Scenario A**: Robust (error rate < 15%)
- System handles conditions well
- Cascade risk manageable
- No intervention needed

**Scenario B**: Acceptable (error rate 15-25%)
- System handles most conditions
- Some cascade risk
- Monitoring recommended

**Scenario C**: Fragile (error rate > 25%)
- System struggles
- High cascade risk
- Intervention needed

### Key Metrics

```
Per scenario:
├─ Correct rate: % of decisions match ground truth
├─ Error rate: % of decisions wrong
├─ Hard-case error rate: Error rate on ambiguous cases
├─ Escalation count: Avg appeals per dispute
├─ Cascade risk: Information cascade probability
└─ Scenario class: A/B/C rating
```

---

## Implementation Status

### ✅ Completed

- [x] `decision_quality.clj` - Per-round models complete
- [x] `information_cascade.clj` - Cascade dynamics complete
- [x] `escalation_economics.clj` - Economic analysis complete
- [x] `phase_p_revised.clj` - Test harness complete
- [x] Module testing - All functions verified
- [x] Git commit - All code committed

### ⏳ Pending

- [ ] Full parameter sweep (81 scenarios, 100 trials each)
- [ ] Results analysis
- [ ] Confidence assessment
- [ ] Stakeholder brief with corrected findings

### 📋 Next Steps

1. **Run Full Test Suite**
   ```bash
   cd /home/user/Code/sew-simulation
   clojure -M:test  # Will run phase-p-revised-sweep
   ```

2. **Analyze Results**
   - Count scenarios by class (A/B/C)
   - Identify vulnerable conditions
   - Calculate actual confidence impact

3. **Generate Report**
   - Create `PHASE_P_REVISED_RESULTS.md`
   - Compare to Phase P Lite (to show correction)
   - Make mainnet recommendation

4. **Update Stakeholder Brief**
   - Explain model correction
   - Present real findings
   - Recommend path forward

---

## Key Differences from Phase P Lite

| Aspect | Phase P Lite (Invalid) | Phase P Revised (Correct) |
|--------|---|---|
| **Architecture** | 3-resolver panel | 1-resolver sequential |
| **Voting** | Parallel (same time) | Sequential (different rounds) |
| **Vulnerability** | Herding cascade | Information cascade |
| **Attack Model** | Corrupt 2/3 for majority | Corrupt per level |
| **Coordination** | Simultaneous vote-flipping | Prior decision following |
| **Root Cause** | Majority game | Reputation pressure |
| **Friction** | None (one-shot bribe) | High (escalation costs) |

---

## Theory & References

### Information Cascades
- Banerjee, A. V. (1992). "A Simple Model of Herd Behavior"
- Welch, I. (1992). "Sequential Sales, Learning, and Cascades"
- Explains how independent individuals can follow wrong prior decisions

### Sequential Appeals
- Common in traditional courts and dispute systems
- Each level (judge, appeals court) independently reviews
- Later levels see prior but make independent decision

### Time Pressure Decision-Making
- Decision quality degrades under time pressure (quadratic)
- Trade-off between accuracy and speed
- Models realistic constraints

---

## Running the Tests

### Quick Test (Single Scenario)
```bash
cd /home/user/Code/sew-simulation
clojure -M -e "(require '[resolver-sim.sim.phase-p-revised]) 
               (phase-p-revised/run-single-test 42 1.0 0.3 0.6 0.3 100)"
```

### Full Sweep (All 81 Scenarios)
```bash
clojure -M -e "(require '[resolver-sim.sim.phase-p-revised]) 
               (phase-p-revised/run-phase-p-revised-sweep)"
```

### Cascade Analysis
```bash
clojure -M -e "(require '[resolver-sim.sim.phase-p-revised]) 
               (phase-p-revised/analyze-cascade-vulnerability)"
```

---

## Conclusion

Phase P Revised corrects the Phase P Lite model mismatch by implementing the actual sequential single-resolver appeal system used in the contracts.

Key improvements:
- ✅ Tests realistic architecture
- ✅ Models actual vulnerabilities (cascades, not herding)
- ✅ Provides proper escalation game analysis
- ✅ Gives actionable insights for mainnet decision

Expected outcome: Real confidence assessment (likely 75-85%) based on actual system behavior, not theoretical false vulnerabilities.

---

**Status**: Implementation ready for testing  
**Next**: Run full parameter sweep and generate results  
**Timeline**: 1-2 hours for complete testing + analysis

