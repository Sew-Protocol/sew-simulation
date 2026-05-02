(ns notebooks.telemetry
  (:require [nextjournal.clerk :as clerk]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; # Simulation Telemetry Explorer
;; Browse and analyze simulation results and event traces.

(defn load-run-results [run-dir]
  (let [summary-file (io/file run-dir "summary.edn")]
    (when (.exists summary-file)
      (edn/read-string (slurp summary-file)))))

(defn- get-run-dirs []
  (let [base (io/file "results")]
    (when (.exists base)
      (sort (filter #(.isDirectory %) (.listFiles base))))))

{::clerk/viewer :table}
(clerk/table
 (for [dir (get-run-dirs)]
   {:run (.getName dir)
    :timestamp (str (.getName dir))
    :summary (load-run-results dir)}))

;; ## Trace Inspector
;; Select a simulation run to inspect individual traces.

(defn load-trace [run-dir trace-id]
  ;; Assuming traces are persisted as EDN files in the run directory.
  (let [file (io/file run-dir (str trace-id ".trace.edn"))]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn render-event [event]
  (clerk/html
   [:div.border-b.p-2
    [:span.font-mono (format "%03d" (:seq event))]
    [:span.ml-2.font-bold (:agent event)]
    [:span.ml-2.text-blue-600 (:action event)]
    [:span.ml-2.text-gray-500 (str (:params event))]]))

(defn render-trace [trace]
  (clerk/html
   [:div.bg-gray-50.p-4.rounded
    [:h3 "Full Trace Timeline"]
    (for [e trace]
      (render-event e))]))
