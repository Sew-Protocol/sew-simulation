(ns resolver-sim.sim.sweep
  "Parameter sweep runner for sensitivity analysis."
  (:require [resolver-sim.sim.batch :as batch]
            [resolver-sim.model.rng :as rng]))

(defn run-parameter-sweep
  "Run multiple scenarios with different parameters and aggregate results.
   
   sweep-params should be a map of parameter keys to lists of values.
   Returns a list of batch results for each combination."
  [params base-rng-seed sweep-params]
  
  (let [; Get all sweep dimensions
        sweep-keys (keys sweep-params)
        sweep-values (vals sweep-params)
        
        ; Create all combinations (simple case: one parameter varied)
        combos (if (= 1 (count sweep-keys))
                 (for [v (first sweep-values)]
                   [(first sweep-keys) v])
                 (throw (Exception. "Multi-dimensional sweeps not yet implemented")))]
    
    (for [[param-key param-val] combos]
      (let [trial-params (assoc params param-key param-val)
            rng (rng/make-rng base-rng-seed)
            result (batch/run-batch rng (:n-trials params) trial-params)]
        (assoc result param-key param-val)))))

(defn run-strategy-sweep
  "Convenient sweep across resolver strategies."
  [params base-rng-seed]
  (run-parameter-sweep params base-rng-seed
                       {:strategy [:honest :lazy :malicious :collusive]}))
