(ns resolver-sim.core
  "CLI entry point."
  (:require [resolver-sim.io.params :as params]
            [resolver-sim.io.results :as results]
            [resolver-sim.sim.batch :as batch]
            [resolver-sim.sim.sweep :as sweep]
            [resolver-sim.model.rng :as rng]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(def cli-options
  [["-p" "--params PATH" "Path to params.edn file"
    :default "params/baseline.edn"]
   ["-o" "--output DIR" "Output directory for results"
    :default "results"]
   ["-s" "--sweep" "Run strategy sweep (honest, lazy, malicious, collusive)"]
   ["-h" "--help" "Show this help"]])

(defn usage [options-summary]
  (->> ["Dispute Resolver Incentive Simulation"
        ""
        "Usage: clojure -M:run [options]"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  clojure -M:run -p params/baseline.edn"
        "  clojure -M:run -p params/cartel.edn -s  # sweep strategies"]
       (clojure.string/join "\n")))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join "\n" errors)))

(defn validate-args [args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) {:exit-message (usage summary) :ok? true}
      errors {:exit-message (error-msg errors)}
      :else {:options options})))

(defn run-simulation [params output-dir]
  (let [scenario-id (:scenario-id params "unnamed")
        run-dir (results/create-run-directory output-dir scenario-id)
        rng (rng/make-rng (:rng-seed params))
        
        _ (println (format "\n📊 Running simulation: %s" scenario-id))
        _ (println (format "   Seed: %d" (:rng-seed params)))
        _ (println (format "   Trials: %d" (:n-trials params)))
        
        ; Run batch
        batch-result (batch/run-batch rng (:n-trials params) params)
        
        _ (println "\n✓ Simulation complete. Results:")
        _ (println (format "   Honest avg profit: %.2f" (:honest-mean batch-result)))
        _ (println (format "   Malice avg profit: %.2f" (:malice-mean batch-result)))
        _ (println (format "   Dominance ratio: %.2f" (:dominance-ratio batch-result)))]
    
    ; Write outputs
    (results/write-edn (format "%s/summary.edn" run-dir) batch-result)
    (results/write-csv (format "%s/results.csv" run-dir) batch-result)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                               {:scenario-id scenario-id
                                :params params
                                :batch-result batch-result})
    
    (println (format "\n💾 Results saved to: %s" run-dir))
    batch-result))

(defn run-sweep [params output-dir]
  (let [scenario-id (:scenario-id params "unnamed")
        run-dir (results/create-run-directory output-dir (str scenario-id "-sweep"))
        
        _ (println (format "\n📊 Running strategy sweep: %s" scenario-id))
        _ (println (format "   Seed: %d" (:rng-seed params)))
        _ (println (format "   Trials per strategy: %d" (:n-trials params)))
        
        ; Run all strategies
        results-list (sweep/run-strategy-sweep params (:rng-seed params))]
    
    (println "\n✓ Sweep complete. Results by strategy:")
    (doseq [result results-list]
      (println (format "   %s: honest=%.2f, malice=%.2f, ratio=%.2f"
                      (:strategy result) (:honest-mean result) (:malice-mean result)
                      (:dominance-ratio result))))
    
    ; Write outputs
    (results/write-edn (format "%s/summary.edn" run-dir) results-list)
    (results/write-csv (format "%s/results.csv" run-dir) results-list)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                               {:scenario-id scenario-id
                                :sweep-type :strategy
                                :params params
                                :results results-list})
    
    (println (format "\n💾 Sweep results saved to: %s" run-dir))
    results-list))

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (if exit-message
      (do (println exit-message)
          (System/exit (if ok? 0 1)))
      
      (try
        (println "Loading params from:" (:params options))
        (let [params (params/validate-and-merge (:params options))]
          (if (:sweep options)
            (run-sweep params (:output options))
            (run-simulation params (:output options)))
          (System/exit 0))
        
        (catch Exception e
          (println "Error:" (.getMessage e))
          (.printStackTrace e)
          (System/exit 1))))))
