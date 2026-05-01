#!/usr/bin/env bash

cd "$(dirname "$0")/.."

echo "Running Clojure tests..."
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
