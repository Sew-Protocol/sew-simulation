(ns resolver-sim.contract-model.invariant-runner
  "In-process runner for the S01–S23 deterministic invariant scenarios.

   Runs every scenario in invariant-scenarios/all-scenarios against
   replay/replay-scenario, reports pass/fail per entry, and returns a
   summary map suitable for CLI consumption.

   S12 is a paired scenario (vector of two maps); it passes only when
   both sub-scenarios pass."
  (:require [resolver-sim.contract-model.replay            :as replay]
            [resolver-sim.contract-model.invariant-scenarios :as sc]))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- run-one
  "Run a single scenario map.  Returns {:pass? bool :result result}."
  [scenario]
  (let [result (replay/replay-scenario scenario)
        pass?  (= :pass (:outcome result))]
    {:pass? pass? :result result}))

(defn- run-entry
  "Run a registry entry (single scenario or [s12a s12b] pair).
   Returns {:pass? bool :steps int :reverts int :violations int :details [...]}."
  [entry]
  (if (map? entry)
    (let [{:keys [pass? result]} (run-one entry)]
      {:pass?      pass?
       :steps      (:events-processed result 0)
       :reverts    (get-in result [:metrics :reverts] 0)
       :violations (get-in result [:metrics :invariant-violations] 0)
       :details    [result]})
    ;; Paired scenario: both must pass
    (let [results (mapv run-one entry)
          all-ok  (every? :pass? results)]
      {:pass?      all-ok
       :steps      (reduce + (map #(get-in % [:result :events-processed] 0) results))
       :reverts    (reduce + (map #(get-in % [:result :metrics :reverts] 0) results))
       :violations (reduce + (map #(get-in % [:result :metrics :invariant-violations] 0) results))
       :details    (mapv :result results)})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn run-all
  "Run all S01–S23 invariant scenarios.  Returns a summary map:
     {:passed int :total int :elapsed-ms long :results [{entry-result}]}
   where each entry-result adds :name and the keys from run-entry."
  []
  (let [t0      (System/currentTimeMillis)
        results (mapv (fn [[name entry]]
                        (merge {:name name} (run-entry entry)))
                      sc/all-scenarios)
        elapsed (- (System/currentTimeMillis) t0)]
    {:passed    (count (filter :pass? results))
     :total     (count results)
     :elapsed-ms elapsed
     :results   results}))

(defn print-report
  "Print a human-readable report from run-all output.  Returns exit code (0/1)."
  [{:keys [passed total elapsed-ms results]}]
  (let [w 72]
    (println (apply str (repeat w "═")))
    (println "  SEW Invariant Suite — Deterministic Scenarios (Clojure in-process)")
    (println (apply str (repeat w "═")))
    (println (format "  %-47s %5s  %7s  %s" "Scenario" "steps" "reverts" "status"))
    (println (str "  " (apply str (repeat (- w 2) "─"))))
    (doseq [{:keys [name pass? steps reverts violations]} results]
      (let [status (if pass? "✓ PASS" "✗ FAIL")
            extra  (when (pos? violations) (format "  violations=%d" violations))]
        (println (format "  %s  %-45s %5d  %7d%s"
                         status name steps reverts (or extra "")))))
    (println (apply str (repeat w "─")))
    (println (format "  %d/%d passed  (%.1f s)" passed total (/ elapsed-ms 1000.0)))
    (println (apply str (repeat w "═")))
    (if (= passed total) 0 1)))

(defn run-and-report
  "Convenience: run-all then print-report.  Returns exit code."
  []
  (print-report (run-all)))
