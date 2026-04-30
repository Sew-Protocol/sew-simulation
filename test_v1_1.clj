(require '[resolver-sim.sim.fixtures :as f]
         '[resolver-sim.sim.reporter :as r])

(let [suite-res (f/run-suite :suites/all-invariants)]
  (r/print-suite-results suite-res))
