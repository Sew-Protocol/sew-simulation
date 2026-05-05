# Researcher Evidence Pack (≤15 min Reproducibility)

This pack is designed for dispute researchers who want to quickly:
1) reproduce core claims,
2) inspect assumptions,
3) try to falsify conclusions.

---

## 1) Scope and claim framing

### Headline claim
> **Under multi-agent adversarial conditions, governance rotation mid-dispute enables 100% outcome manipulation with zero invariant violations — invisible to per-function audits and fuzz testing.**

Supporting evidence: 22% adversarial success rate across 33 deterministic scenarios, spanning three structurally distinct failure classes (governance manipulation, rational liveness failure, state-machine pressure). In every case, `invariant_violations=0` — the protocol does not malfunction; it is *used correctly* against itself.

This is a **sequence-level composability** claim, not a single-function vulnerability claim.

---

## 2) Scenario pairs to reproduce — three failure classes

Each pair contrasts a healthy baseline against a specific structural failure. Together they cover the three classes:

### Pair 1 — Security: adversarial state-machine pressure
- **baseline:** `s01-baseline-happy-path`
- **candidate:** `s08-state-machine-attack-gauntlet` (6 adversarial events; buyer attempts pre-auth resolution, re-disputes, floods)
- **claim:** 6× adversarial pressure, 0 invariant violations — the protocol absorbs illegitimate load but shows the attack surface.

### Pair 2 — Governance: outcome manipulation via attacker-as-resolver
- **baseline:** `s01-baseline-happy-path`
- **candidate:** `governance-decay-exploit` (attacker holds the custom resolver role; self-resolves in their favour, then drains via pending settlement)
- **claim:** 100% attack success when attacker controls resolver role; funds extracted without triggering any on-chain fault signal.
- **why this over s14/s15:** s14/s15 tests *rejection* of an unauthorized call — a correctness check. This pair tests *successful exploitation* by a party who is technically authorized. That is the harder and more important class.

### Pair 3 — Liveness: resolver fallback impact
- **baseline:** `s17-ieo-dispute-no-resolver-timeout` (dispute with no resolver → funds locked, auto-cancel fires)
- **candidate:** `s18-dr3-kleros-l0-resolves` (Kleros L0 resolver successfully resolves the same class of dispute)
- **claim:** without a fallback resolver module, disputes in the IEO configuration end in permanent fund lock. Kleros L0 integration eliminates this failure mode.
- **why this replaces s01/s04:** s01/s04 shows timeout mechanics in isolation. s17/s18 shows the *consequence gap* — what researchers want to know: does the protocol have a recovery path?

Files live in: `data/fixtures/traces/*.trace.json`

---

## 3) Parameter grid (quick reproducibility matrix)

| Dimension | Values |
|---|---|
| Scenario pair | `(s01,s08)`, `(s01,governance-decay-exploit)`, `(s17,s18)` |
| Input mode | `scenario-definition` (fixture trace JSON) |
| Comparator output | `comparison.json`, `comparison.md` |
| Focus metrics | `attack-attempts`, `disputes-triggered`, `resolutions-executed`, `total-volume` |
| Failure class | security, governance, liveness |

Optional extension grid (after quick pass):
- compare replay-result JSONs from fresh runs (if available),
- add profitability surface outputs from `bb adv:sweep`.

---

## 4) Exact commands (copy/paste)

### 4.1 Generate comparison outputs

```bash
# Pair 1: security — adversarial state-machine gauntlet
bb trace:compare \
  --baseline ./data/fixtures/traces/s01-baseline-happy-path.trace.json \
  --candidate ./data/fixtures/traces/s08-state-machine-attack-gauntlet.trace.json \
  --out-dir results/trace-compare/s01-vs-s08
```

```bash
# Pair 2: governance — attacker-as-resolver self-drain
bb trace:compare \
  --baseline ./data/fixtures/traces/s01-baseline-happy-path.trace.json \
  --candidate ./data/fixtures/traces/governance-decay-exploit.trace.json \
  --out-dir results/trace-compare/s01-vs-governance-decay
```

```bash
# Pair 3: liveness — timeout fund-lock vs Kleros L0 fallback
bb trace:compare \
  --baseline ./data/fixtures/traces/s17-ieo-dispute-no-resolver-timeout.trace.json \
  --candidate ./data/fixtures/traces/s18-dr3-kleros-l0-resolves.trace.json \
  --out-dir results/trace-compare/s17-vs-s18
```

## 4.2 Validate core deterministic gate

```bash
./scripts/test.sh invariants
```

## 4.3 Validate full canonical gate (optional, slower)

```bash
./scripts/test.sh all
```

---

## 5) Expected outputs

For each comparison run:
- `results/trace-compare/<pair>/comparison.json`
- `results/trace-compare/<pair>/comparison.md`

Expected report contents:
- outcome + events processed,
- data mode (`scenario-definition` vs `replay-result`),
- key metric deltas,
- terminal-state summary,
- one-line headline for research sharing.

Expected directional pattern for `(s01,s08)`:
- higher `attack-attempts` in candidate,
- higher dispute/resolution event intensity,
- no claimed invariant break from this comparison artifact alone.

---

## 6) One-page methodology

1. **Use canonical fixture traces** to ensure deterministic, versioned inputs.
2. **Run pairwise comparison** with a fixed baseline (`s01`) and targeted candidate classes.
3. **Track directional deltas** rather than absolute security verdicts in scenario-definition mode.
4. **Separate interpretation layers**:
   - Layer A: sequence/activity deltas (from `trace:compare`),
   - Layer B: invariant validity and protocol-level pass/fail (from `scripts/test.sh invariants` / suites).
5. **Promote claims only when both layers align**:
   - increased adversarial pressure + preserved invariant behavior under tested assumptions.

Method boundary:
- `trace:compare` on fixture traces provides **derived/approximate metrics**.
- Strong causal/security claims should reference replay-result metrics and invariant/suite outcomes.

---

## 7) Skeptic checklist (falsification-oriented)

Use this checklist to challenge the claim quickly.

### A. Input validity checks
- [ ] Are both scenario IDs correct and versioned fixture files?
- [ ] Are we comparing the intended threat class (not mismatched purpose)?

### B. Reproducibility checks
- [ ] Do reruns produce identical `comparison.json` for same commit?
- [ ] Are command lines captured exactly in notes?

### C. Claim falsification checks
- [ ] If `governance-decay-exploit` candidate does **not** produce a successful resolution for the attacker, the governance manipulation claim is falsified.
- [ ] If s17 and s18 produce the same final state, the liveness/fallback claim is falsified.
- [ ] If `s08` candidate does **not** show higher attack/dispute event intensity vs `s01`, the "adversarial pressure" claim for pair 1 is falsified.
- [ ] If deterministic invariant runs fail for the same corpus, the "protocol integrity under tested assumptions" claim is falsified.

### D. Where tests live
- replay + protocol behavior:
  - `test/resolver_sim/protocols/sew/replay_test.clj`
- expectation/theory evaluation:
  - `test/resolver_sim/scenario/expectations_test.clj`
  - `test/resolver_sim/scenario/equilibrium_test.clj`
- canonical execution docs:
  - `docs/testing/RUNNING_TESTS.md`
  - `docs/testing/TEST_SUITE.md`

---

## 8) Reporting template for researchers

When sharing, include:
1. commit SHA,
2. exact pair command,
3. headline line,
4. top 3 metric deltas,
5. skeptic checklist status (pass/fail per item).
