#!/bin/bash

cd "$(dirname "$0")"

echo "═══════════════════════════════════════════════════════════"
echo "Phase P Lite: Minimal Falsification Test"
echo "═══════════════════════════════════════════════════════════"
echo ""

clojure -e "
(require '[resolver-sim.sim.phase-p-lite :as ppl])
(require '[resolver-sim.io.params :as params])

(let [p (params/validate-and-merge \"params/phase-p-lite-baseline.edn\")]
  (println \"\\n📊 Phase P Lite Falsification Test\")
  (println \"Baseline scenario: light load, no correlation\")
  (ppl/run-phase-p-lite p))
" 2>&1
