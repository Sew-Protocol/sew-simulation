# Phase Y/Z/AA: Falsification Roadmap – Finding Real Issues

**Premise**: V/W/X validated mechanism robustness under clean-truth assumptions. Remaining risk is model-class (epistemic + reflexive), not parameter risk.

This document specifies three orthogonal tests designed to flip your 85-90% confidence if real issues exist.

---

## Phase Y: Evidence Fog and Attention Budget Constraint

### What It Tests

**Problem**: Truth isn't free. Evidence is cheap to fabricate, expensive to verify. Resolvers have time/effort budgets. When evidence verification costs exceed available attention, rational resolvers go lazy.

**Hypothesis to Falsify**:
```
"The system maintains >80% correctness even when:
  - Evidence quality varies widely (10% ambiguous, unverifiable)
  - Resolvers have limited verification budgets
  - Attackers can pay to increase evidence complexity
  - Detection of malicious evidence requires expensive analysis"
```

### Model Components

**Dispute Complexity Distribution**:
```
20% easy (5 units evidence, 1 unit to verify)
60% medium (15 units evidence, 3 units to verify)
15% hard (30 units evidence, 8 units to verify)
5% ambiguous (100 units evidence, impossible to fully verify)
```

**Resolver Budget Per Epoch**:
```
Total budget = 20 units of attention/effort
Must allocate across disputes they're assigned

Strategies:
1. Deep verification (8-10 budget per dispute) → 90% accuracy
2. Shallow check (2-3 budget per dispute) → 70% accuracy
3. Random guess (0 budget) → 50% accuracy
```

**Attacker Evidence Manipulation**:
```
Attacker can pay to increase evidence complexity:
  +5 complexity cost: increase evidence units by 20%
  +10 complexity cost: add false evidence layer (harder to detect)
  +20 complexity cost: make truth structurally ambiguous

Effect: Makes verification more expensive, pulls resolvers toward lazy strategy
```

**Detection Mechanics**:
```
Shallow resolvers: detect malicious evidence 40% of the time
Deep resolvers: detect malicious evidence 85% of the time
Cost to detect (requires deep strategy): 6-8 budget units
```

### Test Harness Design

```clojure
(defn simulate-epoch-with-fog
  [n-resolvers disputes complexity-per-dispute attention-budget]
  ;; Each resolver budgets attention across assignments
  ;; Returns: (avg-accuracy resolver-exits-due-to-effort)
  
  (let [assignments (assign-disputes-to-resolvers n-resolvers disputes)
        strategies (for [resolver assignments]
                     (choose-budget-strategy
                       resolver
                       attention-budget
                       dispute-complexity))
        ;; Outcome: accuracy | false-positives | burnout
        results (evaluate-epoch strategies)])
    results))

(defn run-phase-y-sweep
  [scenarios]
  ;; Scenarios:
  ;; 1. Baseline (easy complexity, ample budget)
  ;; 2. High fog (15% ambiguous, tight budget)
  ;; 3. Attacker fog (attacker actively increasing complexity)
  ;; 4. Load spike (2× normal dispute volume + tight budget)
  ;; 5. Effort cascades (low accuracy → low trust → fewer resolvers → lower budget per remaining)
  
  (for [scenario scenarios
        seed (range 42 47)]
    (let [result (test-scenario-with-fog scenario seed)]
      {:scenario (:name scenario)
       :accuracy (:accuracy result)
       :false-positives (:fp result)
       :resolver-participation (:participation result)
       :avg-budget-spent (:budget-spent result)
       :lazy-strategy-dominates? (< (:accuracy result) 0.75)})))
```

### Failure Signals (What Would Flip Your Conclusion)

🚨 **Critical**:
1. **Accuracy collapses** in high-fog scenario: <75% even with perfect bonding
   - Signal: Truth verification is too expensive
   - Implication: Mechanism soundness breaks under load

2. **Resolver exit cascade**: >20% exit when budget becomes tight
   - Signal: System degrades under participation pressure
   - Implication: Liveness depends on willingness to work hard for free

3. **Attacker evidence domination**: Attacker achieves >60% win rate by paying for fog
   - Signal: Malicious complexity is cheaper than honest deep verification
   - Implication: Attackers can force system into unfavorable tradeoff

4. **False positive spiral**: >10% honest slashing in high-fog scenario
   - Signal: Overworked resolvers slash innocents
   - Implication: Bonding increases risk to honest actors

⚠️ **Concerning** (doesn't flip conclusion alone, but worrying):
5. **Load-dependent degradation**: Accuracy drops >15% under 2× load
6. **Budget-accuracy nonlinearity**: Marginal benefit of effort increases sharply with difficulty
7. **Lazy strategy becomes rational**: deep-verify losses > shallow-check losses in most disputes

### Expected Outcome

**Base case (if mechanism sound)**:
```
Easy complexity (baseline):    98% accuracy
High fog (15% ambiguous):      82% accuracy  ← still >75%
Attacker fog (active increase): 78% accuracy  ← marginal
Load spike (2× disputes):      76% accuracy  ← tighter but stable
Effort cascades:               74% accuracy  ← concerning but recoverable
```

**Failure case** (if mechanism fragile):
```
Easy complexity:               95% accuracy
High fog:                      68% accuracy  ← below 75% threshold
Attacker fog:                  62% accuracy  ← attacker dominates
Load spike:                    45% accuracy  ← system collapses
Resolver exits:                >30%
```

### Implementation Effort

- **Complexity**: Medium (need attention budget mechanics, evidence model)
- **Code**: 400-500 LOC
- **Runtime**: 20-30 seconds (5 scenarios × 5 seeds)
- **Time estimate**: 1.5-2 days

---

## Phase Z: Legitimacy and Reflexive Participation Loop

### What It Tests

**Problem**: A system can be economically sound but structurally unstable if trust drifts. Legitimate outcomes matter for participation, and participation affects security.

**Hypothesis to Falsify**:
```
"The system maintains stable participation over 100+ epochs even when:
  - Disputes sometimes have controversial outcomes
  - False positives occasionally slash honest resolvers
  - Resolution times vary (sometimes >5 days)
  - Participation shocks occur (30-50% sudden withdrawals)"
```

### Model Components

**Trust Index (Global + Per-Cohort)**:
```
trust_t+1 = trust_t × decay_factor + correctness_signal + fairness_signal

decay_factor: 0.98/epoch (slow drift even if doing well)

correctness_signal:
  +2% per epoch if accuracy > 85%
  -3% per epoch if accuracy < 65%
  -1% per epoch if false-positive rate > 5%

fairness_signal:
  +1% if variance(outcomes) is low
  -2% if resolvers of cohort X are slashed >2× cohort Y average
  -1.5% if resolution time > 5 days consistently
```

**Participation Probability**:
```
prob_participate_t+1 = 
  current_participation × 0.97 +  // baseline retention (97%)
  0.03 × sigmoid(trust_t - 0.6)   // re-entry based on trust

When trust drops below 0.4: re-entry becomes nearly impossible
When trust stays above 0.7: participation stable ~85-90%
When trust between 0.4-0.7: oscillating/unstable
```

**Shock Events**:
```
Market shock: 40% of resolvers withdraw for 10 epochs
  - Security drops temporarily
  - If accuracy falls <70%, trust drops further
  - May trigger cascading exits

Scam wave: Incoming disputes all have ambiguous evidence
  - Raises false-positive risk
  - If slashing accuracy <80%, trust damage is high
  - May lock system into low-trust equilibrium
```

### Test Harness Design

```clojure
(defn simulate-reflexive-loop
  [initial-participation initial-trust epochs shocks]
  ;; Run N epochs, track participation and trust over time
  ;; Inputs:
  ;;   - initial-participation: 0.85 (85% of resolvers active)
  ;;   - initial-trust: 0.75 (moderate trust)
  ;;   - epochs: 100-200
  ;;   - shocks: [{type :market-crash at-epoch 30}
  ;;              {type :scam-wave at-epoch 60}]
  
  (loop [t 0
         state {:participation initial-participation
                :trust initial-trust
                :accuracy 0.85
                :fp-rate 0.02}
         history [state]]
    (if (>= t epochs)
      history
      (let [epoch-result (run-epoch-with-participation
                           (:participation state))
            trust' (update-trust (:trust state) epoch-result)
            participation' (update-participation
                             (:participation state) trust')
            state' {:participation participation'
                    :trust trust'
                    :accuracy (:accuracy epoch-result)
                    :fp-rate (:fp-rate epoch-result)}]
        (recur (inc t) state' (conj history state'))))))

(defn run-phase-z-sweep
  []
  ;; Scenarios:
  ;; 1. Baseline (no shocks, stable environment)
  ;; 2. Market shock (40% exit at t=30)
  ;; 3. Scam wave (high-FP disputes at t=60)
  ;; 4. Combined (both shocks)
  ;; 5. Cascading failures (multiple small shocks)
  
  (for [scenario scenarios
        seed (range 42 47)]
    (let [history (simulate-reflexive-loop
                    (:initial-participation scenario)
                    (:initial-trust scenario)
                    100
                    (:shocks scenario))
          final-state (last history)]
      {:scenario (:name scenario)
       :final-participation (:participation final-state)
       :final-trust (:trust final-state)
       :min-participation (apply min (map :participation history))
       :recovery-time (count-epochs-to-recover history 0.75)
       :stable? (stable-equilibrium? history)
       :death-spiral? (< (:participation final-state) 0.3)})))
```

### Failure Signals (What Would Flip Your Conclusion)

🚨 **Critical**:
1. **Death spiral after shock**: Participation drops below 30% and never recovers
   - Signal: Trust damage is hysteretic (one-way)
   - Implication: System becomes unusable after crisis

2. **Trust-participation coupling**: Positive feedback loop amplifies small drops
   - Signal: Participation → accuracy → trust → participation feedback is unstable
   - Implication: System has multiple equilibria; low-trust one is stable

3. **Shock persistence**: Single market crash causes >20-epoch recovery time
   - Signal: Trust recovery is slow relative to crisis speed
   - Implication: System is fragile in volatile environment

4. **Cascading exit**: Resolvers leave in waves, not in smooth curve
   - Signal: Cliff effects (below T_crit, all leave)
   - Implication: System has phase transitions

⚠️ **Concerning**:
5. **Permanent accuracy degradation** after shock: <75% despite recovery
6. **Low-trust equilibrium stability**: Multiple stable states, and low one is attractive
7. **Variance in outcomes** triggers exit even with correct median

### Expected Outcome

**Base case** (if system stable):
```
Baseline (no shocks):
  Initial trust: 0.75, participation: 0.85
  Final trust: 0.78, participation: 0.84  ← stable

Market shock (t=30, 40% exit):
  Min trust: 0.65 (temporary)
  Min participation: 0.50
  Recovery time: ~15 epochs
  Final trust: 0.75, participation: 0.83  ← recovers

Scam wave (t=60, high FP):
  Min trust: 0.58
  Recovery time: ~20 epochs
  Final trust: 0.72, participation: 0.79  ← recovers slower

Combined shocks:
  Min trust: 0.50
  Min participation: 0.40
  Recovery time: ~30 epochs
  Final trust: 0.68, participation: 0.75  ← recovers but lower baseline
```

**Failure case** (if system unstable):
```
Baseline:
  Final trust: 0.72, participation: 0.82  ← already drifting

Market shock:
  Min participation: 0.35
  Recovery never happens
  Final trust: 0.42, participation: 0.25  ← death spiral
```

### Implementation Effort

- **Complexity**: Medium (need participation feedback, trust state machine)
- **Code**: 350-450 LOC
- **Runtime**: 30-60 seconds (5 scenarios × 5 seeds × 100 epochs)
- **Time estimate**: 1.5-2 days

---

## Phase AA: Governance as Adversary – Selective Enforcement Gaming

### What It Tests

**Problem**: Governance isn't monolithic. It has bandwidth limits, biases, and predictable patterns. Attackers can game governance response.

**Hypothesis to Falsify**:
```
"Attackers cannot achieve >20% win rate by gaming governance thresholds,
even when:
  - Governance can only intervene in N disputes per epoch
  - Governance prioritizes high-value disputes (making low-value invisible)
  - Attackers learn governance patterns
  - There's a predictable 'just under the radar' sweet spot"
```

### Model Components

**Governance Bandwidth**:
```
Governance capacity per epoch: 3 disputes
Determines how many escalations can be frozen/reviewed per round

Bottleneck: If incoming disputes > 3, some escape governance review
```

**Governance Bias**:
```
Prioritization rule:
  High value (>$100K): reviewed with 95% probability
  Medium value ($10-100K): 60% probability
  Low value (<$10K): 20% probability
  
Result: Attackers concentrate on low-value disputes that governance ignores
```

**Governance Latency & Detection**:
```
Decision lag: 24 hours (1 epoch) before governance acts
During lag: Malicious Round 0 decision stands (no freeze)

False positives: Governance occasionally freezes legitimate escalations
  Rate: 2% of correctly-decided cases get frozen

This creates "grey zone": governance-resistant attack strategies
```

**Attacker Learning**:
```
Attacker observes governance response patterns:
  - Which dispute sizes/values get attention
  - Which topics get prioritized
  - How often governance is available vs. overloaded

After 20 disputes, attacker has learned:
  - "Governance ignores <$5K disputes"
  - "Governance overloaded Tuesdays"
  - "Governance never reviews <100-unit disputes"

Attack strategy adapts to stay in learned grey zone
```

### Test Harness Design

```clojure
(defn simulate-governance-response
  [governance-capacity governance-bias attacker-sophistication]
  ;; Returns: attacker win-rate by learning governance patterns
  
  (loop [t 0
         attacker-state {:learned false :best-strategy nil}
         results []]
    (if (>= t 50)  ;; 50 dispute opportunities
      results
      (let [;; Attacker chooses target based on learning
            target (if (:learned attacker-state)
                     (:best-strategy attacker-state)
                     (choose-random-target))
            
            ;; Attacker creates dispute with chosen characteristics
            dispute (create-malicious-dispute target)
            
            ;; Governance decides: can it intervene?
            will-govern? (governance-will-intervene?
                           dispute
                           governance-capacity
                           governance-bias)
            
            ;; Attacker outcome
            result (resolve-dispute
                     dispute
                     {:governance-frozen? will-govern?
                      :attacker-prepared? (prepared-for-governance? target)})
            
            ;; Attacker learns
            attacker-state' (if (and (> t 20) (not (:learned attacker-state)))
                              {:learned true
                               :best-strategy (infer-grey-zone
                                               results governance-bias)}
                              attacker-state')]
        (recur (inc t)
               attacker-state'
               (conj results result))))))

(defn run-phase-aa-sweep
  []
  ;; Scenarios:
  ;; 1. Baseline (high capacity 5, no bias, attacker naive)
  ;; 2. Limited governance (capacity 2, attacker learns)
  ;; 3. Biased governance (capacity 3, 90% goes to high-value)
  ;; 4. Overloaded + biased (capacity 2, bias, attacker sophisticated)
  ;; 5. Adversarial governance (low capacity + deliberate indifference)
  
  (for [scenario scenarios
        seed (range 42 47)]
    (let [results (simulate-governance-response
                    (:gov-capacity scenario)
                    (:gov-bias scenario)
                    (:attacker-sophistication scenario))
          win-rate (/ (count (filter :attacker-won results))
                      (count results))]
      {:scenario (:name scenario)
       :attacker-winrate (format "%.1f%%" (* 100.0 win-rate))
       :grey-zone-discovered? (> win-rate 0.15)
       :governance-overload-epochs
         (count (filter :governance-overloaded results))
       :status (if (> win-rate 0.20) :vulnerable :safe)})))
```

### Failure Signals (What Would Flip Your Conclusion)

🚨 **Critical**:
1. **Grey zone discovered**: Attacker learns to stay below governance thresholds, wins >20% of attacks
   - Signal: Governance enforcement is gameable
   - Implication: Bandwidth-limited governance creates predictable blind spots

2. **Timing attacks**: Attacker concentrates attacks during governance "off hours"
   - Signal: Temporal patterns in governance become exploitable
   - Implication: Governance response is predictable enough to evade

3. **Category evasion**: Attacker shifts to low-value/high-volume (e.g., many $1K disputes instead of few $100K)
   - Signal: Value-based prioritization creates perverse incentives
   - Implication: Attackers game governance allocation

4. **Governance amplification**: Incorrect governance freeze makes problem worse
   - Signal: False positives → legitimate escalations frozen → legitimacy → exit
   - Implication: Governance becomes attack vector, not defense

⚠️ **Concerning**:
5. **Attacker sophistication matters**: With 5+ disputes, attacker >15% win rate
6. **Capacity dependency**: Win rate inversely proportional to governance capacity
7. **Bias exploitation**: Attacker learns low-value attacks have <5% governance probability

### Expected Outcome

**Base case** (governance effective):
```
Baseline (high capacity):       0% win rate (governance always responds)
Limited governance (cap=2):     5% win rate (occasional escapes)
Biased governance:              8% win rate (low-value ignored but risky)
Overloaded + biased:            12% win rate (harder but not exploitable)
Adversarial governance:         15% win rate (marginal, losing strategy)
```

**Failure case** (governance gameable):
```
Baseline:                       5% win rate (already struggling)
Limited governance:             25% win rate (clear grey zone)
Biased governance:              35% win rate (high-value ignored, low exploited)
Overloaded + biased:            45% win rate (attacker dominates)
Adversarial governance:         55% win rate (system broken)
```

### Implementation Effort

- **Complexity**: Medium-High (need governance simulation, attacker learning)
- **Code**: 450-550 LOC
- **Runtime**: 30-45 seconds (5 scenarios × 5 seeds)
- **Time estimate**: 2-2.5 days

---

## Implementation Roadmap: "One-Week Falsification"

If you want maximum signal in minimum time, implement in this order:

### Week 1 (3 days of work, 3 days of iteration)

**Day 1: Phase Y-lite** (Evidence fog + attention budgets)
- Effort: 1-2 days
- Fail condition: >20% accuracy drop or >30% resolver exit under fog
- If fails: Stop, redesign system to make verification cheaper
- If passes: Continue

**Day 2: Phase Z-lite** (Trust reflexivity)
- Effort: 1-2 days
- Fail condition: Death spiral or <30% final participation after shock
- If fails: Stop, add stabilization mechanisms (artificial participation, etc.)
- If passes: Continue

**Day 2-3: Phase AA-lite** (Governance gaming)
- Effort: 1 day
- Fail condition: Attacker >20% win rate by learning governance patterns
- If fails: Redesign governance (increase capacity, randomize, etc.)
- If passes: Document findings

### Results Interpretation

```
All three pass (Y, Z, AA):
  → Confidence: 85-90% stands
  → Recommendation: Launch with monitoring
  → Known risks: Evidence workload, trust dynamics, governance gaming—all within bounds

One fails (e.g., Y):
  → Confidence: Drop to 70-75%
  → Recommendation: Address gap, re-test
  → Timeline: +2 weeks to redesign + retest

Two fail:
  → Confidence: Drop to 60-70%
  → Recommendation: Fundamental issue, needs redesign
  → Timeline: Defer launch 4+ weeks
```

---

## Backlog: Lower-Priority Falsification Tests

If Y/Z/AA all pass, these are worth considering:

### Phase AB: Stake Concentration and Structural Fragility
**Test**: 40-60% stake in top 3 resolvers. Does nominal decentralization mask fragility?
**Effort**: 1 day | **Signal strength**: Medium | **Priority**: Low-Medium

### Phase AC: Ambiguity Amplification (Attacker Fog Strategy)
**Test**: Attacker chooses between "cheat" and "fog" investment. Which dominates?
**Effort**: 1.5 days | **Signal strength**: High | **Priority**: Medium

### Phase AD: Multi-System Dependency Failures
**Test**: Oracle/identity/messaging failures during dispute spike.
**Effort**: 1.5 days | **Signal strength**: Medium | **Priority**: Low

### Phase AE: Resolver Collusion Detection
**Test**: Small groups collude on subset of disputes. How long until caught?
**Effort**: 1 day | **Signal strength**: Medium | **Priority**: Low-Medium

---

## Success Criteria and Interpretation

### If All Y/Z/AA Pass
```
System is proven robust against:
  1. Epistemic realism (truth is expensive, attention is limited)
  2. Reflexive dynamics (participation, trust, stability)
  3. Governance gaming (selective enforcement, predictability)

Confidence: 85-90% solidified
Message: "System is ready for mainnet. Risks are monitored, not eliminated."
```

### If Any Fails
```
Specific failure mode identified:
  - Y fails → Evidence model is unsound, verification costs break mechanism
  - Z fails → Long-run stability is questionable, participation will drift
  - AA fails → Governance can be gamed, creating profitable attack vectors

Confidence: Drop 10-15%
Action: Redesign to address gap, re-test before launch
```

---

## Recommended Approach

1. **Implement Y first** (evidence fog): Most likely to reveal real issue
   - If evidence verification is expensive, everything else is fragile
   - If this passes, system has solved the hardest problem

2. **Then Z** (reflexivity): System sustainability
   - Given Y passes, does system remain stable over time?
   - Catches slow-burn failure modes

3. **Then AA** (governance gaming): Attacker sophistication
   - Given Y and Z pass, can attacker exploit governance unpredictability?
   - Catches adversarial governance pattern learning

4. **Parallel testing**: Run Y/Z/AA in parallel if resources available
   - Combined runtime: <2 hours total
   - Can validate all three in one session if all pass

---

## Conclusion

Current 85-90% confidence is solid for mechanism economics. Y/Z/AA test the deeper model-class assumptions:
- Is truth actually expensive? (Y)
- Does system stay healthy over time? (Z)
- Can governance be gamed? (AA)

If all three pass, confidence is genuinely 85-90% and system is mainnet-ready.

If any fails, you've identified the real issue before launch—which is the point.

---

**Next step**: Build Phase Y (Evidence Fog) first.
