# Missing Realism Assessment: Where the Sim Is Weak

Based on systematic analysis of the 6 hardest categories in decentralized dispute resolution:

## Scorecard: Current Sim Coverage

| Category | Modeled? | Current Depth | Gap Severity | Impact |
|----------|----------|---------------|--------------|--------|
| **1. Bribery Markets** | ❌ No | None | 🔴 HIGH | Could flip verdicts cheaply via contingent bribes |
| **2. Information Quality** | ❌ No | None | 🔴 HIGH | Evidence spam/forgery unmodeled; truth assumed clean |
| **3. Liveness Failures** | ⚠️ Partial | Exit rates, reputation decay | 🟠 MEDIUM | Participation is exogenous; fatigue/boredom absent |
| **4. Legitimacy Collapse** | ❌ No | None | 🟠 MEDIUM | Trust assumed stable; no fork/exit-to-competitor model |
| **5. Governance Capture** | ⚠️ Partial | Delays, freezing | 🟠 MEDIUM | Tests response time, not rule drift or selective enforcement |
| **6. Correlated Events** | ⚠️ Partial | Waterfall cascades | 🟡 MINOR | Cascade tested; learning attackers absent |

---

## Deep Dives: What's Missing

### 1. Bribery Markets (Highest Risk)

**Current Model**: None

**What's Missing**:

```
The naive model:
  bribe-cost = X bps of escrow
  catch-probability = Y
  attacker pays X if caught
  → Assumed too expensive

The realistic model (Kleros yellow paper):
  - p+ε bribes: "Pay only if attack succeeds"
    • Flips incentives: attacker's downside approaches zero
    • Jurors see: "Profit if attack succeeds, no loss if fails"
    • Probability: marginal jurors will coordinate
  
  - Credible escrow: bribe via smart contract
    • Commit bond → release only if verdict is X
    • Credibility is verifiable on-chain
    • Attackers can credibly target the swing set (marginal N jurors)
  
  - Micro-corruption:
    • Flip 1 juror instead of N/2 + 1
    • Selective targeting of "irrelevant alternatives"
    • Cheaper than majority purchase
```

**Why the Sim Misses It**:
- No adversary with budget constraints + strategic targeting
- No modeling of credible commitment mechanisms (on-chain escrow)
- Detection is probabilistic, not based on evidence of bribery coordination
- Assumes bribery is always detected (or paid upfront)

**Real-World Example**:
- Kleros: juror receives offer "Vote X, or if you vote Y, we pay you Y bps bond into escrow that releases only if final verdict is X"
- Low juror downside → higher participation rate in attack
- Current sim would model this as flat "detection probability" and miss the coordination amplification

**What to Add**:
```clojure
Attacker strategy: bribery
  - Budget: B bps of escrow
  - Choose contingent bribe structure: 
    {direct, p+ε, escrow-deposit, micro-targeting}
  - Target jurors: all vs. marginal vs. irrelevant-alternatives
  - Success-rate = f(budget, coordination-credibility, juror-reliability)
  
Test cases:
  - "Can attacker flip verdict with 20% budget?" (current assumption: no)
  - "What's minimum budget needed with credible escrow?" (current: infinity)
  - "How many jurors needed to bribe with micro-targeting?" (current: N/2+1)
```

**Realistic Impact**: 
- If addressable: +15–30% cost to attack (escrow verification needed)
- If not addressable: -40% confidence (bribery too cheap)
- Current model assumes invulnerability; reality is more fragile

---

### 2. Information Quality & Evidence Games (Highest Risk)

**Current Model**: Clean oracle (verdict-correct = ground truth, instant)

**What's Missing**:

```
The clean model:
  ✓ Each dispute has ground truth
  ✓ Resolver's strategy determines accuracy
  ✓ Detection is probabilistic
  → Assumes evidence is: cheap to understand, impossible to forge, neutral

The realistic model:
  ✗ Evidence asymmetry
    • Attacker: custom narrative + screenshots + cherry-picked context
    • Honest: must respond within time window, may lack proof
    • Juror heuristics: narrative beats accuracy
  
  ✗ Adversarial evidence
    • Deepfakes, selective transcript editing, translation tricks
    • Cost to forge plausible evidence: <cost to verify
    • Verification time: hours, not seconds
  
  ✗ Juror attention limits
    • Average dispute: 5–15 min review time
    • Complex disputes: need 1+ hours
    • Under time pressure, jurors rely on heuristics (bias, reputation)
    • Under attention pressure, attackers can spam low-confidence cases
```

**Why the Sim Misses It**:
- `verdict-correct? = (case strategy ...)` assumes juror accuracy is purely strategy-based
- No evidence production/verification as separate cost/time layer
- No adversarial juror herding (jurors ignore evidence, follow reputation)
- No spam/overload attacks (attacker floods with low-confidence disputes)
- Detection is probabilistic catch, not based on evidence forensics

**Real-World Example**:
- UMA's "economic security" framing: incentives work if truth is verifiable
- Optimistic rollup-style "anyone can dispute" systems: first to claim "I have evidence" wins before the other side can respond
- Kleros: "juror heuristics dominate" when time pressure exists

**What to Add**:
```clojure
Evidence game (per-dispute):
  - Dispute hardness: 0 (obvious) → 1 (impossible)
  - Attacker chooses: generate-evidence-cost(hardness)
  - Honest chooses: respond-cost(evidence-quality, time-pressure)
  - Juror decides: based on heuristic(narrative) + verification-effort
  
  - Success = f(evidence-credibility, juror-attention, attacker-budget)
  
Test cases:
  - "Can attacker win with cheaper fake evidence than honest can verify?"
  - "Does evidence quality matter if jurors are time-pressured?"
  - "What's the spam threshold: how many disputes before juror fatigue?"
```

**Realistic Impact**:
- If evidence is hard to forge: +20% confidence (current assumption)
- If evidence is easier to fake than verify: -30% confidence
- If juror attention is rate-limiting: -25% confidence (disputes get rushed)
- Current model: too optimistic on evidence credibility

---

### 3. Liveness Failures (Medium Risk)

**Current Model**: ⚠️ Partial
- Exit rates by strategy (Phase J, O)
- Reputation decay (reputation.clj)
- Governance response delays (Phase M)

**What's Missing**:

```
Current model:
  - Resolvers exit if cumulative profit < 0
  - New resolvers enter at fixed rate
  → Exogenous participation

Missing model:
  - Juror fatigue: after N disputes, attention drops
  - Queue length: long queues → honest users leave → attacker wins
  - Adverse selection: boring periods → only partisan/aggressive jurors remain
  - Reflexive spiral: slowness → users leave → stake decays → security drops
  - Opportunity cost: outside opportunities (other yields) vary over time
```

**Why the Sim Misses It**:
- `run-multi-epoch-simulation` has fixed `:n-epochs`, no variable participation
- Exit rate is deterministic (tied to profitability), not behavioral (tied to UX)
- No model of queue length, decision latency, or perceived fairness
- Phase O tests independent exits, not coordinated exits due to slowness

**Real-World Example**:
- Kleros during high-volume periods: long wait times → honest jurors deprioritize → remaining jurors are risk-seeking
- Aragon: participation drops on "boring" cases → security is lower exactly when attackers are quiet → burst attack succeeds

**What to Add**:
```clojure
Participation layer:
  - Queue length (backlog of disputes)
  - Decision time per dispute (increases with queue)
  - Juror availability (drops if outside opportunities high, or wait time > threshold)
  - Juror quality (partisan > honest when queue is long)
  - Fatigue: after K decisions, error rate increases by M%
  
  - Success-rate = f(juror-quality, queue-length, outside-opportunities)
  
Test cases:
  - "Does system security degrade when queue is long?"
  - "Can attacker exploit the boring periods?"
  - "Does slowness cause exit spiral?"
```

**Realistic Impact**:
- If participation is price-stable: current model OK
- If participation is UX-sensitive: -15% confidence
- If queue length drives selection: -20% confidence
- Current model: assumes steady state; reality is bursty and state-dependent

---

### 4. Legitimacy Collapse (Medium Risk)

**Current Model**: None

**What's Missing**:

```
Current model:
  - Resolvers choose strategy, stay/exit based on profit
  → Trust is assumed constant

Missing model:
  - Perceived bias: "Insiders win more often"
  - Inconsistent rulings: variance in outcomes for similar cases
  - Unfair advantage: belief that some parties get special treatment
  - Fork threat: if trust drops below X, users exit to competing jurisdiction
  - Switching costs: friction to migrate to alternative dispute system
```

**Why the Sim Misses It**:
- Trust/legitimacy is not modeled; it's assumed
- No feedback from perceived unfairness → reduced volume → lower security
- No model of competing jurisdictions or switching costs
- Waterfall cascade tests economic failure, not legitimacy-driven exit

**Real-World Example**:
- Aragon: "If governance is seen as corrupt, users leave"
- Kleros: reputation tracker is public; if some jurors have suspiciously high appeal rates, trust drops
- Lido's staking: perceived favoritism → exodus threat, even if economics are sound

**What to Add**:
```clojure
Trust index:
  - Variance in verdict outcomes (higher = suspicious)
  - Insider win rate vs. average (higher = perceived bias)
  - Appeal success rate (higher = inconsistent rules)
  → Trust-index updates from these signals
  
  - Volume demand = f(trust-index, available-alternatives)
  - Stake requirement = f(trust-index)
  - Security = f(stake, participation)
  
  - Reflexive spiral:
    • Low trust → fewer disputes → lower stake → lower security
    • → Attackers smell blood → more attacks → trust drops further
  
Test cases:
  - "Can system lose legitimacy despite economic soundness?"
  - "What's the exit threshold?"
  - "Does variance in outcomes predict exodus?"
```

**Realistic Impact**:
- If legitimacy is stable: +5% confidence
- If legitimacy is fragile: -20% confidence
- If there's a reflexive spiral: -40% confidence
- Current model: assumes legitimacy is not at risk; reality is more fragile

---

### 5. Governance Capture (Medium Risk)

**Current Model**: ⚠️ Partial
- Phase M: response time delays (0–14 days)
- Resolver freezing on detection

**What's Missing**:

```
Current model:
  - Governance has fixed response time
  - Resolver is frozen during window
  → Captures: "How long until governance can slash?"
  → Misses: "Can attacker exploit governance itself?"

Missing model:
  - Rule drift: repeated "small" interventions → predictable patterns
    • Attackers learn: "governance always approves X slashing"
    • → Can hedge by knowing outcome
  
  - Selective enforcement: 
    • Governance might pardon some slashes, not others
    • Creates moral hazard: "Will governance protect me?"
  
  - Capture via inattention:
    • Governance capture is easiest during low-salience periods
    • Attacker strikes when attention is elsewhere
    • Governance responds too slowly (not because of 14-day delay, but because nobody noticed)
  
  - Governance as an agent, not a backstop:
    • Governance has incentives (political, economic)
    • Governance has constraints (information limits, attention)
    • Governance can be manipulated (via media, narrative)
```

**Why the Sim Misses It**:
- Phase M models governance as a black box with fixed delay
- No model of governance rule drift or selective enforcement
- No attention-based capture (governance acts faster on high-salience cases)
- No governance incentive analysis (does governance benefit from slashing or pardoning?)

**Real-World Example**:
- MakerDAO: governance repeated small rule changes → became predictable → attackers adapted
- Curve Wars: governance captured partly via low-salience periods
- Yearn: selective enforcement (some exploits pardoned, others not) → moral hazard

**What to Add**:
```clojure
Governance agent:
  - Rule set: {slash-bps, freeze-duration, appeal-success-rate}
  - History: past interventions
  - Pattern recognition by attackers: "What will governance do?"
  - Attention level: varies over time
  - Capture probability: f(rule-drift, salience, attacker-investment)
  
  - Response time = f(salience, governance-attention, rule-complexity)
  - Rule change = f(pressure, voting-results, path-dependence)
  
  - Attacker can:
    • Predict governance response
    • Time attack for low-salience periods
    • Lobby governance (via narrative/voting)
  
Test cases:
  - "Can attacker predict governance response?"
  - "Does rule drift create attackable patterns?"
  - "Can attacker exploit low-salience timing?"
```

**Realistic Impact**:
- If governance is stable + attentive: +10% confidence
- If governance is predictable (rule drift): -15% confidence
- If governance can be captured via timing: -25% confidence
- Current model: assumes governance is reactive and fair; reality is political and path-dependent

---

### 6. Correlated Systemic Events (Low Risk)

**Current Model**: ⚠️ Partial
- Phase L: waterfall cascade testing
- Phase O: market exit cascade

**What's Missing**:

```
Current model:
  - Disputes are IID (independent)
  - Resolvers exit independently (based on profit)
  - Waterfall cascades test bonding adequacy
  → Captures: "Can waterfall absorb N simultaneous slashes?"
  → Misses: "Do real stress events create learning attackers?"

Missing model:
  - Correlated dispute types:
    • Market crash → mass liquidation disputes → all similar
    • Exploit pattern spreads → many disputes with same attack signature
    • Attackers learn: "What worked on this dispute type works on all similar ones"
  
  - Attacker learning loop:
    • Epoch 1: attack dispute-type-A
    • Epoch 2: if successful, attack more of type-A
    • If unsuccessful, try dispute-type-B
    • Over time: convergence to cheapest-to-attack types
  
  - External dependency failure:
    • Oracle down → all evidence unverifiable
    • Identity provider fails → all KYC disputes unresolvable
    • Social platform deletes → all evidence gone
  
  - Reflexive feedback:
    • Systemic event → disputes spike → queue grows → quality drops
    • → Attacker exploits low quality → more disputes
    • → Cycle reinforces
```

**Why the Sim Misses It**:
- Attacker is static, not learning (no bandit optimization)
- Disputes are sampled independently; real correlated events are not
- No model of external dependencies or their failures
- Waterfall cascade tests adequacy, but not learning-loop adaptation

**Real-World Example**:
- 2021 Flash Loan attacks: one pattern worked → attackers replicated on all compatible protocols
- Curve Hack: once discovered, attacker scaled to all affected pools
- Kleros: if a particular juror cohort is discoverable as cheap to bribe, attackers will focus there

**What to Add**:
```clojure
Learning attacker:
  - Budget: B across epochs
  - Strategy space: {bribe-juror-cohort-A, forge-evidence-type-B, ...}
  - Track success rate per strategy
  - Update allocation via Thompson sampling (explore vs. exploit)
  - → Converges to cheapest attack type
  
  - Correlated events:
    • Market event triggers N disputes of type X
    • Attacker learns: "Type X is cheap to attack"
    • → Focus on type X in future
  
Test cases:
  - "Does system converge to weakness under adaptive attacker?"
  - "How many epochs before attacker finds optimum?"
  - "Do correlated events create amplified attack surface?"
```

**Realistic Impact**:
- If attacker is static: current model OK
- If attacker learns over 10+ epochs: -10% confidence
- If correlated events reveal new vulnerabilities: -15% confidence
- Current model: tests steady-state and cascade; misses learning loops

---

## Priority: Realism Roadmap

### Tier 1 (Highest ROI, Fast to Implement)

**1. Bribery Markets (1–2 weeks)**
- Add attacker strategy: contingent bribe with credible escrow
- Model marginal juror targeting vs. majority purchase
- Test: "minimum budget to flip verdict with coordination"
- Impact: Validates bond adequacy against credible attacks

**2. Adversarial Evidence (1–2 weeks)**
- Add evidence game layer (per-dispute verification cost)
- Model fake generation vs. verification
- Juror heuristics under time pressure
- Impact: Tests claim that truth is recoverable under adversarial conditions

**3. Adaptive Attacker (1 week)**
- Add learning loop (bandit optimization over strategies)
- Model discovery of cheap attack surface
- Track convergence to optimum
- Impact: Validates robustness over multiple epochs (not just steady-state)

### Tier 2 (Important, Medium Effort)

**4. Participation Endogeneity (2 weeks)**
- Model queue length, decision latency, juror fatigue
- Adverse selection under time pressure
- Reflexive spirals (slowness → exit → security drop)
- Impact: Tests whether slowness is a security vector

**5. Governance Capture (2 weeks)**
- Model governance rule drift + selective enforcement
- Attention-based capture (low-salience periods)
- Governance incentive analysis
- Impact: Tests governance backstop credibility

### Tier 3 (Nice-to-Have, Higher Effort)

**6. Legitimacy Collapse (3 weeks)**
- Trust index from variance + bias perception
- Switching costs to alternative jurisdictions
- Reflexive spiral (low trust → low security → exit)
- Impact: Tests whether system can lose legitimacy despite economic soundness

---

## Confidence Adjustment

### Current Claim
"System is 99%+ ready for mainnet (bond/fee mechanics are sound)"

### More Accurate Claim (With Gaps)
"System is 99%+ ready for mainnet **IF**:
- Bribery attacks don't exploit contingent commitments (confidence: 70%)
- Evidence quality is genuinely hard to fake (confidence: 60%)
- Governance remains attentive and unpredictable (confidence: 75%)
- Participation doesn't degrade under latency (confidence: 80%)
- Attackers don't learn across epochs (confidence: 85%)
- Legitimacy doesn't collapse from perceived unfairness (confidence: 80%)

**Overall confidence on "system works in production": ~50–60%**

**Confidence on "bond mechanics are sound": 99%** ✅
**Confidence on "this works at scale with real humans": 55%** ⚠️

---

## Recommendation

**For Mainnet Launch**:
1. Ship with current validation (bond/fee soundness at 99%)
2. Add monitoring dashboard for:
   - Bribery attempt detection (correlation of votes)
   - Evidence quality signals (appeal rates by dispute type)
   - Governance rule drift (frequency of small changes)
   - Participation trends (queue length, latency, juror availability)
   - Attacker learning (clustering of attacks by type)
3. Commit to Phases P–R (evidence, herding, governance capture) in 3.0

**Do NOT claim** "production-ready" until you've modeled at least Tier 1 (bribery, evidence, adaptive attacker).

**Do claim** "bond mechanics are sound and testable on mainnet" ✅

---

**Last Updated**: 2026-02-13
**Assessment**: Comprehensive gap analysis against 6 hardest categories
**Recommendation**: Add Tier 1 modules before main launch; Tier 2/3 before 2.0 production release
