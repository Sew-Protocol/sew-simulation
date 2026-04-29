(require '[resolver-sim.contract-model.replay :as replay]
         '[clojure.data.json :as json]
         '[clojure.java.io :as io])

(let [scenario (with-open [r (io/reader "data/fixtures/traces/s04-dispute-timeout-autocancel.trace.json")] (json/read r :key-fn keyword))
      result (replay/replay-scenario scenario)]
  (println "Scenario outcome:" (:outcome result))
  (println "Halt reason:" (:halt-reason result))
  (println "Last violations:" (get-in (last (:trace result)) [:violations])))
