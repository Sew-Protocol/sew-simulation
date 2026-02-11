(ns resolver-sim.core
  "CLI entry point."
  (:require [resolver-sim.io.params :as params]
            [resolver-sim.model.dispute :as dispute]
            [resolver-sim.model.rng :as rng]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(def cli-options
  [["-p" "--params PATH" "Path to params.edn file"
    :default "params/baseline.edn"]
   ["-h" "--help" "Show this help"]])

(defn usage [options-summary]
  (->> ["Dispute Resolver Incentive Simulation"
        ""
        "Usage: clojure -M:run [options]"
        ""
        "Options:"
        options-summary]
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

(defn -main [& args]
  (let [{:keys [options exit-message ok?]} (validate-args args)]
    (if exit-message
      (do (println exit-message)
          (System/exit (if ok? 0 1)))
      
      (try
        (println "Loading params from:" (:params options))
        (let [params (params/validate-and-merge (:params options))
              rng (rng/make-rng (:rng-seed params))]
          
          (println "Running baseline scenario...")
          (println (format "  Trials: %d" (:n-trials params)))
          (println (format "  Seed: %d" (:rng-seed params)))
          
          ; Run one trial to test
          (let [result (dispute/resolve-dispute
                        rng 10000 (:resolver-fee-bps params) (:appeal-bond-bps params)
                        (:slash-multiplier params) :honest
                        (:appeal-probability-if-correct params)
                        (:appeal-probability-if-wrong params)
                        (:slashing-detection-probability params))]
            (println "\nSample trial result:")
            (println result))
          
          (println "\n✓ Setup complete. Ready to run simulations."))
        
        (catch Exception e
          (println "Error:" (.getMessage e))
          (System/exit 1))))))
