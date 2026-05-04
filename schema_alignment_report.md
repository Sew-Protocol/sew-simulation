# CDRS v1.1 Schema Alignment Report

## Overview

The current implementation supports a **MINIMAL CDRS v1.1 schema** for regression scenarios. The proposed refined schema describes an **EXTENDED CDRS v1.1** that adds adversarial scenario support with game-theoretic and economic modeling.

---

## Field-by-Field Comparison

### Core Fields (✓ Aligned)

| Field | Implemented | Proposed | Status |
|-------|-------------|----------|--------|
| `:schema-version` | `"1.1"` (string) | `"1.1"` (string) | ✓ Match |
| `:title` | ✓ Present | ✓ Required | ✓ Match |
| `:agents` | ✓ Present | ✓ Required | ✓ Match |
| `:events` | ✓ Present | ✓ Required | ✓ Match |

### ID & Classification (⚠ Type Mismatch)

| Field | Implemented | Proposed | Status |
|-------|-------------|----------|--------|
| `:id` | `"scenarios/s01-baseline-happy-path"` (string) | `:scenarios/example` (keyword) | ⚠ Type difference |
| `:purpose` | `"regression"` (string) | `:regression` (keyword) | ⚠ Type difference |
| `:threat-tags` | ✓ Present (array) | ✓ Expected (array) | ✓ Match |
| `:description` | ✓ Present (not in spec) | Not listed | Extra field |

### Expectations Structure (⚠ Subset)

**Implemented:**
```clojure
:expectations
  :terminal [{:path [...] :equals ...}]
  :metrics  [{:name ... :op ... :value ...}]
```

**Proposed:**
```clojure
:expectations
  :invariants [...]     ;; ← NOT IMPLEMENTED
  :terminal [...]       ;; ✓ Implemented
  :metrics [...]        ;; ✓ Implemented
  :events [...]         ;; ← NOT IMPLEMENTED
```

**Status:** ⚠ Subset — core `:terminal` and `:metrics` present, but missing `:invariants` and `:events`

### Theory Structure (⚠ Core + Extended Missing)

**Implemented:**
```clojure
:theory
  :claim-id "claims/..."
  :claim "Natural language claim"
  :assumptions [...]
  :falsifies-if [{:metric ... :op ... :value ...}]
```

**Proposed (Minimal):**
```clojure
:theory
  :claim-id :claims/example
  :game-class :repeated-stochastic-game      ;; ← NOT IMPLEMENTED
  :equilibrium-concept [:subgame-perfect-equilibrium]  ;; ← NOT IMPLEMENTED
  :mechanism-properties [:incentive-compatibility]     ;; ← NOT IMPLEMENTED
  :threat-model [...]                        ;; ← NOT IMPLEMENTED
  :assumptions [...]
  :falsifies-if [...]
```

**Status:** ⚠ Core present, extended game-theoretic fields missing

### Payoff Model (✗ Not Implemented)

**Proposed:**
```clojure
:payoff-model
  :tracked [:coalition/net-profit :honest/relative-equity]
  :costs {:slashing true :gas true :opportunity-cost true}
```

**Status:** ✗ **Not implemented** — required for adversarial scenarios only

---

## Schema Tiers

### MINIMAL v1.1 (Regression Scenarios) — ✓ IMPLEMENTED

```clojure
{:schema-version "1.1"
 :id :scenarios/s01-baseline-release
 :title "Baseline Successful Release"
 :purpose :regression
 :threat-tags []
 :agents [...]
 :events [...]
 :expectations
 {:terminal [{:path [:workflows "wf0" :status]
              :equals :released}]}}
```

**Status:** ✓ **Fully supported**

### EXTENDED v1.1 (Adversarial Scenarios) — ⚠ PARTIAL

```clojure
{:schema-version "1.1"
 :id :scenarios/ring-attack-repeated
 :title "Resolver Ring Profitability Test"
 :purpose :theory-falsification
 :threat-tags [:collusive-resolvers :sybil-reentry]
 :theory
 {:claim-id :claims/collusion-negative-ev
  :game-class :repeated-stochastic-game                     ;; MISSING
  :mechanism-properties [:collusion-resistance]             ;; MISSING
  :assumptions [...]
  :falsifies-if [...]}
 :payoff-model {...}                                       ;; MISSING
 :agents [...]
 :events [...]
 :expectations {...}}
```

**Status:** ⚠ **Partially supported** — core theory/expectations work, but game-class, mechanism-properties, and payoff-model not yet integrated

---

## Type Inconsistencies

### `:id` Field
- **Current:** String `"scenarios/s01-baseline-happy-path"`
- **Proposed:** Keyword `::scenarios/example`
- **Impact:** Minimal — semantically equivalent, but normalization treats them differently
- **Fix:** Could add keyword conversion to `normalize-scenario`

### `:purpose` Field
- **Current:** String `"regression"`
- **Proposed:** Keyword `:regression`
- **Impact:** Minimal — values like `"regression"` could be converted to `:regression` during normalization
- **Fix:** Could add keyword conversion to `normalize-scenario`

---

## Current vs. Proposed Compatibility Matrix

| Scenario Type | Minimal v1.1 | Extended v1.1 | Notes |
|---------------|:---:|:---:|-------|
| Regression (s01, s04, etc.) | ✓ | ✓ | Fully supported |
| Happy path | ✓ | ✓ | No theory required |
| Dispute resolution | ✓ | ⚠ | Theory present but game-class missing |
| Adversarial (ring-attack) | ✗ | ⚠ | Needs payoff-model integration |
| Multi-epoch trajectory | ✗ | ✗ | Out of scope |

---

## Recommended Migrations

### Phase 1: Type Normalization (Optional, Low Risk)
```clojure
:id       "scenarios/..." → :scenarios/...     (string → keyword)
:purpose  "regression" → :regression           (string → keyword)
```
**Effort:** 10 lines in normalize-scenario
**Benefit:** Full type alignment with proposed schema
**Risk:** None (backward compatible via normalization)

### Phase 2: Extended Theory Fields (Moderate, Future)
Add support for:
- `:game-class` — classify scenario as repeated game, one-shot, etc.
- `:equilibrium-concept` — list equilibrium assumptions
- `:mechanism-properties` — list economic properties being tested
- `:threat-model` — detailed threat assumptions

**Effort:** ~50 lines in theory.clj for field validation and reporting
**Benefit:** Enables game-theoretic scenario analysis
**Risk:** Low — additive only, doesn't break existing scenarios

### Phase 3: Payoff Model Support (High Effort, Required for Adversarial)
Add `:payoff-model` section with:
- `:tracked` — metrics to track for coalition/adversary
- `:costs` — cost model flags (slashing, gas, opportunity cost)

**Effort:** ~100 lines for payoff tracking + cost calculations
**Benefit:** Enables adversarial scenario analysis with economics
**Risk:** Requires new metrics infrastructure

---

## Conclusion

**Current Status:** ✓ **ALIGNED with MINIMAL v1.1 specification**

The implementation fully supports regression scenarios with core expectations and theory evaluation. The proposed refined schema represents an **upward-compatible extension** to support adversarial scenarios with game-theoretic modeling.

**No breaking changes required.** The path forward is:
1. ✓ Current: Support minimal v1.1 (done)
2. Optional: Add type normalization for `:id` and `:purpose`
3. Future: Add extended theory fields (game-class, mechanism-properties)
4. Future: Add payoff-model support for adversarial analysis

All additions would be **backward compatible** with existing regression scenarios.
