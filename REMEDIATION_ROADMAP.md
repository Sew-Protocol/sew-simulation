# Remediation Roadmap: From Broken (40%) to Robust (90%+)

**Current State**: Phase P Lite reveals Scenario C (Broken)  
**Target State**: 90%+ confidence under realistic conditions  
**Timeline**: 6-8 weeks (phased implementation)  
**Recommendation**: Option A - Full Redesign with Multi-Level Adjudication

---

## Why Current System Fails

**The Herding Cascade**:
1. Panel of 3 resolvers votes by majority
2. At rho ≥ 0.5, resolvers herding influence > individual analysis
3. Attacker captures 1-2 resolvers (easy under load)
4. Honest minority switches vote due to slashing fear
5. Attack succeeds, dominance inverts to attacker favor
6. System tips from safe → broken

**Critical parameter**: rho > 0.3 is bifurcation point
- rho < 0.3: System holds (dominance > 1.0x)
- rho ≥ 0.3: Herding overwhelms → dominance < 1.0x

---

## The Three-Layer Solution

### LAYER 1: Multi-Level Adjudication
**Goal**: Break herding cascade by isolating layers

**Current**: Single L1 panel (3 resolvers, majority decides)  
**Proposed**: 3-level system

```
L1 (Dispute court): 3 resolvers, simple majority
  ↓ (if appealed OR consensus < 95%)
L2 (Appeal court): 7 jurors, 5+ majority (harder to corrupt)
  ↓ (if L2 unanimous wrong)
L3 (Final appeals): 11 senior jurors, 8+ majority
  → Governance can intervene if L3 fails
```

**Why this breaks herding**:
- L1 corruption ≠ L2 corruption (different people)
- L2 majority = 5 votes (need to corrupt 3+ people)
- L3 majority = 8 votes (need to corrupt 4+ people)
- Corruption cost scales exponentially up levels
- Early herding at L1 can be overturned at L2

**Implementation**: Phase Q (2 weeks)
- [ ] Design appeals architecture
- [ ] Implement appeal-triggered mechanism
- [ ] Add escalation logic to dispute.clj
- [ ] Test: dominance at rho=0.8, medium load (target > 1.5x)

### LAYER 2: Evidence Oracles
**Goal**: Remove dispute difficulty as attack surface

**Current**: Difficulty is implicit, hard cases have 80% lower detection  
**Proposed**: External oracle system for hard cases

```
Dispute difficulty detection:
  - If dispute involves on-chain data → automatic L1
  - If dispute involves off-chain claims → automatic L2
  - If claims require external context → automatic L3 + oracle

Evidence oracle:
  - For hard cases, external service provides verified facts
  - Hard cases become medium cases (detection 10% → 6%)
  - Removes "ambiguity is attacker advantage" dynamic
```

**Why this works**:
- Easy/medium cases: No change (oracle not needed)
- Hard cases: Ambiguity resolved → easier to detect fraud
- Attacker no longer has 80% detection advantage
- Honest resolver advantage restored

**Implementation**: Phase R (2 weeks)
- [ ] Design oracle interface (minimal scope)
- [ ] Implement hard-case routing logic
- [ ] Integrate with difficulty detection
- [ ] Test: dominance with uniform difficulty (no tail)

### LAYER 3: Reputation Weighting
**Goal**: Make corruption progressively more expensive

**Current**: All resolvers weighted equally (1 vote = 1 vote)  
**Proposed**: Vote weight scales with past accuracy

```
Reputation score:
  - Earned: +1 per correct verdict
  - Lost: -2 per slashed verdict
  - Floor: 0 (can't go negative)
  - Range: 0-100

Vote weight:
  - weight = reputation / 100
  - Accurate juror (rep=80): 0.8x vote influence
  - New juror (rep=50): 0.5x vote influence
  - Slashed juror (rep=0): votes don't count

Impact on L1 majority:
  - Need 2 votes, but weighted by reputation
  - Attacking an accurate juror is expensive
  - Attacking new jurors is cheap but doesn't flip vote
```

**Why this works**:
- Attackers can't afford to corrupt good jurors
- Bad jurors' votes matter less
- System self-selects toward honest jurors
- Herding less effective against experienced consensus

**Implementation**: Phase S (2 weeks)
- [ ] Design scoring system
- [ ] Implement reputation tracking in resolver_ring.clj
- [ ] Add weight calculations to panel voting
- [ ] Test: dominance with herding (rho=0.8, weighted votes)

---

## Full Implementation Plan

### Phase Q: Multi-Level Adjudication (Weeks 1-2)

**Deliverables**:
- [ ] `src/resolver_sim/model/appeals_architecture.clj` - 3-level system
- [ ] Updated `dispute.clj` - appeal triggering logic
- [ ] Test: P_Q_appeals.clj validating L2 overturns L1 herding
- [ ] Metric: dominance > 1.5x at rho=0.8, medium load

**Success criteria**:
- Appeals reduce L1 dominance impact by 50%+
- L2 independent of L1 outcomes
- Dominance returns to > 1.0x even with herding

### Phase R: Evidence Oracles (Weeks 3-4)

**Deliverables**:
- [ ] `src/resolver_sim/model/evidence_oracle.clj` - oracle interface
- [ ] Hard-case routing logic in difficulty.clj
- [ ] Test: P_R_evidence_oracles.clj
- [ ] Metric: uniform difficulty performance

**Success criteria**:
- Hard cases no longer preferred attack target
- Detection probability uniform across difficulty
- Dominance stable regardless of difficulty distribution

### Phase S: Reputation System (Weeks 5-6)

**Deliverables**:
- [ ] `src/resolver_sim/model/reputation.clj` - scoring + weighting
- [ ] Updated `panel_decision.clj` - weighted voting
- [ ] Test: P_S_reputation_herding.clj
- [ ] Metric: dominance with rho=0.8 weighted votes

**Success criteria**:
- Reputation-weighted panels resist herding
- Good jurors' opinions dominate
- Dominance > 1.5x even at rho=0.8

### Phase T: Full Integration + Revalidation (Weeks 7-8)

**Deliverables**:
- [ ] Integration: Q + R + S combined
- [ ] Re-run Phases G-O (backward compatibility check)
- [ ] Full Phase P Lite sweep (3 trials per combo, 48 scenarios)
- [ ] Final validation report

**Success criteria**:
- Phases G-O still pass (no regression)
- Phase P Lite: Scenario A (robust, > 1.5x across all conditions)
- Confidence > 90%

---

## Key Design Decisions

### Why 3-Level Not 2-Level?
- **2-level**: L2 can override L1, but needs L2 unanimity to be safe
- **3-level**: L3 catches L2 failures, provides governance escape hatch
- **Why matters**: Prevents situation where L2 is itself herded

### Why Reputation Not Just Reputation?
- **Reputation alone**: Jurors care about score, but don't affect vote weight
- **Weighted voting**: Good jurors' analysis actually matters more
- **Why matters**: Creates incentive for accuracy that feeds back into voting

### Why Oracle + Levels?
- **Oracle alone**: Solves hard cases but doesn't stop herding on medium cases
- **Levels alone**: Escalation works but still vulnerable on hard cases
- **Together**: Removes tail vulnerability + isolates herding to lowest levels

---

## Testing Strategy

### Quick Validation (Week 2)
```
Phase P Lite + Multi-Level Adjudication
- Load: medium, heavy
- Rho: 0.0, 0.5, 0.8
- Expected: dominance > 1.0x at rho=0.5 (improvement from 0.33x)
```

### Medium Validation (Week 6)
```
Phase P Lite + All Three Layers
- Load: light, medium, heavy, extreme
- Rho: 0.0, 0.2, 0.5, 0.8
- Budget: 0%, 10%, 25%
- Expected: dominance > 1.5x across all (Scenario A)
```

### Final Validation (Week 8)
```
- Full Phase P Lite sweep: 3 trials per combo
- Phases G-O regression tests
- Phase T final integration test
- Target: 90%+ confidence documented
```

---

## Rollback Plan

If any phase fails testing:

1. **Phase Q failure**: Revert to single L1, try different appeal threshold
2. **Phase R failure**: Keep oracle for hard cases only, don't auto-route
3. **Phase S failure**: Reduce reputation weighting from 0-100 to 0-50 (smaller effect)

Each layer is designed to be independently functional. Failure in one doesn't require full redesign.

---

## Resource Estimate

| Phase | Engineering | Testing | Review | Total |
|-------|-------------|---------|--------|-------|
| Q | 3 days | 2 days | 1 day | 1 week |
| R | 3 days | 2 days | 1 day | 1 week |
| S | 4 days | 2 days | 1 day | 1.5 weeks |
| T | 3 days | 4 days | 1 day | 1.5 weeks |
| **TOTAL** | **13 days** | **10 days** | **4 days** | **6-8 weeks** |

**Key assumptions**:
- Parallel Phase development (R starts while Q testing)
- Clojure experience on team
- No blocking dependencies

---

## Confidence Trajectory

| Phase | Status | Dominance (worst case) | Confidence |
|-------|--------|------------------------|-----------|
| Current | Broken | 0.33x | 40% |
| +Q | Improving | 0.75x | 55% |
| +R | Solid | 1.10x | 70% |
| +S | Robust | 1.50x | 85% |
| +T (Full) | Validated | > 1.50x | 90%+ |

---

## Alternative: Hybrid (Faster)

If 6-8 weeks is unacceptable, hybrid approach:

**Phase Q only** (Multi-Level): 2 weeks
- Gets dominance from 0.33x → 1.10x at problem areas
- Doesn't solve hard-case tail (oracle missing)
- Not sufficient alone (confidence 65%)

**+ Parameter tuning** (bonds 3x, herding caps):
- Gets dominance from 1.10x → 1.40x
- Still vulnerable to sophisticated attacks
- Acceptable only if governance willing to monitor closely
- Confidence: 70-75%

**Timeline**: 3 weeks total  
**Confidence**: 70-75% (vs 90%+ for full redesign)

**Verdict**: Hybrid is 3-week delay for 15% confidence loss. Full redesign recommended.

---

## Success Metrics (Phase T Acceptance Criteria)

**Mechanism validation**:
- ✅ All phases G-O tests still pass
- ✅ No regression in single-resolver cases

**Realism validation**:
- ✅ Phase P Lite Scenario A classification
- ✅ Dominance > 1.5x across all 48 test scenarios
- ✅ No dominance < 1.0x in any parameter combination

**Robustness validation**:
- ✅ Herding (rho) reduced impact by 90%+
- ✅ Evidence cost not exploitable (oracle covering hard cases)
- ✅ Load doesn't destabilize (7-layer L2 prevents correlation)

---

## Conclusion

The redesign is complex but feasible:

**Complexity drivers**:
1. Multi-level architecture (adds state management)
2. Reputation system (adds memory/state)
3. Evidence oracles (adds external dependency)

**Complexity is justified by**:
1. Moves from Scenario C (broken) → A (robust)
2. Confidence improves 40% → 90%
3. Mainnet becomes safe vs dangerous

**Key insight**: The three layers work together. Alone, each is partial. Together, they address all three failure modes from Phase P Lite (difficulty, evidence asymmetry, herding).

**Recommendation**: Proceed with full redesign. 6-8 weeks is acceptable cost for safe mainnet.

