#!/usr/bin/env bash

cd "$(dirname "$0")"

echo "Running Clojure tests..."
clojure -M:test \
  -e "(do
    (require '[clojure.test :as t])
    (require '[resolver-sim.core-test])
    (t/run-tests 'resolver-sim.core-test))"
