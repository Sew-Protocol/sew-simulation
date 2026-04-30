#!/usr/bin/env bash

cd "$(dirname "$0")/.."

echo "Running Clojure tests..."
clojure -M:test -e "
(require '[clojure.test :as t])

(require '[resolver-sim.core-tests])
(require '[resolver-sim.protocols.sew.replay-test])

(let [results (t/run-tests
                'resolver-sim.core-tests
                'resolver-sim.protocols.sew.replay-test)]
  (when (pos? (+ (:error results) (:fail results)))
    (System/exit 1)))"
