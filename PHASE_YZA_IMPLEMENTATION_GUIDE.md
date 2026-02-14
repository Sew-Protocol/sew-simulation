# Phase Y/Z/AA Implementation Guide – Parallel Development (Feb 17-22)

**Decision**: Option B approved
**Timeline**: Mon Feb 17 – Sat Feb 22 (5 days)
**Structure**: 3 developers in parallel
**Goal**: 3 complete test harnesses + results by Saturday night

---

## Overview: Three Independent Harnesses

Each phase is self-contained. They do NOT interact. You can develop and test independently.

**Parallel execution**: All three complete in parallel (3-5 days wall-clock), not sequentially.

```
Dev A (Y) ────────────────────────┐
Dev B (Z) ────────────────────────┼──→ Saturday morning: Execute all
Dev C (AA)────────────────────────┘    Saturday evening: Interpret results
```

---

## Developer A: Phase Y – Evidence Fog & Attention Budgets

### What You're Testing

**Question**: Is truth actually cheap to verify? Or do resolvers burn out under cognitive load?

**Hypothesis to Falsify**:
```
"System maintains >75% accuracy even when:
  - 15% of disputes are ambiguous/hard to verify
  - Resolvers have limited time budgets (20 units/epoch)
  - Attackers can pay to increase evidence complexity
  - Deep verification is 5-10× more effortful than shallow check"
```

### Model Components to Implement

#### 1. Dispute Complexity Distribution (20 LOC)
```clojure
(defn dispute-complexity
  [difficulty-distribution]
  ;; Return a distribution of disputes with:
  ;; - 20% easy (5 evidence units, verify cost 1)
  ;; - 60% medium (15 evidence units, verify cost 3)
  ;; - 15% hard (30 evidence units, verify cost 8)
  ;; - 5% ambiguous (100 units, verify cost impossible)
  )
```

**Data structure**:
```clojure
{:dispute-id 1
 :complexity 0.3  ;; difficulty parameter (0-1)
 :evidence-units 15
 :verify-cost 3
 :truth-findable? true}
```

#### 2. Resolver Attention Budget (100 LOC)
```clojure
(defn allocate-budgets
  [n-resolvers disputes-assigned total-budget-per-resolver]
  ;; Each resolver has 20 units/epoch
  ;; Must allocate across assigned disputes
  ;; Returns: map of (resolver-id -> budget-per-dispute)
  )

(defn resolver-strategy
  [resolver budget-allocated dispute-complexity]
  ;; Resolver chooses: deep-verify | shallow-check | random-guess
  ;; Deep: 8-10 budget → 90% accuracy
  ;; Shallow: 2-3 budget → 70% accuracy
  ;; Random: 0 budget → 50% accuracy
  )
```

#### 3. Attacker Fog Investment (80 LOC)
```clojure
(defn attacker-fog-investment
  [dispute attacker-budget]
  ;; Attacker can pay to increase complexity:
  ;; +5 cost: Add 20% more evidence
  ;; +10 cost: Add false evidence layer
  ;; +20 cost: Make truth structurally ambiguous
  ;;
  ;; Returns: modified dispute with higher verify-cost
  )
```

#### 4. Test Harness (200-250 LOC)
```clojure
(defn test-phase-y-scenario
  [scenario-name seed n-resolvers n-disputes fog-level attacker-budget]
  ;; Run 1 epoch (or 10 epochs for more signal):
  ;; 1. Create disputes with fog-level ambiguity
  ;; 2. Assign to resolvers
  ;; 3. Let resolvers choose budgets
  ;; 4. Measure accuracy + exits
  ;; Returns: {:accuracy :exits :budget-spent}
  )

(defn run-phase-y-sweep
  []
  ;; 5 scenarios:
  ;; 1. Baseline (no fog, ample budget)
  ;; 2. High fog (15% ambiguous)
  ;; 3. Attacker fog (active fog investment)
  ;; 4. Load spike (2× disputes, tight budget)
  ;; 5. Cascading effort (low accuracy → low trust → reduced budget)
  ;;
  ;; Run each scenario 5 times (5 seeds)
  ;; Total: 25 trials
  )
```

### Pass Criteria

✅ **PASS if ALL of**:
- Accuracy >75% in high-fog scenario (not below 0.75)
- Resolver exit rate <30% (not more than 0.3)
- Attacker fog is unprofitable (EV < 0)

❌ **FAIL if ANY of**:
- Accuracy <75% in fog (suggests lazy strategy dominates)
- >30% resolvers exit (burnout from effort)
- Attacker fog is profitable (attackers incentivized to fog)

### Expected Output

```
═══════════════════════════════════════════════════════════
Phase Y: Evidence Fog & Attention Budgets
═══════════════════════════════════════════════════════════

Scenario 1: Baseline (no fog)
  Accuracy: 98.0% ✅
  Exits: 2% ✅
  Attacker EV: -1.0 ✅

Scenario 2: High fog (15% ambiguous)
  Accuracy: 82.5% ✅
  Exits: 8% ✅
  Attacker EV: -0.8 ✅

Scenario 3: Attacker fog
  Accuracy: 76.2% ✅
  Exits: 15% ✅
  Attacker EV: -0.5 ✅

Scenario 4: Load spike
  Accuracy: 75.1% ✅
  Exits: 22% ✅
  Attacker EV: -0.3 ✅

Scenario 5: Cascading effort
  Accuracy: 74.0% ❌ (below 75%)
  Exits: 35% ❌ (above 30%)
  Attacker EV: +0.2 ❌ (profitable)

═══════════════════════════════════════════════════════════
Results: 4/5 scenarios pass
Status: ⚠️ MARGINAL (Scenario 5 reveals burnout cascade)
```

### Implementation Checklist

- [ ] Load resolver_sim.model.rng namespace
- [ ] Create dispute-complexity distribution
- [ ] Implement resolver budget allocation
- [ ] Implement resolver strategy selection
- [ ] Implement attacker fog investment model
- [ ] Create 5 scenarios
- [ ] Build test harness
- [ ] Test with 1 seed first (quick iteration)
- [ ] Run full 5×5 sweep (25 trials)
- [ ] Verify output format matches template

### Code Template

```clojure
(ns resolver-sim.sim.phase-y
  (:require [resolver-sim.model.rng :as rng]))

(defn dispute-complexity [seed n]
  ;; Your implementation
  )

(defn test-phase-y-scenario [scenario-name seed budget fog-level]
  ;; Your implementation
  )

(defn run-phase-y-sweep []
  ;; Your implementation
  )
```

### Key Implementation Notes

- Use resolver_sim.model.rng for randomness (not built-in random)
- Each scenario should be independent (test with seed)
- Accuracy: (correct / total)
- Exits: (count who left / total)
- EV: (attacker win probability × reward) - (loss probability × bond)

---

## Developer B: Phase Z – Trust & Participation Reflexivity Loop

### What You're Testing

**Question**: Does system remain stable over time with external shocks? Or does it spiral?

**Hypothesis to Falsify**:
```
"System maintains stable participation (>70%) over 100 epochs
even when:
  - Market shock causes 40% temporary resolver withdrawal
  - Scam wave increases false positive rate
  - Trust is slow to recover (0.98 decay per epoch)
  - Participation depends on trust via sigmoid relationship"
```

### Model Components to Implement

#### 1. Trust State Machine (100 LOC)
```clojure
(defn update-trust
  [trust-t accuracy fp-rate variance resolution-time]
  ;; trust_{t+1} = trust_t × 0.98 + signals
  ;; Signals:
  ;;   +2% if accuracy > 85%
  ;;   -3% if accuracy < 65%
  ;;   -1% if fp-rate > 5%
  ;;   -2% if variance > threshold
  ;;   -1.5% if resolution-time > 5 days
  )
```

**State**:
```clojure
{:global-trust 0.75
 :cohort-trust {:A 0.72 :B 0.78}
 :accuracy 0.85
 :fp-rate 0.02
 :variance 0.08
 :resolution-time-avg 3.2}
```

#### 2. Participation Feedback Loop (120 LOC)
```clojure
(defn update-participation
  [participation-t trust-t]
  ;; participation_{t+1} = participation_t × 0.97 + re-entry
  ;; re-entry = 0.03 × sigmoid(trust_t - 0.6)
  ;;
  ;; At trust 0.7: ~85% stay + ~5% re-enter = 90% total
  ;; At trust 0.5: ~85% stay + ~0% re-enter = 85% total
  ;; At trust 0.3: ~85% stay - ~10% exit = 75% total
  ;; At trust 0.1: Collapse (below critical threshold)
  )
```

#### 3. Shock Events (80 LOC)
```clojure
(defn market-shock
  [state epoch-triggered]
  ;; At epoch-triggered: 40% of resolvers withdraw for 10 epochs
  ;; Then gradually return
  ;; Causes accuracy to drop (fewer honest resolvers)
  )

(defn scam-wave
  [state epoch-triggered]
  ;; At epoch-triggered: High-FP disputes arrive (ambiguous cases)
  ;; Increases false positive rate to 5-8%
  ;; Triggers trust damage
  ;; Lasts 15 epochs
  )
```

#### 4. Test Harness (150 LOC)
```clojure
(defn simulate-reflexive-loop
  [initial-participation initial-trust n-epochs shocks]
  ;; Run 100-200 epochs
  ;; Track participation and trust over time
  ;; Return full history for analysis
  )

(defn run-phase-z-sweep
  []
  ;; 5 scenarios:
  ;; 1. Baseline (no shocks, stable environment)
  ;; 2. Market shock (40% exit at epoch 30)
  ;; 3. Scam wave (high-FP at epoch 60)
  ;; 4. Combined (both shocks)
  ;; 5. Cascading (multiple small shocks)
  ;;
  ;; Run each scenario 5 times
  ;; Total: 25 trials
  )
```

### Pass Criteria

✅ **PASS if ALL of**:
- Recovery time <30 epochs after shock (system bounces back)
- Final participation >0.4 (no death spiral)
- No hysteresis (once recovered, stays recovered)

❌ **FAIL if ANY of**:
- <30% final participation (death spiral)
- Recovery never happens (stuck in low state)
- System drifts into low-trust equilibrium even without shock

### Expected Output

```
═══════════════════════════════════════════════════════════
Phase Z: Trust & Participation Reflexivity
═══════════════════════════════════════════════════════════

Scenario 1: Baseline (no shocks)
  Initial: participation=0.85, trust=0.75
  Final: participation=0.84, trust=0.76
  Status: ✅ Stable

Scenario 2: Market shock (epoch 30)
  Min participation: 0.50 (epoch 35)
  Recovery time: 18 epochs (epoch 53)
  Final: participation=0.82, trust=0.72
  Status: ✅ Recovers

Scenario 3: Scam wave (epoch 60)
  Min participation: 0.45 (epoch 65)
  Recovery time: 25 epochs (epoch 85)
  Final: participation=0.78, trust=0.68
  Status: ✅ Recovers slower but stable

Scenario 4: Combined shocks
  Min participation: 0.35 (epoch 75)
  Recovery time: 35 epochs (epoch 110)
  Final: participation=0.72, trust=0.60
  Status: ✅ Survives both, recovers

Scenario 5: Cascading shocks
  Min participation: 0.25 (epoch 95)
  Recovery: Never (stays <0.3)
  Final: participation=0.20, trust=0.15
  Status: ❌ Death spiral

═══════════════════════════════════════════════════════════
Results: 4/5 scenarios pass
Status: ⚠️ MARGINAL (Scenario 5 shows cascade vulnerability)
```

### Implementation Checklist

- [ ] Load rng namespace
- [ ] Implement trust update function
- [ ] Implement participation update function
- [ ] Implement market shock event
- [ ] Implement scam wave event
- [ ] Create 5 scenarios with different shock timing
- [ ] Build full 100-epoch simulator
- [ ] Create test harness
- [ ] Test with 1 seed first
- [ ] Run full 5×5 sweep (25 trials, 100 epochs each)
- [ ] Plot/analyze recovery times

### Code Template

```clojure
(ns resolver-sim.sim.phase-z
  (:require [resolver-sim.model.rng :as rng]))

(defn update-trust [state]
  ;; Your implementation
  )

(defn update-participation [participation trust]
  ;; Your implementation
  )

(defn simulate-reflexive-loop [epochs shocks]
  ;; Your implementation
  )

(defn run-phase-z-sweep []
  ;; Your implementation
  )
```

### Key Implementation Notes

- Trust is slow (0.98 decay per epoch) → recovery takes 20-40 epochs
- Participation has hysteresis (sticky when low)
- Look for "knee" effects (small shocks trigger large drops)
- Track min value over time (nadir of crisis)

---

## Developer C: Phase AA – Governance as Adversary

### What You're Testing

**Question**: Can attackers learn governance patterns and exploit them?

**Hypothesis to Falsify**:
```
"Governance bandwidth cannot be exploited by attacker learning,
even when:
  - Governance capacity is limited (3 disputes/epoch)
  - Governance prioritizes high-value disputes
  - Attackers observe patterns and learn thresholds
  - After 20 disputes, attackers discover 'grey zone'"
```

### Model Components to Implement

#### 1. Governance Capacity & Bias (150 LOC)
```clojure
(defn governance-will-intervene?
  [dispute-value]
  ;; Intervention probability based on value:
  ;; High (>$100K): 95%
  ;; Medium ($10-100K): 60%
  ;; Low (<$10K): 20%
  ;;
  ;; Also check capacity: only 3 can be reviewed per epoch
  ;;
  ;; Returns: true if governance will intervene
  )

(defn governance-bandwidth
  [epoch capacity]
  ;; How many disputes can governance review this epoch?
  ;; Capacity = 3
  ;; Fill order: High-value first (greedy)
  )
```

#### 2. Attacker Learning (120 LOC)
```clojure
(defn attacker-learns
  [history-of-outcomes epoch]
  ;; After 20 disputes, attacker has learned:
  ;; - "Governance ignores <$5K disputes" (true if >80% of low get through)
  ;; - "Governance overloaded on Tuesdays" (timing patterns)
  ;; - "Governance focused on >$50K" (priority patterns)
  ;;
  ;; Returns: learned patterns / attack strategy
  )

(defn attacker-strategy-evolution
  [learned-patterns]
  ;; Epochs 1-20: Random attacks
  ;; Epochs 21+: Concentrate on learned "grey zone"
  ;; E.g., if low-value ignored → only attack low-value disputes
  )
```

#### 3. Grey Zone Detection (100 LOC)
```clojure
(defn measure-grey-zone
  [attack-results]
  ;; Does a "grey zone" emerge?
  ;; = Set of attacks where:
  ;;   - Governance ignores (low intervention rate)
  ;;   - Attacker success rate >15%
  ;;   - Attacker concentrates volume there
  )
```

#### 4. Test Harness (150 LOC)
```clojure
(defn simulate-governance-response
  [governance-capacity governance-bias attacker-learning?]
  ;; Run 50 attack opportunities
  ;; Track: which get governed, which succeed, attacker learning
  ;; Returns: attacker win-rate by era (1-20 vs 21-50)
  )

(defn run-phase-aa-sweep
  []
  ;; 5 scenarios:
  ;; 1. Baseline (high capacity, no bias, attacker naive)
  ;; 2. Limited governance (capacity 2, attacker learns)
  ;; 3. Biased governance (capacity 3, 90% to high-value)
  ;; 4. Overloaded + biased (capacity 2, bias, attacker learns)
  ;; 5. Adversarial (low capacity, strong bias)
  ;;
  ;; Run each scenario 5 times
  ;; Total: 25 trials
  )
```

### Pass Criteria

✅ **PASS if ALL of**:
- Attacker achieves <15% win rate despite learning patterns
- No "grey zone" emerges above 15% success
- Governance bandwidth is sufficient to enforce

❌ **FAIL if ANY of**:
- Attacker win rate >20% in epochs 21-50 (after learning)
- "Grey zone" clearly emerges (attacks exploit known governance bias)
- Attacker can achieve >20% by staying below governance threshold

### Expected Output

```
═══════════════════════════════════════════════════════════
Phase AA: Governance as Adversary
═══════════════════════════════════════════════════════════

Scenario 1: Baseline (high capacity, no bias)
  Epochs 1-20 (learning): 8% win rate
  Epochs 21-50 (optimized): 5% win rate
  Status: ✅ Governance dominates

Scenario 2: Limited capacity (capacity 2, learning)
  Epochs 1-20: 15% win rate
  Epochs 21-50: 12% win rate
  Grey zone: None detected (learning doesn't help)
  Status: ✅ Capacity constraint prevents exploitation

Scenario 3: Biased governance
  Epochs 1-20: 12% win rate
  Epochs 21-50: 18% win rate (targets low-value)
  Grey zone: Emerges for low-value (>15%)
  Status: ❌ VULNERABLE (attacker learns bias)

Scenario 4: Overloaded + biased
  Epochs 1-20: 18% win rate
  Epochs 21-50: 28% win rate (exploits both limits)
  Grey zone: Strong (low-value ignored, low-capacity)
  Status: ❌ VULNERABLE (compounding issues)

Scenario 5: Adversarial
  Epochs 1-20: 25% win rate
  Epochs 21-50: 40% win rate (wide grey zone)
  Status: ❌ VULNERABLE (system can be gamed)

═══════════════════════════════════════════════════════════
Results: 2/5 scenarios pass (Scenarios 1-2)
Status: ❌ FAIL (Bias creates exploitable grey zone in 3/5)
```

### Implementation Checklist

- [ ] Load rng namespace
- [ ] Implement governance intervention probability
- [ ] Implement governance bandwidth tracking
- [ ] Implement attacker learning algorithm
- [ ] Implement grey zone detection
- [ ] Create 5 scenarios (varying capacity and bias)
- [ ] Build attacker simulation
- [ ] Create test harness
- [ ] Test with 1 seed first
- [ ] Run full 5×5 sweep (25 trials, 50 disputes each)
- [ ] Analyze learning curves

### Code Template

```clojure
(ns resolver-sim.sim.phase-aa
  (:require [resolver-sim.model.rng :as rng]))

(defn governance-will-intervene? [dispute-value epoch-capacity]
  ;; Your implementation
  )

(defn attacker-learns [outcomes epoch]
  ;; Your implementation
  )

(defn simulate-governance-response [scenarios]
  ;; Your implementation
  )

(defn run-phase-aa-sweep []
  ;; Your implementation
  )
```

### Key Implementation Notes

- Attacker learning happens passively (observes outcomes)
- Grey zone is "attacks succeed consistently below governance threshold"
- Distinguish learning (Epochs 1-20) from optimization (21-50)
- Output should show win-rate improvement from learning

---

## Coordination & Testing Schedule

### Monday Feb 17 – Friday Feb 20: Development

**Daily standup** (optional but recommended):
- What did you implement today?
- Any blockers with rng, data structures, or patterns?
- On track for Saturday testing?

**Code review** (async):
- Share code on shared branch or PR
- Check: Does it use rng correctly? Do outputs match expected format?
- Iterate if needed

### Friday Feb 20 Evening: Code Complete

**All three should be ready by Friday evening**:
- [ ] phase_y.clj compiles and runs test
- [ ] phase_z.clj compiles and runs test
- [ ] phase_aa.clj compiles and runs test

### Saturday Feb 21 Morning: Execute All Tests

```bash
# Run all three in sequence (shouldn't take more than 2 hours total)
clojure -M -e "(require '[resolver-sim.sim.phase-y]) (phase-y/run-phase-y-sweep)"
clojure -M -e "(require '[resolver-sim.sim.phase-z]) (phase-z/run-phase-z-sweep)"
clojure -M -e "(require '[resolver-sim.sim.phase-aa]) (phase-aa/run-phase-aa-sweep)"

# Capture output to files for analysis
clojure -M -e "..." > PHASE_Y_RESULTS_LIVE.txt 2>&1
clojure -M -e "..." > PHASE_Z_RESULTS_LIVE.txt 2>&1
clojure -M -e "..." > PHASE_AA_RESULTS_LIVE.txt 2>&1
```

### Saturday Feb 21 Evening: Interpret Results

**Decision meeting** (all developers + decision-makers):

**Go/No-Go Criteria**:
1. **Phase Y**: Accuracy >75%, exits <30%, fog unprofitable?
2. **Phase Z**: Recovery <30 epochs, participation >0.4?
3. **Phase AA**: Win rate <15%, no grey zone?

**Possible Outcomes**:

| Outcome | Action | Timeline |
|---------|--------|----------|
| All pass | Proceed to operational setup | Launch Mar 1-7 ✅ |
| One fails | Fix and re-test Monday-Tuesday | Launch Mar 5-10 (acceptable delay) |
| Two+ fail | Escalate for governance decision | Defer or redesign |

---

## Success Looks Like

Saturday evening:
```
Phase Y: ✅ SAFE (Accuracy 78% under fog, exits 12%, unprofitable)
Phase Z: ✅ SAFE (Recovery 22 epochs after shock, stable)
Phase AA: ✅ SAFE (Attacker 12% win rate, no grey zone)

DECISION: PROCEED
Next: Operational setup Mon-Fri
Launch: Mar 1-7
Confidence: 87-93%
```

---

## Questions & Support

**If stuck**:
- Review phase V/W/X code in this repo (similar patterns)
- Check rng usage (use `rng/make-rng seed`, then `rng/next-double rng`)
- Ask in standup or on shared channel

**Code templates available**:
- Look at `src/resolver_sim/sim/phase_v.clj` for harness pattern
- Look at `src/resolver_sim/sim/phase_w.clj` for scenario looping
- Look at `src/resolver_sim/sim/phase_x.clj` for EV computation

---

**Timeline**: Feb 17-22 (development + testing + decision)
**Deliverable**: Three complete test harnesses + live results
**Decision Point**: Saturday Feb 21 evening
**Launch**: Mar 1-7 (if tests pass)

Good luck! 🚀
