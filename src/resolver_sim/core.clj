(ns resolver-sim.core
  "CLI entry point."
  (:require [resolver-sim.io.params :as params]
            [resolver-sim.io.results :as results]
            [resolver-sim.sim.batch :as batch]
            [resolver-sim.sim.sweep :as sweep]
            [resolver-sim.sim.multi-epoch :as multi-epoch]
            [resolver-sim.sim.waterfall :as waterfall]
            [resolver-sim.sim.governance-impact :as gov-impact]
            [resolver-sim.sim.phase-o :as phase-o]
            [resolver-sim.sim.phase-p-lite :as phase-p-lite]
            [resolver-sim.model.rng :as rng]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(def cli-options
  [["-p" "--params PATH" "Path to params.edn file"
    :default "params/baseline.edn"]
   ["-o" "--output DIR" "Output directory for results"
    :default "results"]
   ["-s" "--sweep" "Run strategy sweep (honest, lazy, malicious, collusive)"]
   ["-m" "--multi-epoch" "Run Phase J multi-epoch simulation (10+ epochs)"]
   ["-l" "--waterfall" "Run Phase L waterfall stress testing"]
   ["-g" "--governance-impact" "Run Phase M governance response time impact analysis"]
   ["-O" "--market-exit" "Run Phase O market exit cascade modeling"]
   ["-P" "--phase-p-lite" "Run Phase P Lite: falsification test (difficulty, evidence, herding)"]
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
        
        ; Check if this is a custom sweep (2D+) or strategy sweep
        custom-sweep-params (:sweep-params params)
        
        _ (if custom-sweep-params
            (println (format "\n📊 Running parameter sweep: %s" scenario-id))
            (println (format "\n📊 Running strategy sweep: %s" scenario-id)))
        _ (println (format "   Seed: %d" (:rng-seed params)))
        _ (println (format "   Trials per combo: %d" (:n-trials params)))
        
        ; Run appropriate sweep
        results-list (if custom-sweep-params
                      (sweep/run-parameter-sweep params (:rng-seed params) custom-sweep-params)
                      (sweep/run-strategy-sweep params (:rng-seed params)))]
    
    (println (format "\n✓ Sweep complete. %d results:" (count results-list)))
    (if custom-sweep-params
      ; For multi-D sweeps, show parameter values
      (doseq [result results-list]
        (let [param-str (->> custom-sweep-params
                           keys
                           (map #(format "%s=%s" (name %) (get result %)))
                           (clojure.string/join " "))]
          (println (format "   %s: honest=%.2f, malice=%.2f, ratio=%.2f"
                          param-str (:honest-mean result) (:malice-mean result)
                          (:dominance-ratio result)))))
      ; For strategy sweeps, show strategy
      (doseq [result results-list]
        (println (format "   %s: honest=%.2f, malice=%.2f, ratio=%.2f"
                        (:strategy result) (:honest-mean result) (:malice-mean result)
                        (:dominance-ratio result)))))
    
    ; Write outputs
    (results/write-edn (format "%s/summary.edn" run-dir) results-list)
    (results/write-csv (format "%s/results.csv" run-dir) results-list)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                               {:scenario-id scenario-id
                                :sweep-type (if custom-sweep-params :parameter :strategy)
                                :params params
                                :results results-list})
    
    (println (format "\n💾 Sweep results saved to: %s" run-dir))
    results-list))

(defn run-ring-simulation [params output-dir]
  (let [scenario-id (:scenario-id params "unnamed")
        run-dir (results/create-run-directory output-dir (str scenario-id "-ring"))
        rng (rng/make-rng (:rng-seed params))
        ring-spec (:ring-spec params)
        
        _ (when-not ring-spec (throw (Exception. "ring-spec not found in params")))
        _ (println (format "\n📊 Running ring simulation: %s" scenario-id))
        _ (println (format "   Seed: %d" (:rng-seed params)))
        _ (println (format "   Trials: %d" (:n-trials params)))
        _ (let [senior (:senior ring-spec)
                juniors (:juniors ring-spec)]
            (println (format "   Ring: 1 senior ($%d bond) + %d juniors"
                            (:bond senior) (count juniors))))
        
        ; Run ring batch
        ring-result (batch/run-ring-batch rng (:n-trials params) params ring-spec)
        
        _ (println "\n✓ Ring simulation complete. Results:")
        _ (println (format "   Total ring profit: %.2f" (:ring-total-profit ring-result)))
        _ (println (format "   Avg profit/dispute: %.2f" (:ring-avg-profit-per-dispute ring-result)))
        _ (println (format "   Catch rate: %.4f" (:ring-catch-rate ring-result)))
        _ (println (format "   Ring viable: %s" (:ring-viable? ring-result)))
        _ (println (format "   Senior exhausted: %s" (:ring-senior-exhausted? ring-result)))]
    
    ; Write outputs (skip CSV for now since format is different for ring vs strategy sweep)
    (results/write-edn (format "%s/summary.edn" run-dir) ring-result)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                               {:scenario-id scenario-id
                                :type :ring
                                :params params
                                :ring-result ring-result})
    
    (println (format "\n💾 Ring results saved to: %s" run-dir))
    ring-result))

(defn run-multi-epoch-simulation [params output-dir]
  (let [scenario-id (:scenario-id params "unnamed")
        run-dir (results/create-run-directory output-dir (str scenario-id "-multi-epoch"))
        rng (rng/make-rng (:rng-seed params))
        n-epochs (get params :n-epochs 10)
        n-trials-per-epoch (get params :n-trials-per-epoch 500)
        
        ; Run Phase J multi-epoch simulation
        result (multi-epoch/run-multi-epoch rng n-epochs n-trials-per-epoch params)]
    
    ; Write outputs
    (results/write-edn (format "%s/summary.edn" run-dir) result)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                               {:scenario-id scenario-id
                                :type :multi-epoch
                                :n-epochs n-epochs
                                :n-trials-per-epoch n-trials-per-epoch
                                :params params
                                :result result})
    
    (println (format "\n💾 Multi-epoch results saved to: %s" run-dir))
    result))

(defn run-waterfall-simulation [params output-dir]
  (let [scenario-id (:scenario-id params "unnamed")
        scenario-name (:scenario-name params scenario-id)
        run-dir (results/create-run-directory output-dir (str scenario-name "-waterfall"))
        
        ; Initialize pools
        pool (waterfall/initialize-waterfall-pool params)
        
        ; Simulate waterfall stress
        _ (println (format "\n🌊 Running waterfall stress test: %s" scenario-name))
        _ (println (format "   Seniors: %d | Juniors: %d" 
                          (:n-seniors params 5)
                          (* (:n-seniors params 5) (:n-juniors-per-senior params 10))))
        _ (println (format "   Fraud rate: %.1f%% | Coverage multiplier: %.1f×"
                          (* 100 (:fraud-rate params 0.10))
                          (:coverage-multiplier params 3.0)))
        
        ; Generate slash events based on fraud rate
        n-trials (get params :n-trials 1000)
        n-fraud-events (int (* n-trials (:fraud-rate params 0.10)))
        
        ; Create synthetic slash events (simplified)
        slash-events (mapv (fn [i]
                            (let [n-juniors (* (:n-seniors params 5) (:n-juniors-per-senior params 10))
                                  junior-idx (mod i n-juniors)
                                  senior-idx (int (/ junior-idx (:n-juniors-per-senior params 10)))]
                              {:resolver-id (str "j" senior-idx "_" (mod junior-idx (:n-juniors-per-senior params 10)))
                               :senior-id (str "s" senior-idx)
                               :slash-amount (waterfall/calculate-slash-amount 
                                            (:junior-bond-amount params 500)
                                            (:fraud-slash-bps params 50))
                               :reason :fraud
                               :epoch (int (/ i 10))}))
                          (range n-fraud-events))
        
        ; Process all slash events
        result (reduce (fn [state event]
                        (waterfall/process-slash-event event (:resolvers state) (:seniors state)))
                      {:resolvers (:juniors pool)
                       :seniors (:seniors pool)
                       :events []}
                      slash-events)
        
        ; Aggregate metrics
        metrics (waterfall/aggregate-waterfall-metrics 
                (:resolvers result)
                (:seniors result)
                (mapv :event-result (map (fn [e] (waterfall/process-slash-event e (:juniors pool) (:seniors pool)))
                                        slash-events)))
        
        summary {:scenario-id scenario-id
                :scenario-name scenario-name
                :type :waterfall
                :params (select-keys params [:fraud-rate :coverage-multiplier :utilization-factor
                                           :n-seniors :n-juniors-per-senior
                                           :senior-bond-amount :junior-bond-amount])
                :results metrics}]
    
    ; Print key findings
    (println (format "   Juniors exhausted: %.1f%%" (:juniors-exhausted-pct metrics)))
    (println (format "   Coverage used: %.1f%%" (:seniors-coverage-used-avg-pct metrics)))
    (println (format "   Adequacy score: %.1f%%" (:coverage-adequacy-score metrics)))
    
    ; Write outputs
    (results/write-edn (format "%s/summary.edn" run-dir) summary)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                               {:scenario-id scenario-id
                                :type :waterfall
                                :params params
                                :results metrics})
    
    (println (format "\n💾 Waterfall results saved to: %s" run-dir))
    summary))

(defn run-governance-impact-simulation [params output-dir]
  (let [scenario-id (:scenario-id params "unnamed")
        scenario-name (:scenario-name params scenario-id)
        run-dir (results/create-run-directory output-dir (str scenario-name "-governance-impact"))
        
        rng (rng/make-rng (:seed params 42))
        n-epochs (get params :n-epochs 10)
        n-trials (get params :n-trials-per-epoch 500)
        
        _ (println (format "\n🏛️  Running governance impact test: %s" scenario-name))
        _ (println (format "   Governance response: %d days" (:governance-response-days params 3)))
        
        result (gov-impact/run-multi-epoch-governance-impact rng n-epochs n-trials params)
        
        summary {:scenario-id scenario-id
                 :scenario-name scenario-name
                 :type :governance-impact
                 :governance-response-days (:governance-response-days params 3)
                 :params (select-keys params [:governance-response-days :n-epochs :n-trials-per-epoch 
                                            :slashing-detection-probability :strategy-mix])
                 :results (select-keys result [:epoch-results :governance-metrics :aggregated-stats])}]
    
    ; Print key findings
    (println (format "   Final honest resolvers: %d" (get-in result [:aggregated-stats :honest-final-count])))
    (println (format "   Slashes executed: %d" (get-in result [:governance-metrics :total-pending-slashes-resolved])))
    (println (format "   Still pending: %d" (get-in result [:governance-metrics :pending-slashes-still-waiting])))
    (println (format "   Frozen resolvers: %d" (get-in result [:governance-metrics :frozen-resolvers])))
    
    ; Write outputs
    (results/write-edn (format "%s/summary.edn" run-dir) summary)
    (results/write-run-metadata (format "%s/metadata.edn" run-dir)
                               {:scenario-id scenario-id
                                :type :governance-impact
                                :params params
                                :results result})
    
    (println (format "\n💾 Governance impact results saved to: %s" run-dir))
    summary))

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (if exit-message
      (do (println exit-message)
          (System/exit (if ok? 0 1)))
      
      (try
        (println "Loading params from:" (:params options))
        (let [params (params/validate-and-merge (:params options))]
          (cond
            (:ring-spec params)
            (run-ring-simulation params (:output options))
            
             
             (:phase-p-lite options)
             (do (println "\n📊 Running Phase P Lite Falsification Test")
                (phase-p-lite/run-phase-p-lite params))
            (:market-exit options)
            (do (println "\n🔄 Running Phase O Market Exit Cascade")
               (phase-o/run-phase-o-complete params))
            
            (:governance-impact options)
            (run-governance-impact-simulation params (:output options))
            
            (:waterfall options)
            (run-waterfall-simulation params (:output options))
            
            (:multi-epoch options)
            (run-multi-epoch-simulation params (:output options))
            
            (:sweep options)
            (run-sweep params (:output options))
            
            :else
            (run-simulation params (:output options)))
          (System/exit 0))
        
        (catch Exception e
          (println "Error:" (.getMessage e))
          (.printStackTrace e)
          (System/exit 1))))))
