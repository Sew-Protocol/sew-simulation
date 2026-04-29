(require '[resolver-sim.contract-model.replay :as replay]
         '[resolver-sim.protocols.sew :as sew]
         '[clojure.data.json :as json]
         '[clojure.java.io :as io])

(let [scenario (with-open [r (io/reader "data/fixtures/traces/s04-dispute-timeout-autocancel.trace.json")] (json/read r :key-fn keyword))
      result (replay/replay-with-protocol sew/protocol scenario)]
  (println "Outcome:" (:outcome result))
  (println "Halt reason:" (:halt-reason result))
  (when (= :fail (:outcome result))
    (println "Final trace status:" (-> result :trace last :result))
    (println "Final trace violations:" (-> result :trace last :violations))))
