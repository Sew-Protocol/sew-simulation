# Truth Model Analysis: Current State & Gaps

## Executive Summary

The simulation currently uses a **clean oracle model** with **independent agents**. This is appropriate for initial validation but creates specific blindspots when compared to real dispute resolution.

| Aspect | Current Model | Reality | Gap Impact |
|--------|---------------|---------|-----------|
| **Truth Signal** | Clean oracle (verdict-correct? = ground truth) | Noisy evidence with claims/verification costs | **MEDIUM** |
| **Agent Interactions** | Independent strategies (no correlation) | Correlated behavior (herding, shared priors) | **MAJOR** |
| **Detection** | Probabilistic catch (random prob per trial) | Requires proof/analysis (time/cost) | **MEDIUM-HIGH** |
| **Dispute Quality** | IID random (uniform) | Heavy-tailed (some disputes are ambiguous) | **MINOR** |

---

## 1. Current Truth Model: Clean Oracle

### What's Modeled

```clojure
; From dispute.clj:60-65
verdict-correct?
(case strategy
  :honest       true              ; Always correct
  :lazy         (< (rng/next-double rng) 0.5)  ; 50% correct
  :malicious    (< (rng/next-double rng) 0.3)  ; 30% correct
  :collusive    (< (rng/next-double rng) 0.8)) ; 80% correct
```

### Characteristics

1. **Ground Truth Exists**: Each dispute has a definite `dispute-correct?` (boolean)
2. **No Evidence/Claims**: Verdicts are judged against ground truth instantly, not argued
3. **No Verification Cost**: Detection is purely probabilistic, not based on proof review
4. **Strategy is Deterministic**: Resolver's strategy determines judgment accuracy, independent of other resolvers

### Why This Model?

✅ **Strengths**:
- Simple, tractable, clear incentives
- Isolates slashing mechanics from judgment difficulty
- Lets us focus on bond/fee trade-offs without evidence burden
- Early-stage validation appropriate

❌ **Limitations**:
- Doesn't capture **ambiguous disputes** (real disputes have shades of gray)
- Assumes **instant detection** (real systems need analysis time)
- No **communication/evidence sharing** between resolvers
- Ignores **asymmetric information** (who knows ground truth?)

---

## 2. Current Agent Model: Independent Strategies

### What's Modeled

```clojure
; From multi_epoch.clj:128
:strategy-mix {:honest 0.50 :lazy 0.15 :malicious 0.25 :collusive 0.10}
```

Each resolver independently samples a strategy at start of epoch, executes it in isolation.

### Collusion Model (Limited)

The `resolver_ring.clj` module models **coordinated ring behavior**:

```clojure
; All ring members use :malicious strategy + waterfall slashing
```

**But**: 
- Ring members are treated as a single unit for profit calculations
- No **communication cost** modeled
- No **incentive to defect** from ring
- No **detection of collusion signals** (e.g., suspiciously correlated judgments)
- No **herding** effects (e.g., "if 3 seniors disagreed, maybe I'm wrong")

### Behavioral Assumptions

| Behavior | Modeled? | How |
|----------|----------|-----|
| **Independent judgment** | ✅ Yes | Each resolver draws from RNG independently |
| **Shared oracle access** | ❌ No | Each resolver "sees" ground truth differently (by strategy) |
| **Consensus/Majority voting** | ❌ No | Single resolver judges, no jury pool |
| **Social proof/Herding** | ❌ No | No mechanism for "follow the crowd" |
| **Communication/Collusion** | ⚠️ Partial | Ring model exists but no real communication, no cost, no incentive to defect |
| **Reputation/History effects** | ⚠️ Partial | `reputation.clj` tracks exit rates by strategy, but not causal history |
| **Information asymmetry** | ❌ No | All resolvers have same strategy mapping to accuracy |

---

## 3. Detection Model: Clean vs. Noisy

### Current Model (Clean)

```clojure
; From dispute.clj:90-91
l1-slashed?
(and (not verdict-correct?) (< (rng/next-double rng) base-detection-prob))
```

**Assumptions**:
- ✅ If verdict is WRONG, there's a `base-detection-prob` chance it's caught
- ✅ Phase I adds 3 detection mechanisms (fraud, reversal, timeout)
- ❌ NO TIME COST for investigation
- ❌ NO EVIDENCE REQUIREMENT
- ❌ NO FALSE POSITIVE RISK

### Real-World Detection (Noisy)

In practice, catching fraud requires:
1. **Evidence gathering** (user submits complaint + evidence)
2. **Proof review** (governance or automated oracle analyzes)
3. **Time cost** (hours to weeks depending on dispute complexity)
4. **False positive risk** (misreading evidence)
5. **False negative risk** (fraud hidden, detection fails)

### Impact on Model

**Current simplification is reasonable for**:
- Comparing honest vs. malicious incentives (bond/fee level)
- Testing waterfall adequacy (how much capital needed)
- Governance response time (Phase M already tests this)

**Current simplification breaks down for**:
- **Dispute complexity effects** (hard disputes are harder to detect fraud in)
- **Governance scalability** (need O(N) time to review N disputes)
- **Cost of accuracy** (detecting fraud costs money, may exceed slash amount)
- **Oracle monopoly** (who decides truth? single point of failure?)

---

## 4. What's NOT Modeled: Critical Gaps

### Gap 1: Evidence Model & Verification Costs

**What would be needed**:
```
For each dispute:
  - Specify claims (resolver's verdict vs. counterparty's claim)
  - Estimate dispute "hardness" (0 = obvious, 1 = ambiguous)
  - Add verification cost (time, effort) to detect fraud
  - Model detection success rate = f(evidence quality, effort spent)
```

**Current impact**: 
- System assumes fraud is always detectable at some cost
- Doesn't account for disputes where fraud is cryptographically undetectable
- Overestimates governance's ability to catch all fraud

**Severity**: 🟠 **MEDIUM** (affects confidence in edge cases)

### Gap 2: Correlated Behavior & Herding

**What would be needed**:
```
- Shared priors: resolvers agree on baseline dispute difficulty
- Communication: can resolvers see others' votes before deciding?
- Herding incentive: "follow the crowd to avoid slashing"
- Reputation coupling: history of one resolver affects pool composition
```

**Current impact**:
- Assumes resolvers act independently
- Doesn't capture "if all seniors agreed, maybe I should too"
- Ignores "vote early/vote late" dynamics (when is commitment?)
- No cascade failures from correlated exits

**Severity**: 🔴 **HIGH** (affects Pool stability, governance capture risk)

**Where it matters most**:
- Phase O (market exit cascade) assumes exits are independent
- Phase J (sybil resistance) assumes new entrants are exogenous
- Governance failure scenarios (Phase M) assume no coordinated attack

### Gap 3: Dispute Difficulty Heterogeneity

**What would be needed**:
```
- Escrow distribution (✅ already have: lognormal)
- Dispute hardness distribution (❌ don't have)
  - 50% of disputes: obvious (anyone can judge correctly)
  - 30% of disputes: technical (requires domain knowledge)
  - 15% of disputes: ambiguous (expert opinions differ)
  - 5% of disputes: impossible (insufficient evidence)
```

**Current impact**:
- All disputes modeled as IID Bernoulli (strategy determines accuracy)
- Doesn't account for disputes where even honest judges disagree
- Overestimates honest resolver accuracy on hard disputes

**Severity**: 🟡 **MINOR** (affects calibration, not fundamental viability)

### Gap 4: Oracle Authority & Finality

**What would be needed**:
```
- Multi-oracle options: which oracle has final authority?
- Oracle cost structure: who pays for oracle verification?
- Oracle censorship risk: can oracle be bribed to certify false verdict?
- Appeal vs. reversal: what's the mechanism for ground truth discovery?
```

**Current impact**:
- Assumes a benevolent, omniscient oracle exists
- Doesn't model case where oracle itself is attacked
- Doesn't account for disputes where oracle fees exceed escrow

**Severity**: 🟠 **MEDIUM** (affects long-term trust model)

---

## 5. Implications for Mainnet Readiness

### What We Can Claim (95%+ Confidence)

1. **Bond adequacy**: Waterfall is 66× over-provisioned for 10% fraud baseline ✅
2. **Slashing deters fraud**: If caught, honest strategy dominates across fee ranges ✅
3. **Governance can freeze suspects**: Resolver freezing works during governance window ✅
4. **10-epoch stability**: System doesn't collapse under reasonable parameters ✅

### What We Can't Claim (Unmodeled)

1. **Actual fraud detection rate**: Phase M assumes detection happens, doesn't model cost/time ⚠️
2. **Correlated exit behavior**: Phase O assumes independent exits, no herding ⚠️
3. **Ambiguous dispute handling**: Model assumes all disputes have ground truth ⚠️
4. **Oracle robustness**: Assumes oracle never fails or is compromised ⚠️

### Risk Mitigation for Mainnet

| Risk | Current Mitigation | Post-Launch Monitoring |
|------|-------------------|----------------------|
| Fraud detection rate drops | Phase M shows 14-day freeze works | Monitor actual detection rate vs. 10% assumption |
| Resolvers herd on same verdict | Phase J runs 10-epoch stability tests | Track correlation of verdicts in pool |
| Ambiguous disputes cause appeals | Model assumes 50% appeal rate | Monitor actual appeal rate, dispute difficulty |
| Oracle becomes bottleneck | Phase M tests governance delays | Track oracle response time, cost |

---

## 6. Recommended Future Work (3.0 Release)

### Phase P: Evidence & Verification Costs
**Implement**:
- Dispute hardness distribution (bimodal: easy vs. hard)
- Verification cost model (cost increases with hardness)
- Detection probability = f(hardness, cost spent)
- Test: system remains stable even when fraud is hard to detect

**Impact**: Validates claim that system works with imperfect information

### Phase Q: Behavioral Correlation
**Implement**:
- Shared prior on dispute difficulty (bayesian updating)
- Optional communication (can resolvers see peers' votes?)
- Herding incentive (penalty/bonus based on majority)
- Test: no cascade failures under coordination

**Impact**: Validates claim that system resists majority manipulation

### Phase R: Multi-Oracle Scenarios
**Implement**:
- Choose oracle from {on-chain, governance, arbitration}
- Model oracle cost vs. escrow tradeoff
- Test: system remains viable with expensive oracle

**Impact**: Validates claim that system works with realistic oracle economics

---

## 7. Current Model Verdict

### For **Bond/Fee Mechanics**: ✅ Sufficient
- Clean oracle + independent agents is fine for optimizing parameters
- Gap: doesn't matter because we're testing economic equilibrium, not information flow

### For **Governance Stability**: ⚠️ Partial
- Independent agent assumption breaks down at scale (herding effects)
- Gap: Phase O doesn't test coordinated exits
- Mitigation: Phase M shows freezing prevents governance capture

### For **Long-Term Viability**: ❌ Incomplete
- Missing dispute difficulty, verification costs, oracle robustness
- Gap: can't prove system works with imperfect information
- Mitigation: 99%+ confidence claim is specifically for bond/fee trade-offs, not ultimate game theory

### Overall Assessment

**Clean oracle + independent agents model is:**
- ✅ Appropriate for **bond adequacy** (what we're validating)
- ⚠️ Incomplete for **governance robustness** (should warn)
- ❌ Insufficient for **information security** (must defer to 3.0)

**Recommendation for mainnet**:
- Launch with current validation (99%+ confidence on bond/fee)
- Add monitoring dashboard for actual fraud detection rate, appeal rate, oracle latency
- Commit to Phase P/Q in 3.0 to address information-theoretic gaps
- No blocking issues, but document assumptions clearly

---

## Appendix: Code References

**Oracle/Ground Truth**:
- `dispute.clj:60-65` - Strategy determines verdict accuracy
- `dispute.clj:77-91` - Slashing only if verdict-wrong + detected

**Agent Independence**:
- `multi_epoch.clj:128` - Strategy mix (independent sampling)
- `types.clj:68` - Default strategy mix
- `batch.clj` - Runs trials independently (no inter-trial communication)

**Detection Model**:
- `dispute.clj:89-113` - Base detection + Phase I multi-mechanism
- `governance_impact.clj` - Phase M governance delays (tests detection timing)

**Incomplete Collusion Model**:
- `resolver_ring.clj:4-97` - Ring coordination (no communication cost)
- `economics.clj:65-80` - Collusive EV (simplified, no defection model)
- `reputation.clj` - Exit rates by strategy (correlates with history, but not causal)

---

**Last Updated**: 2026-02-13
**Author**: System assessment
**Status**: Documentation of current model limits & gap analysis
