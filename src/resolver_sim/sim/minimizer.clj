(ns resolver-sim.sim.minimizer
  "Trace minimisation engine for SEW simulations.
   Reduces a failing event sequence to its 1-minimal subset that still
   triggers a target invariant violation."
  (:require [resolver-sim.contract-model.replay :as replay]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]))

(defn- re-index-events
  "Ensure event sequence numbers are contiguous from 0."
  [events]
  (mapv (fn [i e] (assoc e :seq i))
        (range)
        (sort-by :time events)))

(defn fails?
  "True if the scenario fails with the target-invariant violation."
  ([scenario] (fails? scenario nil))
  ([scenario target-invariant]
   (let [res (replay/replay-scenario scenario)
         last-entry (last (:trace res))
         violations (get-in last-entry [:violations])]
     (println "Replay outcome:" (:outcome res) "halt-reason:" (:halt-reason res) "violations:" (keys violations))
     (and (= :fail (:outcome res))
          (= :invariant-violation (:halt-reason res))
          (if target-invariant
            (contains? violations target-invariant)
            true)))))

(defn- try-remove
  "Attempt to remove event at index i and check if it still fails.
   Events with :minimize/pin true are never removed."
  [scenario i target-invariant]
  (let [events (:events scenario)
        event  (nth events i)]
    (if (:minimize/pin event)
      scenario
      (let [new-events   (vec (concat (subvec events 0 i) (subvec events (inc i))))
            new-scenario (assoc scenario :events (re-index-events new-events))]
        (if (fails? new-scenario target-invariant)
          new-scenario
          scenario)))))

(defn- prune-unreferenced-agents
  "Remove agents from :agents that are not referenced by any event in the
   minimized sequence.  Keeps the trace self-consistent after event removal."
  [scenario]
  (let [referenced (into #{} (keep :agent (:events scenario)))]
    (update scenario :agents (fn [agents]
                                (filterv #(contains? referenced (:id %)) agents)))))

(defn minimize
  "Greedily minimize the scenario trace, then prune unreferenced agents.

   Events annotated with :minimize/pin true are never removed, preserving
   causal pairs (e.g. same-block resolution + escalation) that are required
   to trigger a specific invariant violation."
  [scenario target-invariant]
  (println "Starting minimization of" (count (:events scenario)) "events...")
  (let [minimized
        (loop [current scenario
               i       (dec (count (:events scenario)))]
          (if (neg? i)
            current
            (recur (try-remove current i target-invariant) (dec i))))]
    (println "Minimization complete:" (count (:events minimized)) "events remain.")
    (prune-unreferenced-agents minimized)))

;; ---------------------------------------------------------------------------
;; CLI Entry Point
;; ---------------------------------------------------------------------------

(def cli-options
  [["-i" "--input PATH" "Path to input trace.json"]
   ["-v" "--violation KEY" "Target invariant (e.g., :solvency)"
    :parse-fn keyword]
   ["-o" "--output PATH" "Path to save minimized trace.json"]
   ["-h" "--help"]])

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) (println summary)
      errors (println errors)
      (not (:input options)) (println "Error: --input is required")
      :else
      (let [input-path (:input options)
            scenario (with-open [r (io/reader input-path)]
                       (json/read r :key-fn keyword))
            target (:violation options)
            minimized (minimize scenario target)]
        (if-let [output-path (:output options)]
          (with-open [w (io/writer output-path)]
            (json/write minimized w :key-fn name :value-fn (fn [k v] (if (keyword? v) (name v) v))))
          (println (json/write-str minimized :key-fn name)))))))
