(ns resolver-sim.io.results
  "Write results to various formats (EDN, CSV, JSON)."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.time Instant]
           [java.io File]))

(defn ensure-results-dir
  "Ensure results directory exists."
  [dir-path]
  (let [dir (io/file dir-path)]
    (.mkdirs dir)
    dir-path))

(defn write-edn
  "Write result to EDN file."
  [filepath data]
  (io/make-parents filepath)
  (spit filepath (prn-str data)))

(defn write-csv
  "Write batch results to CSV for easy plotting.
   Phase B: Includes escalation metrics."
  [filepath results]
  (io/make-parents filepath)
  (with-open [writer (io/writer filepath)]
    ; Header with Phase B escalation metrics
    (.write writer "strategy,n_trials,honest_mean,honest_std,honest_min,honest_max,honest_p50,malice_mean,malice_std,malice_min,malice_max,malice_p50,dominance_ratio,appeal_rate,escalation_rate,l2_detection_rate\n")
    
    ; Rows
    (doseq [result (if (sequential? results) results [results])]
      (let [{:keys [strategy n-trials honest-mean honest-std honest-min honest-max honest-p50
                    malice-mean malice-std malice-min malice-max malice-p50 dominance-ratio
                    appeal-rate escalation-rate l2-detection-rate]} result]
        (.write writer (format "%s,%d,%.2f,%.2f,%d,%d,%.2f,%.2f,%.2f,%d,%d,%.2f,%.2f,%.4f,%.4f,%.4f\n"
                              strategy n-trials honest-mean honest-std honest-min honest-max honest-p50
                              malice-mean malice-std malice-min malice-max malice-p50 dominance-ratio
                              (or appeal-rate 0) (or escalation-rate 0) (or l2-detection-rate 0)))))))

(defn write-run-metadata
  "Write run metadata (params, git info, timestamp)."
  [filepath metadata]
  (io/make-parents filepath)
  (spit filepath (prn-str (assoc metadata :timestamp (str (Instant/now))))))

(defn create-run-directory
  "Create timestamped results directory."
  [base-path scenario-id]
  (let [timestamp (-> (java.text.SimpleDateFormat. "yyyy-MM-dd_HH-mm-ss")
                      (.format (java.util.Date.)))
        dir-path (format "%s/%s_%s" base-path timestamp scenario-id)]
    (ensure-results-dir dir-path)
    dir-path))
