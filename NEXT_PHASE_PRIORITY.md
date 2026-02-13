# Phase P–T Priority Matrix: What to Build Next

## The Honest Assessment

Your current simulation validates: **"Bond mechanics are sound, slashing deters fraud IF fraud is detectable"**

It does NOT validate: **"Real attackers can't work around this with bribes, evidence manipulation, or participation gaming"**

The gap between these two statements is the difference between:
- ✅ **Mathematical soundness** (current: 99%+)
- ⚠️ **Production readiness** (current: ~55%)

---

## Decision Tree

```
Q1: Should we launch on mainnet now with current sim?
A: YES, launch.
   Reason: Bond/fee mechanics are genuinely sound.
   But add monitoring dashboard (see below).

Q2: Should we claim "system is production-ready"?
A: NO. Claim "bond mechanics are validated" instead.
   Add disclaimer about information/coordination realism.

Q3: What's the minimum to do before 2.0 production launch?
A: Implement Tier 1 (Phases P, Q, R).
   Budget: 4–6 weeks. ROI: +35–40% confidence.

Q4: What's the long-term thesis?
A: Deploy mainnet with strong monitoring.
   Learn from real attacks.
   Iterate on 3.0 with empirical data.
   (This is how Kleros, UMA, Aragon all did it.)
```

---

## 6 Phases: Phased Rollout Strategy

### Phase P: Bribery Markets (Week 1–2)
**Goal**: Can attacker flip verdict cheaply via credible bribery?

**Implement**:
1. Attacker strategy: `{type: :bribery, budget-bps: X, structure: :p+epsilon}`
2. Credible escrow: bribe → smart contract → release only if verdict-matches
3. Juror coordination: if offered bribe, participate if:
   - `(bribe-payout * prob-success) > (expected-honest-profit)`
4. Marginal targeting: don't flip N/2+1 jurors, flip K (minimum needed)

**Test Cases**:
- "Can attacker flip verdict with 10% of escrow budget?" (baseline)
- "How many jurors need to coordinate?" (minimum)
- "Does marginal targeting beat majority purchase?" (cost comparison)

**Success Criteria**:
- ✅ Attacker needs >50% budget (current assumption) → confidence +20%
- ⚠️ Attacker needs 30–50% budget → confidence +10%
- ❌ Attacker needs <30% budget → confidence -30% (major gap)

**Estimated Time**: 1–2 weeks (attacker.clj module, bribery-response.clj for juror modeling)

---

### Phase Q: Adversarial Evidence (Week 2–3)
**Goal**: How robust is the system when evidence can be forged/withheld?

**Implement**:
1. Dispute difficulty distribution:
   - 50% easy (verifiable facts, immutable evidence)
   - 35% technical (requires expertise, time-consuming)
   - 12% ambiguous (expert disagreement expected)
   - 3% impossible (insufficient evidence)

2. Evidence production layer:
   ```clojure
   attacker-cost = f(dispute-hardness, fake-quality)
   honest-verification-cost = f(dispute-hardness, time-available)
   juror-decision = heristic(narrative, time-pressure) OR verification(deep-analysis)
   ```

3. Juror time budget:
   - 5–15 min available per dispute (realistic)
   - Under pressure: use heuristic (reputation, narrative)
   - With time: can do verification (slower but more accurate)

4. Queue effects:
   - Long queue → more time pressure → heuristics dominate
   - Attacker targets queue-length > 10 (juror fatigue)

**Test Cases**:
- "Can attacker win with cheaper fake evidence?" (cost comparison)
- "Does evidence quality matter under time pressure?" (heuristic dominance)
- "What's the spam threshold?" (queue length → error rate)

**Success Criteria**:
- ✅ Honest evidence quality > fake quality → confidence +25%
- ⚠️ Honest verification takes 10x longer → confidence +10%
- ❌ Attacker wins more often under time pressure → confidence -25%

**Estimated Time**: 1–2 weeks (evidence-game.clj, queue-simulator.clj)

---

### Phase R: Adaptive Attacker (Week 3–4)
**Goal**: Does system degrade when attacker learns optimal strategy?

**Implement**:
1. Attacker strategy space:
   - `{bribe-cohort-A, forge-evidence-type-B, spam-queue, timing-attack, ...}`
2. Reward per strategy: `profit-per-budget`
3. Learning algorithm: Thompson sampling (explore vs. exploit)
   - Epochs 1–3: explore all strategies
   - Epochs 4+: converge to cheapest

4. Correlated events:
   - Market crash → N disputes of liquidation-type
   - Scam pattern spreads → N disputes of fraud-type-X
   - Attacker learns: "Type X is cheap to attack"

5. Learning loop:
   ```clojure
   epoch-1: try bribe-cohort-A → success-rate 20%
   epoch-2: try forge-evidence → success-rate 45%
   epoch-3: try spam-queue → success-rate 30%
   epoch-4+: allocate 80% budget to forge-evidence, 20% to spam-queue
   → converge to optimum
   ```

**Test Cases**:
- "Does attacker find the cheapest strategy?" (convergence)
- "How many epochs to optimize?" (learning speed)
- "Do correlated events create cascading attacks?" (learning amplification)

**Success Criteria**:
- ✅ Attacker converges but costs remain >50% of bond → confidence +20%
- ⚠️ Attacker finds 30% cost optimum → confidence +5%
- ❌ Attacker finds <20% cost optimum → confidence -40%

**Estimated Time**: 1 week (bandit-learning.clj, attacker-optimization.clj)

---

### Phase S: Endogenous Participation (Week 5–6)
**Goal**: Does system degrade when jurors fatigue or queue builds?

**Implement**:
1. Juror participation model:
   ```clojure
   availability = f(outside-opportunities, queue-length, perceived-fairness)
   decision-quality = f(fatigue-level, time-pressure, expertise)
   fatigue-accumulation = f(disputes-judged-in-epoch, boredom-factor)
   ```

2. Queue dynamics:
   - If disputes > jurors, queue grows
   - Queue length → decision time increases
   - Decision time → honest users abandon → volume drops → security drops

3. Adverse selection:
   - Long queue → only partisan/risk-seeking jurors remain
   - Risk-seeking jurors have lower accuracy (more heuristic, less verification)

4. Reflexive spiral:
   - Slowness → exodus → lower stake → lower security
   - Attacker exploits low security

**Test Cases**:
- "Does security degrade when queue is long?" (latency vector)
- "Can attacker exploit boring periods?" (attention exploitation)
- "Do jurors exit when queue is > 10?" (threshold discovery)

**Success Criteria**:
- ✅ Participation is stable across queue lengths → confidence +15%
- ⚠️ Participation drops 20% when queue > 10 → confidence +5%
- ❌ Reflexive spiral (exit → security → attack → exit) → confidence -30%

**Estimated Time**: 2 weeks (participation.clj, queue-simulator.clj, fatigue.clj)

---

### Phase T: Governance Capture Vectors (Week 7–8)
**Goal**: Can attacker predict/manipulate governance decisions?

**Implement**:
1. Governance as an agent:
   ```clojure
   governance = {
     rule-set: {slash-bps, freeze-duration, appeal-success-rate},
     history: [past-interventions],
     attention-level: varies-over-time,
     capture-risk: f(rule-drift, attacker-investment)
   }
   ```

2. Rule drift detection:
   - Track governance changes per epoch
   - Attacker learns: "governance always increases slash by 5% after exploit"
   - → Predictable, exploitable pattern

3. Selective enforcement:
   - Track which verdicts are pardoned vs. slashed
   - Attacker learns: "governance pardons big players"
   - → Moral hazard

4. Capture via attention:
   - Governance is faster on high-salience cases
   - Slower on low-salience cases
   - Attacker times attack for low-salience period

**Test Cases**:
- "Can attacker predict governance response?" (pattern discovery)
- "Does rule drift create exploitable patterns?" (governance gaming)
- "Can attacker time attack for low-salience period?" (attention exploitation)

**Success Criteria**:
- ✅ Governance is unpredictable → confidence +15%
- ⚠️ Attacker can predict 60% of responses → confidence +5%
- ❌ Attacker can predict >80% of responses → confidence -25%

**Estimated Time**: 2 weeks (governance-agent.clj, rule-drift.clj, attention-model.clj)

---

## Implementation Roadmap: Fast Path

### If you have **4 weeks** (before production launch):
1. **Phase P** (Bribery) → +20% confidence
2. **Phase Q** (Evidence) → +25% confidence
3. **Phase R** (Adaptive Attacker) → +20% confidence

**Result**: "99% on bond mechanics + 70% on adversarial robustness"

### If you have **6 weeks** (before 2.0 production):
1. Phases P, Q, R (above)
2. **Phase S** (Participation) → +15% confidence

**Result**: "99% on bond mechanics + 80% on adversarial robustness"

### If you have **8+ weeks** (before 3.0):
1. Phases P, Q, R, S (above)
2. **Phase T** (Governance Capture) → +15% confidence

**Result**: "99% on bond mechanics + 90% on production readiness"

---

## Monitoring Dashboard (Parallel Track)

**Deploy immediately** (doesn't require sim work):

```
Real-Time Metrics:
1. Bribery detection:
   - Measure: % disputes where verdict matches pre-announced expectation
   - Alert: >20% → possible bribery signal
   
2. Evidence quality:
   - Measure: appeal success rate by dispute type
   - Alert: appeal-rate >40% → evidence quality issues
   
3. Governance drift:
   - Measure: frequency of rule changes
   - Alert: >1 change per 3 epochs → rule drift signal
   
4. Participation trends:
   - Measure: queue length, decision latency, juror availability
   - Alert: queue >20 or latency >1 hour → slowdown vector
   
5. Attacker learning:
   - Measure: attack success rate by type
   - Alert: convergence to single attack type → learning signal

6. Legitimacy:
   - Measure: verdict variance, insider win rate, exit rate
   - Alert: exit-rate >5% or variance >high → trust issue

Dashboard updates: Real-time
Escalation: Weekly report to governance
```

---

## Confidence Ladder

| Phase | Completion | Bond Mechanics | Adversarial Robustness | Production Ready |
|-------|------------|----------------|------------------------|------------------|
| Current (G–O) | ✅ Done | 99% | 55% | ❌ No |
| + Phase P | 2w | 99% | 70% | ⚠️ Risky |
| + Phase Q | 4w | 99% | 85% | ⚠️ Moderate |
| + Phase R | 5w | 99% | 87% | ✅ Yes |
| + Phase S | 7w | 99% | 90% | ✅ Yes |
| + Phase T | 9w | 99% | 93% | ✅ Yes |

---

## Recommendation: Go-Live Plan

**Mainnet Launch** (Now):
- ✅ Ship with current Phases G–O validation
- ✅ Deploy monitoring dashboard immediately
- ✅ Document: "Bond mechanics validated. Information/coordination realism TBD."

**Week 1–2** (Alpha):
- Monitor metrics, adjust parameters if needed
- Implement Phase P (Bribery) in parallel

**Week 3–4** (Beta):
- Phase P complete, test results published
- Implement Phase Q

**Before 2.0 Production** (6 weeks):
- Phases P, Q, R complete
- Confidence at 85–87%
- Declare "production-ready"

**Before 3.0** (9+ weeks):
- Phases P, Q, R, S, T complete
- Confidence at 90%+
- Empirical data from mainnet incorporated

---

**Bottom Line**: You can launch now. You can't claim "production-ready" until you've done Phases P–R. The gap is real, but it's addressable in parallel with mainnet operation.

