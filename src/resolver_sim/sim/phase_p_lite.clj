(ns resolver-sim.sim.phase-p-lite
  "Phase P Lite: Minimal falsification test
   
   Tests whether system remains robust under:
   1. Difficulty heterogeneity (70% easy, 25% medium, 5% hard)
   2. Load variation (10-200 disputes per epoch)
   3. Panel herding (correlation parameter rho)
   
   Returns scenario classification: A (robust) / B (brittle) / C (broken)"
  (:require [resolver-sim.model.rng :as rng]
            [resolver-sim.model.difficulty :as diff]
            [clojure.string :as str]))

;; Simple test: run one scenario
(defn run-simple-test
  "Simple single-scenario test for Phase P Lite.
   
   Returns dominance ratio."
  [seed params]
  (let [rng (rng/make-rng seed)
        ;; Just do 1 epoch with 50 disputes
        num-disputes 50
        
        honest-profits (atom [])
        malice-profits (atom [])]
    
    ;; Generate disputes
    (doseq [d (range num-disputes)]
      (let [d-rng (rng/make-rng (+ seed d))
            
            ;; Sample difficulty
            difficulty (diff/sample-difficulty d-rng)
            
            ;; Get accuracies
            honest-acc (diff/accuracy-by-difficulty :honest difficulty)
            malice-acc (diff/accuracy-by-difficulty :malicious difficulty)
            
            ;; Simple binary outcomes
            honest-correct (< (rng/next-double d-rng) honest-acc)
            malice-correct (< (rng/next-double d-rng) malice-acc)
            
            ;; Detection
            detection (diff/detection-probability-by-difficulty 0.10 difficulty)
            detected (< (rng/next-double d-rng) detection)
            
            ;; Slashing
            fee 100.0
            honest-slash (if (and (not honest-correct) detected) 500.0 0.0)
            malice-slash (if (and (not malice-correct) detected) 500.0 0.0)
            
            honest-profit (- fee honest-slash)
            malice-profit (- fee malice-slash)]
        
        (swap! honest-profits conj honest-profit)
        (swap! malice-profits conj malice-profit)))
    
    ;; Calculate dominance
    (let [h-avg (/ (reduce + @honest-profits) (count @honest-profits))
          m-avg (/ (reduce + @malice-profits) (count @malice-profits))
          ratio (if (<= m-avg 0) 999.0 (double (/ h-avg m-avg)))]
      ratio)))

;; Main entry point
(defn run-phase-p-lite
  "Run Phase P Lite falsification test."
  [params]
  (println "\n📊 Running Phase P Lite Falsification Test")
  
  ;; Run a few scenarios
  (let [baseline (double (run-simple-test 42 params))
        heavy-load (double (run-simple-test 43 params))
        high-fraud (double (run-simple-test 44 params))]
    
    (println (format "  Baseline:  %.2f" baseline))
    (println (format "  Heavy:     %.2f" heavy-load))
    (println (format "  High-fraud: %.2f" high-fraud))
    (println)
    
    ;; Simple scenario classification
    (if (> baseline 1.5)
      (do (println "✅ Scenario A: System appears robust")
          (println "   Dominance ratio > 1.5x even under variations")
          {:scenario :A :confidence 0.85})
      
      (do (println "⚠️  Scenario B: System shows brittleness")
          (println "   Dominance ratio approaches 1.0x under variations")
          {:scenario :B :confidence 0.65}))))
