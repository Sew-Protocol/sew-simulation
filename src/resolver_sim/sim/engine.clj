(ns resolver-sim.sim.engine
  "Unified simulation engine for Phase-series experiments.
   Consumes declarative scenario definitions and handles the simulation loop,
   result aggregation, and reporting."
  (:require [resolver-sim.model.rng :as rng]
            [resolver-sim.io.results :as results]
            [clojure.java.io :as io]))

(defn run-epoch-simulation
  "Run a multi-epoch simulation based on a scenario definition.
   
   Scenario keys:
     :label        — Display name
     :initial-state — Starting map
     :update-fn    — (fn [epoch state params rng]) -> new-state
     :epochs       — Number of epochs to run
     :seed         — RNG seed
     :params       — Static parameters for the update-fn
     :summary-fn   — (fn [history params]) -> result-summary-map
     :persist-trace? — If true, save trial traces to results/ directory"
  [{:keys [label initial-state update-fn epochs seed params summary-fn persist-trace?] :as scenario}]
  (println (format "📋 %s" label))
  (let [d-rng (rng/make-rng seed)
        res-dir (format "results/%s" (java.time.LocalDateTime/now))]
    (loop [epoch 1
           state initial-state
           history []]
      (if (> epoch epochs)
        (let [summary (summary-fn history params)]
          (println (format "   Final status: %s" (:status summary "COMPLETE")))
          (when persist-trace?
            (results/persist-trace! res-dir label history))
          (assoc summary :history history :label label))
        (let [new-state (update-fn epoch state params d-rng)]
          (recur (inc epoch)
                 new-state
                 (conj history (assoc new-state :epoch epoch))))))))

(defn run-sweep
  "Run a sweep of multiple scenarios."
  [sweep-label scenarios common-params]
  (println (format "\n📊 %s" sweep-label))
  (let [results (mapv #(run-epoch-simulation (merge % {:params (merge common-params (:params %))})) 
                      scenarios)]
    results))
