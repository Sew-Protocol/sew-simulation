# Phase P Lite: Minimal Falsification Test

## Reframing: What Your Sim Actually Validates

**Current Claim**: "System is 99%+ ready for mainnet"

**Accurate Claim**: "Mechanism security under perfect observability is 99%+ sound"

### What Perfect Observability Means (Your Current Model)
- ✅ Each dispute has ground truth (verdict-correct = boolean)
- ✅ Detection is probabilistic but unambiguous (fraud is always detectable if caught)
- ✅ Single resolver decides (no coordination, no aggregation uncertainty)
- ✅ Disputes are uniform difficulty (attacker can't cherry-pick hard cases)
- ✅ Slashing is instant (no window for repositioning/exit/re-entry)
- ✅ Agents are independent (no herding, shared priors, or information cascades)

### What Real Observability Breaks (Phase P Lite Tests)
- ❌ Dispute difficulty is heterogeneous (attackers target the hard/ambiguous tail)
- ❌ Evidence costs vary (lazy becomes rational under load; evidence games dominate)
- ❌ Panel decisions create coordination (herding, correlated errors, Schelling games)
- ❌ Slashing delays enable repositioning (exit-with-penalty, sybil re-entry, attack clustering)
- ❌ Shared priors cause cascades (early outcomes seed later ones)

---

## The Falsification Experiment: 3 Modules

### Module 1: Dispute Difficulty + Attacker Targeting

**What to add**:
```clojure
; Each dispute samples difficulty at generation
; Distribution: 70% easy, 25% medium, 5% hard
difficulty = sample({:easy 0.70, :medium 0.25, :hard 0.05})

; Honest accuracy decreases with difficulty
honest-accuracy(strategy, difficulty) =
  case strategy
    :honest: {easy: 0.95, medium: 0.80, hard: 0.50}
    :lazy: {easy: 0.50, medium: 0.35, hard: 0.15}
    :malicious: {easy: 0.30, medium: 0.20, hard: 0.10}

; Detection probability decreases with difficulty
detection-prob(difficulty) =
  case difficulty
    :easy: 0.10
    :medium: 0.06
    :hard: 0.02

; KEY: Attacker chooses which bucket to target
; Rational attacker routes fraud into :hard bucket
; (lower detection, higher plausible deniability)
attacker-targeting-strategy:
  if budget-available
    then target :hard disputes
    else target :medium
```

**Why this matters**:
- The "average case" (70% easy) looks safe
- The tail (5% hard) is where attacks happen
- Your current uniform model averages them, hiding the vulnerability

**Test case**:
```
Baseline (uniform difficulty):
  Honest profit: 150 bps
  Malice profit: 100 bps
  → Dominance ratio: 1.5x (good)

With difficulty distribution + attacker targeting:
  Honest profit: 150 bps (stable, they don't target hard cases)
  Malice profit: 300 bps (now profitable, they target hard cases)
  → Dominance ratio: 0.5x (INVERTED - attacker wins)
```

---

### Module 2: Evidence Costs + Attention Budget

**What to add**:
```clojure
; Each resolver has an effort budget per epoch
effort-budget = 100 (time units available per epoch)

; Effort cost to decide depends on difficulty + strategy
effort-cost(difficulty, strategy) =
  case [difficulty strategy]
    [:easy :honest] 5      ; trivial verification
    [:easy :lazy] 3        ; heuristic only
    [:medium :honest] 30   ; real investigation
    [:medium :lazy] 10     ; skim + heuristic
    [:hard :honest] 80     ; deep analysis needed
    [:hard :lazy] 15       ; give up, guess

; Accuracy improves with effort spent
accuracy-with-effort(base-accuracy, effort-spent, required-effort) =
  if effort-spent >= required-effort
    then base-accuracy       ; fully informed
    else base-accuracy * (effort-spent / required-effort)  ; partially informed

; Lazy strategy becomes RATIONAL under load
; If effort-budget < total-effort-needed:
  lazy-payoff = fee * (disputes-decided / disputes-available)
    + evidence-forgery-profit (harder to detect when rushed)
  honest-payoff = fee * (disputes-decided / disputes-available) * accuracy
    - slashing-from-errors (higher error rate when rushed)
```

**Why this matters**:
- Under light load: honest effort >> fraud cost, honesty wins
- Under heavy load: effort budget → shortcuts → heuristics → fraud exploitable
- Lazy strategy becomes economically dominant, not a minority play

**Test case**:
```
Light load (10 disputes, 100 effort units):
  Honest can fully verify all disputes
  Malice doesn't pay (easy to catch)

Heavy load (100 disputes, 100 effort units):
  Honest can only verify 10% of disputes
  Malice can forge evidence faster than honest can verify
  Lazy strategy earns 2x honest
  → System breaks under load
```

---

### Module 3: Panel Decision (n=3) + Correlated Priors + Herding

**What to add**:
```clojure
; Replace single resolver with panel of 3
; Majority vote decides (2 out of 3)

; Add correlation parameter: rho
; rho = 0: independent decisions (current model)
; rho = 0.5: shared narratives, moderate correlation
; rho = 0.9: strong herding, nearly identical outcomes

; Each resolver's accuracy is modeled as:
true-accuracy = base-accuracy(strategy, difficulty)
observed-vote = 
  if (random < rho)
    vote(panel-cohort-signal)  ; follow social signal
  else
    vote(base-accuracy)        ; follow own analysis

; Herding incentive: deviating from majority increases perceived slashing risk
; In ambiguous cases (hard difficulty):
  if own-vote != majority-vote
    then perceived-slashing-risk += 20%
           (even if you're correct, might be slashed for deviance)
  else
    perceived-slashing-risk = base

; Decision rule:
majority-correct? = (votes-for-truth >= 2)
slashing-happens = majority-correct? = false AND detected
```

**Why this matters**:
- With rho=0 (independence): each resolver's incentives are isolated
- With rho=0.5 (moderate): correlated errors amplify → majority can be wrong
- With rho=0.9 (herd): minority truth-teller gets slashed for deviance
- Schelling games: "what does everyone think everyone else will vote?"

**Test case**:
```
Hard dispute, ambiguous facts:
  Panel member 1 (honest): vote=truth, confidence=40%
  Panel member 2 (malice): vote=false, confidence=80%, paid bribe
  Panel member 3 (lazy): vote=false (follows member 2, correlated)

With rho=0:
  Honest votes for truth (1/3, loses, not slashed because wrong)
  System catches malice eventually, slashes

With rho=0.5:
  Honest sees 2 votes for false, questions own analysis
  Queries herding incentive: "If I vote against majority, perceived slashing goes +20%"
  Switches vote to match majority (herding)
  Result: 3 votes for false, majority enforced, truth-teller captured by correlation

With rho=0.9:
  All three converge to same vote regardless of actual evidence
  Attacker only needs to capture 1 resolver; correlation does the rest
```

---

## Implementing Phase P Lite (2 Weeks)

### Week 1: Core Modules
1. Add `difficulty-distribution.clj`
   - Mixture model: easy/medium/hard
   - Per-dispute sampling
   - Difficulty-dependent accuracy curves

2. Add `evidence-costs.clj`
   - Effort budget per resolver
   - Effort-dependent accuracy
   - Load-aware strategy selection

3. Add `panel-decision.clj`
   - n=3 majority vote
   - Correlation parameter (rho)
   - Herding incentive modeling

### Week 2: Integration + Testing
4. Integrate into `dispute.clj`
   - Replace single-resolver logic with panel majority
   - Add difficulty sampling to each dispute
   - Add effort budget tracking per epoch

5. Create `phase-p-lite.clj`
   - Run 1000 trials with increasing rho (0, 0.2, 0.5, 0.8)
   - Compare: dominance ratio at each rho level
   - Detect: abrupt regime change (where does it flip?)

6. Parameter sweep: test under different loads
   - Light load: 10 disputes, 100 effort
   - Medium load: 50 disputes, 100 effort
   - Heavy load: 200 disputes, 100 effort

---

## Success Criteria: What Would Falsify "99%+ Confidence"

### Scenario A: System Remains Robust (Unlikely)
```
All three modules implemented.
Dominance ratio > 1.5x even at:
  - rho = 0.8 (strong herding)
  - heavy load (200 disputes, 100 effort)
  - hard dispute targeting
Result: System is genuinely robust. Claim 90%+ confidence ✅
```

### Scenario B: System Shows Brittleness (More Likely)
```
Dominance ratio inverts (< 1.0) at:
  - rho > 0.5 (moderate herding triggers collapse)
  - OR heavy load triggers lazy dominance
  - OR hard-case targeting drops profitability below malice
Result: System is fragile under realistic conditions. Claim 60%+ confidence ⚠️
```

### Scenario C: System Breaks (Most Likely)
```
Dominance ratio inverts at low rho (0.2) AND/OR light load.
Multiple failure modes (herding cascade + evidence game + load effect) reinforce.
Result: Fundamental vulnerability discovered. Revise mechanism design.
```

---

## Predicted Outcome (Based on Design Patterns)

I predict **Scenario B** (brittleness discovered):

**Most likely failure chain**:
1. Hard disputes are rare (5%) but profitable for attackers (detection -80%)
2. Attacker targets hard cases; honest resolvers deprioritize them (expected value drops)
3. Under load, effort budget → shortcuts → lazy becomes dominant
4. Panel correlation (rho > 0.4) creates herding → majority-wrong outcomes
5. Dominance ratio drops from 1.5x to 0.8x (attack becomes profitable)
6. **Trigger**: When `difficulty-tail + evidence-game + correlation` stack together

**What this means for mainnet**:
- Bond mechanics are still sound (they'd still deter simple fraud)
- But the system is fragile on three dimensions:
  1. Ambiguous cases are underdeterred
  2. Load causes quality collapse
  3. Correlation creates cascade risks
- Confidence drops from 99% → 65% (not broken, but fragile)

---

## What to Do If Brittleness Is Found

### Option A: Redesign Mechanism (Major)
- Add appeals/jury (redundancy)
- Change slashing to reputation-weighted (prevent herding)
- Add escalation on disagreement (hard cases go to L2)

### Option B: Add Parameters (Medium)
- Increase bond size for hard disputes
- Reduce herding weight (lower correlation amplification)
- Implement effort rewards (incentivize deep analysis)

### Option C: Accept and Monitor (Fast)
- Ship mainnet with current design
- Monitor hard-case performance
- Prepare redesign for 2.0 if brittleness emerges

---

## Spec Summary

**Phase P Lite**: ~500 lines of Clojure, 2 weeks

**Three modules**:
1. `difficulty-distribution.clj` (100 lines) - Mixture model + attacker targeting
2. `evidence-costs.clj` (150 lines) - Effort budget + load effects
3. `panel-decision.clj` (200 lines) - 3-resolver panel + correlation + herding

**Integration**: Modify `dispute.clj` to use panel instead of single resolver

**Testing**: `phase-p-lite.clj` (200 lines) - Sweep over rho, load, difficulty

**Expected time to falsification**: <2 weeks

---

## Why This Is The Minimal Falsification

These three modules directly attack the three biggest structural optimisms in your current model:

1. **Dispute uniformity** (attackers can't select for difficulty) → falsified by difficulty distribution
2. **Single resolver** (no coordination, no herding) → falsified by panel + correlation
3. **Infinite attention** (everyone verifies everything) → falsified by effort budget

Together, they usually reveal the real fragility points that the bond/fee math hid.

---

**The bottom line**: If Phase P Lite finds no problems, you have genuine 90%+ confidence. If it finds brittleness (likely), you know exactly where to redesign before mainnet.

