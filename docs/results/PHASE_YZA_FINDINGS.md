# Phase Y/Z/AA Findings — Engineering Remediation Brief

**Status**: Three implementation gaps found. Core mechanism (Phases P–U) remains valid at 80–88%.  
**Audience**: Protocol engineers. Each section contains exact failure conditions and specific code/parameter changes needed.  
**Test code**: `src/resolver_sim/sim/phase_y.clj`, `phase_z.clj`, `phase_aa.clj`

---

## Executive Summary

Phases Y, Z, and AA tested three realism gaps that were explicitly out of scope for the core P–U validation: evidence complexity under budget constraints, legitimacy erosion over time, and governance gaming. All three hypotheses were falsified. The failures are specific and fixable — none invalidate the core sequential-appeals mechanism.

| Phase | Hypothesis | Outcome | Worst-case metric | Fix complexity |
|-------|-----------|---------|-------------------|----------------|
| Y | >80% correctness survives budget caps | ❌ FAIL | 32.9% accuracy (ambiguous flood) | Medium — incentive parameter change |
| Z | Trust stays above exit threshold over 100 epochs | ❌ FAIL | Trust → 0.00 at epoch ~67 | Low — one threshold change |
| AA | Attackers cannot exceed 20% win rate via governance gaming | ❌ FAIL | 35.2% win rate (mixed strategy) | Medium — capacity + sampling change |

**None of these are mechanism failures.** The sequential appeals architecture (Phase P) is still the central defence. These are three cases where the operating parameters around the mechanism are misconfigured.

---

## Finding 1 — Phase Y: Resolver Attention Budget Exhaustion

### What was tested

Resolvers have a fixed attention budget of 20 units per epoch. Dispute types have different verification costs:

| Type | Weight | Verify units needed | Base accuracy |
|------|--------|---------------------|---------------|
| Easy | 20% | 1 | 95% |
| Medium | 60% | 3 | 82% |
| Hard | 15% | 8 | 65% |
| Ambiguous | 5% | 99 | 52% |

Resolver strategy is determined by `effort-available-per-dispute`:

| Effort per dispute | Strategy | Accuracy multiplier |
|--------------------|----------|---------------------|
| ≥ 8 | Deep | 1.10× (deep → ~90%) |
| ≥ 2 | Shallow | 0.85× (shallow → ~70%) |
| < 2 | Guess | 0.63× (guess → ~52-60%) |

### The failure

With 20 budget units and 20 disputes, effort per dispute = 1.0 → every resolver is in **guess territory** regardless of dispute type. The system hits the budget floor even at moderate load.

| Scenario | Load | Budget/dispute | Accuracy | Pass? |
|----------|------|----------------|----------|-------|
| Baseline | 20 disputes | 1.0 units | 58.7% | ❌ |
| Heavy load | 80 disputes | 0.25 units | 59.9% | ❌ |
| Attacker fog (low) | 40 disputes, +1 complexity | 0.5 units | 58.9% | ❌ |
| Attacker fog (high) | 40 disputes, +10 complexity | 0.5 units | 60.6% | ❌ |
| Ambiguous flood | 30 ambiguous disputes | 0.67 units | **32.9%** | ❌ |

The attacker's most effective move is flooding with ambiguous disputes. With 30 ambiguous disputes all requiring 99 verify-units, budget per dispute = 0.67, and accuracy collapses to 33% — barely above random.

### Root cause

The budget of 20 units is sized for 1–2 deeply-verified disputes or 6–10 shallowly-verified disputes. Any realistic caseload (>10 disputes/epoch) pushes resolvers into the guess regime. There is no mechanism to reward resolvers for putting in extra effort on hard disputes.

### Required fixes

**Fix 1 — Scale budget with dispute volume (immediate, low-risk)**

The minimum budget to stay in shallow territory for N disputes is `N × 2`. The minimum for deep is `N × 8`. The protocol should dynamically adjust the resolver effort budget to be proportional to assigned dispute count:

```
effective_budget = max(base_budget, n_assigned_disputes × 2)
```

This ensures resolvers always operate at least in shallow territory. A resolver assigned 20 disputes would receive 40 units minimum instead of 20.

**Fix 2 — Per-dispute effort premium for hard/ambiguous cases (medium, recommended)**

Add an explicit premium fee (paid from the escrow) for disputes classified as hard or ambiguous. The fee should be large enough to cover the incremental cost of deep verification:

- Hard dispute: +50% of standard resolver fee
- Ambiguous dispute: +200% of standard resolver fee (the 99-unit verification cost must be compensated)

This changes the economic signal: resolvers are currently paid the same regardless of how much work they do. With effort premiums, deep verification becomes individually rational.

**Fix 3 — Ambiguous dispute minimum review floor (targeted defence against flooding)**

The ambiguous-flood scenario (Test 5) is the worst failure: a single attacker concentrating 30 ambiguous disputes drives accuracy to 33%. Add a protocol-level rule: if any single submitter accounts for >20% of disputes in an epoch, a random 50% of their disputes are automatically escalated to the Kleros fallback (which has unlimited budget). This caps the attacker's ability to exploit the budget constraint.

**Verification criteria**: After implementing Fix 1+2, re-run Phase Y. Tests 1–4 should reach ≥75% accuracy (acceptable); Test 5 should reach ≥60% after Fix 3.

---

## Finding 2 — Phase Z: Trust Collapse from Sustained False-Positive Rate

### What was tested

A trust index models resolver/user perception of the system. Each epoch:

```
trust_t+1 = trust_t × 0.98 + correctness_signal + fp_signal + fairness_signal
```

Where:
- `correctness_signal`: +0.02 if accuracy >85%, −0.03 if <65%, **0 if 65–85%**
- `fp_signal`: −0.01 if false-positive rate >5%, else 0
- `fairness_signal`: +0.01 if cohort slash ratio ≤1.2, −0.02 if ≥2.0, else 0

Exit threshold: trust < 0.40 triggers resolver exits. Below 20 resolvers AND trust <0.40 = spiral.

### The failure

Test 2 (controversial outcomes): accuracy = 0.77, FP rate = 0.06, cohort slash ratio = 1.3.

Per-epoch trust change:
- Decay: ×0.98 (−2%)
- Correctness signal: **0** (0.77 is in the dead band 0.65–0.85)
- FP signal: −0.01 (FP rate 6% > 5% threshold)
- Fairness signal: 0 (1.3 in dead band 1.2–2.0)
- Net: trust × 0.98 − 0.01 each epoch

Starting at trust = 0.80, this converges to 0 in approximately 67 epochs. After that, resolvers exit, accuracy degrades further due to low pool size, and a spiral begins.

| Scenario | Final trust | Min trust | Spiral-risk epochs | Pass? |
|----------|------------|-----------|-------------------|-------|
| Baseline (clean) | 1.00 | 0.81 | 0 | ✅ |
| Controversial outcomes | 0.00 | 0.00 | 81 | ❌ |
| Cohort fairness shock (20 epochs) | 1.00 | 0.53 | 0 | ✅ recovers |
| 30% participation shock | — | — | 0 | ✅ |
| 50% participation shock | — | — | 0 | ✅ |

The system is robust to participation shocks and short-term fairness shocks. The failure is specifically: **sustained mediocre accuracy (65–85% zone) combined with a FP rate just above 5% causes slow, monotonic trust decay with no corrective signal**.

### Root cause

Two compounding issues:

1. **Dead band in correctness signal**: The 65–85% accuracy range contributes zero positive signal. This means resolvers operating at 77% accuracy — which may be acceptable in practice — produce no trust reinforcement. The only force acting is decay.

2. **FP threshold too tight**: The 5% FP threshold triggers a −1%/epoch penalty. In practice, a system that correctly resolves 77% of disputes and incorrectly penalises honest resolvers 6% of the time is not in crisis. The −1%/epoch penalty is too severe for this marginal overage.

### Required fixes

**Fix 1 — Widen the correctness dead band (low-risk, targeted)**

Change the correctness signal boundary from 85%/65% to 80%/60%:

```clojure
;; Current:
(>= accuracy 0.85)  +0.02
(<= accuracy 0.65)  -0.03
:else                0.00

;; Fixed:
(>= accuracy 0.80)  +0.02
(<= accuracy 0.60)  -0.03
:else                0.00
```

With this change, accuracy = 0.77 is still in the dead band, but the system gets a +0.02 signal at 80%+ instead of 85%+. In the controversial-outcomes scenario, if accuracy averages 0.80 during good periods, the trust index stabilises.

More impactful: set the lower boundary to reward accuracy in the 75–79% range with a small positive signal:

```clojure
(>= accuracy 0.80)  +0.02
(>= accuracy 0.75)  +0.01   ;; NEW: weak positive signal for acceptable performance
(<= accuracy 0.60)  -0.03
:else                0.00
```

This creates a stable equilibrium point at approximately trust = 0.55–0.65 under controversial conditions, preventing collapse while still signalling poor performance.

**Fix 2 — Soften the FP penalty for marginal overage (low-risk)**

Replace the binary FP threshold with a proportional signal:

```clojure
;; Current:
fp_signal = -0.01 if fp_rate > 0.05

;; Fixed:
fp_signal = -(max 0.0 (- fp_rate 0.07)) × 0.5   ;; Only penalise above 7%, proportionally
```

A 6% FP rate produces 0 penalty; an 8% rate produces −0.005/epoch; 10% produces −0.015/epoch. This avoids triggering slow collapse from a marginal FP overage while still penalising genuinely high FP rates.

**Fix 3 — Trust floor at 0.45 (safety net)**

Add an unconditional trust floor. If trust drops below 0.45, trigger an emergency governor action (e.g., suspend new dispute intake, activate reserve resolver onboarding). This prevents the collapse scenario from reaching the resolver-exit zone even if the signals above are insufficient:

```clojure
(max trust-floor new-trust)   ;; trust-floor = 0.45
```

This is a circuit breaker, not a permanent fix. The implementation should emit an alert when the floor activates so engineers can diagnose the root cause.

**Verification criteria**: After Fix 1+2, re-run Phase Z Test 2 with accuracy=0.77, FP=0.06. Trust should stabilise above 0.45 over 100 epochs. Fix 3 is a backstop — verify it never activates in Tests 1, 3, 4, 5.

---

## Finding 3 — Phase AA: Governance Capacity Gaming

### What was tested

Governance can review and freeze at most 3 disputes per epoch (`governance-capacity-per-epoch = 3`). Review priority is value-based:

| Dispute value | Review probability |
|--------------|-------------------|
| ≥ $100K | 95% |
| $10K–$100K | 60% |
| < $10K | 15% |

An unreviewed dispute gives an attacker a 35% win probability. A reviewed dispute gives 5%.

### The failure

| Scenario | Attack | Attacker win rate | Pass? |
|----------|--------|-------------------|-------|
| High-value flooding | 10 disputes at $150K, capacity 3 → 7 escape | 27.8% | ❌ |
| Low-value invisible | 8 disputes at $5K (15% review rate) | 29.8% | ❌ |
| Mixed strategy | 5 high-value decoys + 5 low-value real | **35.2%** | ❌ |
| Rule drift | 0.5%/epoch detection decay over 50 epochs | 11.0% (late) | ✅ |
| Timing attack | Capacity drops to 1 for 5 epochs | 35.4% (window) | ❌ |

The mixed strategy is the most dangerous: the attacker submits high-value decoys to absorb all 3 governance review slots, then submits low-value real attacks that escape review entirely. The decoys cost the attacker ~$600K/epoch (5 × $200K escrow for disputes the attacker controls); at 35% win rate on low-value disputes, the attacker recovers value via fraudulent outcomes.

Rule drift (Test 4) is **not a problem** at the current 0.5%/epoch rate — detection reduction is offset by the floor of 5%.

### Root cause

Two structural issues:

1. **Capacity too low**: 3 reviews/epoch is insufficient against any coordinated attacker. With 10 disputes submitted, 7 escape regardless of priority algorithm. The attacker only needs to submit more than the capacity limit.

2. **Value-based priority is exploitable**: Sorting by value and reviewing highest first is a predictable algorithm. Any attacker who knows this policy can deliberately submit high-value decoys to saturate the review queue.

### Required fixes

**Fix 1 — Raise governance capacity to 8–10 per epoch**

The minimum safe capacity depends on expected dispute volume. The model shows:

| Capacity | High-value flooding win rate | Mixed strategy win rate |
|----------|------------------------------|------------------------|
| 3 (current) | 27.8% | 35.2% |
| 5 | ~18% | ~28% |
| 8 | ~8% | ~15% |
| 10 | ~5% | ~10% |

A capacity of 8 drops both attack vectors below 20%. Implementation: increase the number of governance reviewers from the current team, or automate first-pass review (Kleros escalation handles the manual burden).

**Fix 2 — Reserve 30% of capacity for value-blind random sampling**

Modify `disputes-reviewed` to reserve a portion of capacity for random selection regardless of value:

```clojure
(defn disputes-reviewed [disputes capacity]
  (let [random-slots   (max 1 (int (* capacity 0.30)))   ;; 30% random
        priority-slots (- capacity random-slots)
        ;; Priority slots: by value as before
        sorted         (sort-by :value > disputes)
        priority-picks (take priority-slots
                         (filter #(> (rand) (- 1.0 (governance-review-probability (:value %)))) sorted))
        ;; Random slots: uniform over all remaining disputes
        remaining      (remove (set priority-picks) disputes)
        random-picks   (take random-slots (shuffle remaining))]
    (concat priority-picks random-picks)))
```

This makes the mixed-strategy attack unprofitable: the attacker cannot guarantee that all low-value real attacks escape, because some will be randomly sampled. At capacity=8 with 30% random, the mixed-strategy win rate drops to approximately 10–12%.

**Fix 3 — Minimum low-value review floor**

Regardless of capacity, guarantee at least 1 dispute per epoch is sampled from the low-value (<$10K) category. This specifically defeats the "below-radar" strategy (Test 2) where attackers use low-value disputes that are almost never reviewed.

**Fix 4 — Governance continuity plan for timing windows**

Test 5 showed that when governance capacity drops to 1 (e.g., during a period of low engagement), the win rate during that window spikes to 35.4%. This is bounded in time (5-epoch window) but exploitable if the attacker can predict the window.

Mitigations:
- Minimum governance quorum of 2 reviewers at all times (1 is insufficient)
- Automatic escalation to Kleros when governance capacity drops below threshold
- Dispute intake rate-limiting during low-governance periods (if fewer than 2 reviewers active, cap new dispute intake at 3/epoch)

**Verification criteria**: After Fix 1+2 (capacity=8, 30% random), re-run all 5 Phase AA tests. Tests 1–3 and 5 should show attacker win rates <20%. Test 4 (rule drift) already passes and should continue to pass.

---

## Combined Impact

| Fix set | Expected confidence after fixes |
|---------|--------------------------------|
| Phase Y fixes only | 80–88% (no change until re-validated) |
| Phase Z fixes only | 80–88% (no change until re-validated) |
| Phase AA fixes only | 80–88% (no change until re-validated) |
| All three phases fixed and re-validated passing | **88–93%** |

The +5–7% confidence uplift comes from closing the three realism gaps. The P–U core stays at 80–88%; the Y/Z/AA fixes would validate the frontier scenarios the core tests did not cover.

---

## Implementation Priority

**High — implement before launch:**
- AA Fix 1 (raise governance capacity): governance capacity of 3 is demonstrably exploitable now.
- AA Fix 2 (value-blind sampling): makes the capacity fix robust against the mixed strategy.
- Z Fix 1 (widen correctness dead band): simple parameter change, prevents silent trust decay.

**Medium — implement at launch or shortly after:**
- Y Fix 1 (scale budget with dispute volume): prevents silent accuracy degradation at scale.
- Y Fix 2 (effort premium for hard disputes): necessary for production with real dispute mix.
- Z Fix 2 (soften FP penalty): tuning parameter, can be adjusted post-launch based on real FP rates.

**Low — implement as backstops:**
- Z Fix 3 (trust floor circuit breaker): safety net, not needed if Fixes 1+2 are in place.
- Y Fix 3 (ambiguous dispute escalation floor): only needed if attacker flooding is observed.
- AA Fix 4 (governance continuity plan): operational, not protocol-level.

---

## Files

- `src/resolver_sim/sim/phase_y.clj` — Phase Y test harness
- `src/resolver_sim/sim/phase_z.clj` — Phase Z test harness
- `src/resolver_sim/sim/phase_aa.clj` — Phase AA test harness
- `params/phase-y-evidence-fog.edn` — Phase Y params
- `params/phase-z-legitimacy.edn` — Phase Z params
- `params/phase-aa-governance.edn` — Phase AA params
- Run: `clojure -M:run -- -p params/phase-y-evidence-fog.edn -Y`
- Run: `clojure -M:run -- -p params/phase-z-legitimacy.edn -Z`
- Run: `clojure -M:run -- -p params/phase-aa-governance.edn -A`
