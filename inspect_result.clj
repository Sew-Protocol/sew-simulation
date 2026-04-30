(require '[resolver-sim.sim.fixtures :as f]
         '[resolver-sim.contract-model.replay :as replay]
         '[clojure.pprint :refer [pprint]])

(let [suite (f/compose-suite (f/load-fixture :suites/all-invariants))
      trace (first (:traces suite))
      res (replay/replay-scenario trace)]
  (let [actual (get (:metrics res) :total-escrows)
        expect (get-in trace [:expectations :metrics 0 :value])]
     (println "Actual Type:" (type actual) "Value:" actual)
     (println "Expect Type:" (type expect) "Value:" expect)
     (println "== Match?" (== actual expect))))
