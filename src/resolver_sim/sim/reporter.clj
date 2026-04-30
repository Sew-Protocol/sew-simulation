(ns resolver-sim.sim.reporter
  "Result reporting for CDRS v1.1 suite execution.")

(defn- print-expectations [expectations]
  (when expectations
    (if (:ok? expectations)
      (println "    ✓ Expectations: Pass")
      (do
        (println "    ✗ Expectations: FAIL")
        (doseq [v (:violations expectations)]
          (case (:type v)
            :terminal-mismatch (println "      - Terminal mismatch at" (:path v) ": expected" (:expected v) "got" (:actual v))
            :metric-violation  (println "      - Metric violation:" (:name v) (:op v) (:expected v) "got" (:actual v))
            (println "      - Unknown violation:" v)))))))

(defn- print-theory [theory]
  (when theory
    (if (:falsified? theory)
      (do
        (println "    ✗ Theory: FALSIFIED")
        (doseq [e (:evidence theory)]
          (println "      - Evidence:" (:metric e) (:op e) (:value e) "triggered by actual value" (:actual e))))
      (println "    ✓ Theory: Claim not falsified"))))

(defn print-suite-results [suite-result]
  (println (str "\nSuite: " (:suite-id suite-result)))
  (println (str "Overall Status: " (if (:ok? suite-result) "PASS" "FAIL")))
  (println "----------------------------------------------------------------------")
  (doseq [r (:results suite-result)]
    (println (str "[" (:outcome r) "] " (:trace-id r)))
    (print-expectations (:expectations r))
    (print-theory (:theory r))))
