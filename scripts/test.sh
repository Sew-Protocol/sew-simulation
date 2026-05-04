#!/usr/bin/env bash
# Canonical test runner for sew-simulation.
#
# Usage:
#   ./scripts/test.sh            # run all suites (unit + invariants + fixtures)
#   ./scripts/test.sh unit       # Clojure unit tests only
#   ./scripts/test.sh generators # Generator + equilibrium regression tests (pinned seeds)
#   ./scripts/test.sh invariants # S01–S41 deterministic invariant scenarios only
#   ./scripts/test.sh contracts  # Cross-layer contract checks (proto/service/wire compatibility)
#   ./scripts/test.sh suites     # fixture suite runner (all-invariants + equilibrium-validation)
#   ./scripts/test.sh triage     # Failure triage grouped by purpose/threat-tag
#
# Exit code: 0 = all passed, 1 = any failure.

cd "$(dirname "$0")/.."

MODE="${1:-all}"
FAILURES=0
ARTIFACT_DIR="results/test-artifacts"
ARTIFACT_FILE="$ARTIFACT_DIR/test-summary.json"
RUN_ID="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$ARTIFACT_DIR"

TARGET_LOG=""

start_target() {
  TARGET_LOG="$ARTIFACT_DIR/.target-${1}-${RUN_ID}.log"
  : > "$TARGET_LOG"
}

record_target() {
  target="$1"
  code="$2"
  dur_ms="$3"
  status="pass"
  if [ "$code" -ne 0 ]; then
    status="fail"
  fi
  printf '%s,%s,%s,%s,%s\n' "$target" "$status" "$code" "$dur_ms" "$TARGET_LOG" >> "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
}

run_target() {
  target="$1"
  func="$2"
  start_target "$target"
  t0="$(date +%s)"
  "$func" >"$TARGET_LOG" 2>&1
  code=$?
  t1="$(date +%s)"
  dur_ms=$(( (t1 - t0) * 1000 ))
  record_target "$target" "$code" "$dur_ms"
  cat "$TARGET_LOG"
  return "$code"
}

run_unit() {
  echo "Running unit tests..."
  clojure -M:test -e "
(require '[clojure.test :as t])
(require '[resolver-sim.core-tests])
(require '[resolver-sim.protocols.sew.replay-test])
(require '[resolver-sim.scenario.expectations-test])
(require '[resolver-sim.scenario.equilibrium-test])
(require '[resolver-sim.sim.multi-epoch-test])
(let [results (t/run-tests
                'resolver-sim.core-tests
                'resolver-sim.protocols.sew.replay-test
                'resolver-sim.scenario.expectations-test
                'resolver-sim.scenario.equilibrium-test
                'resolver-sim.sim.multi-epoch-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
  return $?
}

run_invariants() {
  echo "Running S01–S41 deterministic invariant scenarios..."
  clojure -M:run -- --invariants
  return $?
}

run_generators() {
  echo "Running generator regression tests (pinned seeds)..."
  clojure -M:test -e "
(require '[clojure.test :as t])
(require '[resolver-sim.generators.equilibrium-test])
(require '[resolver-sim.generators.fixtures-test])
(require '[resolver-sim.properties.invariants-test])
(let [results (t/run-tests
                'resolver-sim.generators.equilibrium-test
                'resolver-sim.generators.fixtures-test
                'resolver-sim.properties.invariants-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
  return $?
}

run_contracts() {
  echo "Running cross-layer contract checks (proto/service/wire compatibility)..."

  # Proto service + RPC contract
  grep -q 'package sew.simulation;' proto/simulation.proto
  grep -q 'service SimulationEngine' proto/simulation.proto
  grep -q 'rpc StartSession' proto/simulation.proto
  grep -q 'rpc Step' proto/simulation.proto
  grep -q 'rpc DestroySession' proto/simulation.proto

  # Python client must target same service/methods
  grep -q '_SERVICE = "sew.simulation.SimulationEngine"' python/sew_sim/grpc_client.py
  grep -q 'StartSession' python/sew_sim/grpc_client.py
  grep -q 'DestroySession' python/sew_sim/grpc_client.py

  # Clojure server must expose same RPC names and snake_case↔kebab-case bridge
  grep -q 'SimulationEngine' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "StartSession"' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "Step"' src/resolver_sim/server/grpc.clj
  grep -q 'make-method "DestroySession"' src/resolver_sim/server/grpc.clj
  grep -q 'snake_case' src/resolver_sim/server/grpc.clj

  # Scenario naming convention sanity checks for Sxx traces
  python - <<'PY'
import json
import pathlib
import re
import sys

root = pathlib.Path('data/fixtures/traces')
pat = re.compile(r'^s\d{2}[a-z]?-[a-z0-9\-]+\.trace\.json$')
bad = []
for p in sorted(root.glob('s*.trace.json')):
    if not pat.match(p.name):
        bad.append(f"bad-filename:{p}")
        continue
    try:
        obj = json.loads(p.read_text())
    except Exception as e:
        bad.append(f"bad-json:{p}:{e}")
        continue
    sid = obj.get('id')
    if sid:
        sid_s = str(sid)
        expected = p.name.replace('.trace.json', '')
        if sid_s != expected:
            bad.append(f"id-mismatch:{p}:id={sid_s}:expected={expected}")

if bad:
    print("Scenario naming/ID convention checks failed:")
    for b in bad:
        print(" -", b)
    sys.exit(1)

print("Scenario naming/ID convention checks passed")
PY

  return $?
}

run_triage() {
  echo "Running failure triage (purpose/threat-tag grouping)..."
  clojure -M -m resolver-sim.scenario.triage ${1:-data/fixtures/traces}
  return $?
}

run_suites() {
  echo "Running fixture suites (all-invariants + equilibrium-validation + spe-validation)..."
  clojure -M:test -e "
(require '[resolver-sim.sim.fixtures :as f])
(let [suites [:suites/all-invariants :suites/equilibrium-validation :suites/spe-validation]
      results (map (fn [id] [id (f/run-suite id)]) suites)
      any-fail (some (fn [[_ r]] (not (:ok? r))) results)]
  (doseq [[suite-id result] results]
    (println (str suite-id \" → \" (if (:ok? result) \"PASS\" \"FAIL\")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println (str \"  FAIL: \" (:trace-id r) \" [\" (:outcome r) \"]\"))))))
  (when any-fail (System/exit 1)))"
  return $?
}

case "$MODE" in
  unit)
    run_unit || FAILURES=$((FAILURES + 1))
    ;;
  invariants)
    run_invariants || FAILURES=$((FAILURES + 1))
    ;;
  generators)
    run_generators || FAILURES=$((FAILURES + 1))
    ;;
  contracts)
    run_target contracts run_contracts || FAILURES=$((FAILURES + 1))
    ;;
  triage)
    run_target triage run_triage || FAILURES=$((FAILURES + 1))
    ;;
  suites)
    run_target suites run_suites || FAILURES=$((FAILURES + 1))
    ;;
  all)
    : > "$ARTIFACT_DIR/.targets-${RUN_ID}.csv"
    run_target unit run_unit || FAILURES=$((FAILURES + 1))
    echo ""
    run_target generators run_generators || FAILURES=$((FAILURES + 1))
    echo ""
    run_target contracts run_contracts || FAILURES=$((FAILURES + 1))
    echo ""
    run_target invariants run_invariants || FAILURES=$((FAILURES + 1))
    echo ""
    run_target suites run_suites || FAILURES=$((FAILURES + 1))
    ;;
  *)
    echo "Unknown mode: $MODE"
    echo "Usage: $0 [unit|generators|contracts|invariants|suites|triage|all]"
    exit 1
    ;;
esac

if [ -f "$ARTIFACT_DIR/.targets-${RUN_ID}.csv" ]; then
  python - <<PY
import csv, json, pathlib
csv_path = pathlib.Path("$ARTIFACT_DIR/.targets-${RUN_ID}.csv")
rows = []
for r in csv.reader(csv_path.read_text().splitlines()):
    if len(r) != 5:
        continue
    rows.append({
        "target": r[0],
        "status": r[1],
        "exit_code": int(r[2]),
        "duration_ms": int(r[3]),
        "log_file": r[4]
    })
out = {
  "run_id": "$RUN_ID",
  "mode": "$MODE",
  "overall_status": "pass" if $FAILURES == 0 else "fail",
  "failure_count": $FAILURES,
  "targets": rows,
}
pathlib.Path("$ARTIFACT_FILE").write_text(json.dumps(out, indent=2))
print(f"Wrote machine-readable summary: $ARTIFACT_FILE")
PY
fi

exit $FAILURES
