(ns resolver-sim.sim.reporter
  "Result reporting for CDRS v1.1 suite execution."
  (:require [clojure.string :as str]))

(defn print-expectations [expectations]
  (if (nil? expectations)
    (println "    - Expectations: Not evaluated")
    (if (:ok? expectations)
      (println "    ✓ Expectations: Pass")
      (do
        (println "    ✗ Expectations: Fail")
        (doseq [v (:violations expectations)]
          (case (:type v)
            :terminal-mismatch (println "      - Terminal mismatch at" (:path v) ": expected" (:expected v) "got" (:actual v))
            :metric-violation  (println "      - Metric violation:" (:name v) (:op v) (:expected v) "got" (:actual v))
            :invariant-failed  (println "      - Invariant failed:" (:invariant v) (when (:note v) (str "(" (:note v) ")")))
            (println "      - Unknown violation:" v)))))))

(defn- theory-label [status]
  (case status
    :not-evaluated "Not evaluated"
    :not-falsified "Claim not falsified"
    :falsified     "Claim falsified"
    :inconclusive  "Inconclusive"
    "Not evaluated"))

(defn- theory-expected? [status purpose]
  ;; A falsified claim is the expected outcome for theory-falsification scenarios
  (case status
    :not-evaluated true
    :not-falsified true
    :falsified     (= (keyword (or purpose "")) :theory-falsification)
    :inconclusive  false
    true))

(defn print-theory [theory purpose]
  (let [status (or (:status theory) :not-evaluated)
        label  (theory-label status)
        ok?    (theory-expected? status purpose)
        marker (case status
                 :inconclusive  "?"
                 :not-evaluated "-"
                 (if ok? "✓" "✗"))]
    (println (str "    " marker " Theory: " label))
    (when (and (= status :falsified) (seq (:evidence theory)))
      (doseq [e (:evidence theory)]
        (println "      - Evidence:" (:metric e) (:op e) (:value e) "→ actual" (:actual e))))
    (when (= status :inconclusive)
      (println "      - No tracked metrics matched the falsifies-if conditions"))))

(defn print-suite-results [suite-result]
  (println (str "\nSuite: " (:suite-id suite-result)))
  (println (str "Overall Status: " (if (:ok? suite-result) "PASS" "FAIL")))
  (println "----------------------------------------------------------------------")
  (doseq [r (:results suite-result)]
    (println (str "[" (:outcome r) "] " (:trace-id r)))
    (print-expectations (:expectations r))
    (print-theory (:theory r) (:purpose r))))

;; ---------------------------------------------------------------------------
;; Coverage reporting
;; ---------------------------------------------------------------------------

(defn- purpose-label [p]
  (case p
    :regression              "Regression"
    :adversarial-robustness  "Adversarial Robustness"
    :theory-falsification    "Theory Falsification"
    :unclassified            "Unclassified (v1.0)"
    (if p (name p) "Unclassified (v1.0)")))

(defn print-coverage
  "Print a human-readable coverage report from a map returned by
   resolver-sim.scenario.coverage/coverage-report."
  [report]
  (let [total     (:total report 0)
        versions  (:schema-versions report {})
        by-purpose (:by-purpose report {})
        tag-freq  (:threat-tag-freq report {})
        uncl      (:unclassified-count report 0)
        v11-count (get versions "1.1" 0)]
    (println "\n╔══════════════════════════════════════════════════════════════════╗")
    (println "║  Scenario Coverage Report                                        ║")
    (println "╚══════════════════════════════════════════════════════════════════╝")
    (println (str "\n  Scanned: " (:scanned-dir report)))
    (println (str "  Total scenarios: " total))
    (println)

    ;; Schema version breakdown
    (println "  Schema versions:")
    (doseq [[v cnt] (sort-by key versions)]
      (let [bar (str/join "" (repeat cnt "█"))]
        (println (str "    v" v "  " (format "%3d" cnt) "  " bar))))
    (println)

    ;; By purpose
    (println "  By purpose:")
    (let [purpose-order [:regression :adversarial-robustness :theory-falsification :unclassified]
          all-purposes  (distinct (concat purpose-order (keys by-purpose)))]
      (doseq [p all-purposes
              :let [ids (get by-purpose p [])]
              :when (seq ids)]
        (println (str "    " (format "%-28s" (purpose-label p)) (count ids) " scenario(s)"))
        (doseq [id ids]
          (println (str "      · " (if (keyword? id) (name id) id))))))
    (println)

    ;; Threat tags
    (if (seq tag-freq)
      (do
        (println "  Threat tags (by frequency):")
        (doseq [[tag cnt] (sort-by (comp - val) tag-freq)]
          (println (str "    " (format "%-32s" (name tag)) cnt " scenario(s)"))))
      (println "  Threat tags: none tagged"))
    (println)

    ;; Summary
    (println (str "  v1.1 enriched:      " v11-count " / " total " scenarios"))
    (println (str "  Unclassified (v1.0): " uncl " scenarios without :purpose or :threat-tags"))
    (when (pos? uncl)
      (println (str "  → " uncl " scenarios could be enriched with :id, :title, :purpose, :threat-tags")))
    (println)))
