#!/usr/bin/env bash
# Canonical test runner for sew-simulation.
#
# Usage:
#   ./scripts/test.sh            # run all suites (unit + invariants + fixtures)
#   ./scripts/test.sh unit       # Clojure unit tests only
#   ./scripts/test.sh generators # Generator + equilibrium regression tests (pinned seeds)
#   ./scripts/test.sh invariants # S01–S41 deterministic invariant scenarios only
#   ./scripts/test.sh suites     # fixture suite runner (all-invariants + equilibrium-validation)
#
# Exit code: 0 = all passed, 1 = any failure.

cd "$(dirname "$0")/.."

MODE="${1:-all}"
FAILURES=0

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

run_suites() {
  echo "Running fixture suites (all-invariants + equilibrium-validation)..."
  clojure -M:test -e "
(require '[resolver-sim.sim.fixtures :as f])
(let [suites [:suites/all-invariants :suites/equilibrium-validation]
      results (map (fn [id] [id (f/run-suite id)]) suites)
      any-fail (some (fn [[_ r]] (not (:ok? r))) results)]
  (doseq [[suite-id result] results]
    (println (str suite-id \" → \" (if (:ok? result) \"PASS\" \"FAIL\")))
    (when-not (:ok? result)
      (doseq [r (:results result)]
        (when (not= :pass (:outcome r))
          (println \"  FAIL:\" (:scenario-id r) (:outcome r))))))
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
  suites)
    run_suites || FAILURES=$((FAILURES + 1))
    ;;
  all)
    run_unit       || FAILURES=$((FAILURES + 1))
    echo ""
    run_generators || FAILURES=$((FAILURES + 1))
    echo ""
    run_invariants || FAILURES=$((FAILURES + 1))
    echo ""
    run_suites     || FAILURES=$((FAILURES + 1))
    ;;
  *)
    echo "Unknown mode: $MODE"
    echo "Usage: $0 [unit|generators|invariants|suites|all]"
    exit 1
    ;;
esac

exit $FAILURES
