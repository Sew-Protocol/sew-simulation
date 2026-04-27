(ns resolver-sim.sim.fixtures
  "Recursive fixture loader and composer for deterministic simulation suites.

   Handles loading EDN/JSON files from fixtures/ based on keyword namespaces.
   Implements canonical action mapping and golden report generation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [clojure.data.json :as json]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.types :as t]
            [resolver-sim.contract-model.invariants :as inv]
            [resolver-sim.contract-model.diff :as diff]
            [resolver-sim.canonical.actions :as canon]
            [resolver-sim.sim.minimizer :as minimizer]))

;; ---------------------------------------------------------------------------
;; Canonical Action Mapping (delegated to resolver-sim.canonical.actions)
;; ---------------------------------------------------------------------------

(def impl->canonical
  "Reverse mapping for trace reporting."
  (into {} (map (fn [[k v]] [v k]) resolver-sim.canonical.actions/action-map)))

;; ---------------------------------------------------------------------------
;; Fixture Loading
;; ---------------------------------------------------------------------------

(defn- fixture-key->path
  [k]
  (let [ns (namespace k)
        nm (name k)
        ext (if (= ns "traces") ".trace.json" ".edn")]
    (if ns
      (str "fixtures/" ns "/" nm ext)
      (str "fixtures/" nm ext))))

(defn load-fixture
  [k]
  (let [path (fixture-key->path k)
        resource (io/file path)]
    (if (.exists resource)
      (with-open [r (io/reader resource)]
        (if (.endsWith path ".json")
          (json/read r :key-fn keyword)
          (edn/read (java.io.PushbackReader. r))))
      (throw (ex-info "Fixture not found" {:key k :path path})))))

(defn- fixture-ref? [x]
  (and (keyword? x) (namespace x)
       (contains? #{"protocol" "states" "actors" "authority" "tokens" "thresholds" "suites" "traces"} (namespace x))))

(defn compose-suite
  ([x] (compose-suite x #{}))
  ([x seen]
   (cond
     (fixture-ref? x)
     (if (contains? seen x)
       (throw (ex-info "Circular fixture reference" {:key x :seen seen}))
       (compose-suite (load-fixture x) (conj seen x)))

     (map? x)
     (reduce-kv (fn [m k v]
                  (assoc m k (if (contains? #{:suite/id :protocol/id :state/id :authority/id :threshold/id :actor/id :token/id} k)
                               v
                               (compose-suite v seen))))
                {} x)

     (vector? x)
     (mapv #(compose-suite % seen) x)

     :else x)))

;; ---------------------------------------------------------------------------
;; Validation & Reports
;; ---------------------------------------------------------------------------

(defn- validate-thresholds
  [result thresholds]
  (let [violations (atom [])]
    (when (and (= :strict (:solvency thresholds))
               (pos? (get-in result [:metrics :invariant-violations] 0)))
      (swap! violations conj {:type :solvency-violation :detail "Strict solvency check failed"}))
    {:ok? (empty? @violations)
     :violations @violations}))

(defn- generate-golden-report
  "Generate a summary report suitable for golden snapshotting."
  [suite-id result]
  (let [last-entry (last (:trace result))
        final-hash (get-in last-entry [:projection-hash])]
    {:suite-id suite-id
     :trace-id (:scenario-id result)
     :final-state-hash final-hash
     :metrics (:metrics result)
     :outcome (:outcome result)}))

(defn run-suite
  [suite-key]
  (let [suite (compose-suite (load-fixture suite-key))
        traces (:traces suite [])
        thresholds (:thresholds suite {})
        proto (:protocol suite)
        state (:state suite)
        authority (:authority suite)
        actors (:actors suite)
        results (mapv (fn [trace]
                        (let [effective-trace (cond-> trace
                                                proto (assoc :protocol-params proto)
                                                state (assoc :initial-block-time (:block-time state 1000))
                                                authority (assoc :authority-params authority)
                                                actors (assoc :agents (vec (concat (:agents trace []) actors))))
                              res (replay/replay-scenario effective-trace)]
                          {:trace-id (:scenario-id trace)
                           :outcome (:outcome res)
                           :metrics (:metrics res)
                           :threshold-validation (validate-thresholds res thresholds)
                           :golden-report (generate-golden-report suite-key res)}))
                      traces)
        all-ok? (every? (fn [r] (and (= :pass (:outcome r))
                                     (:ok? (:threshold-validation r))))
                        results)]
    {:suite-id suite-key
     :ok? all-ok?
     :results results}))

;; ---------------------------------------------------------------------------
;; Trace Minimisation
;; ---------------------------------------------------------------------------

(defn minimise-suite
  "Run the minimizer on all failing traces in a suite, generating minimized
   versions for closer analysis and fixture consolidation.
   
   Returns {:suite-id kw :minimized [{:trace-id :target-invariant :event-count}]}"
  [suite-key target-invariant]
  (let [suite (compose-suite (load-fixture suite-key))
        traces (:traces suite [])
        proto (:protocol suite)
        state (:state suite)
        authority (:authority suite)
        actors (:actors suite)
        results (atom [])]
    (doseq [trace traces]
      (let [effective-trace (cond-> trace
                              proto (assoc :protocol-params proto)
                              state (assoc :initial-block-time (:block-time state 1000))
                              authority (assoc :authority-params authority)
                              actors (assoc :agents (vec (concat (:agents trace []) actors))))
            replay-result (replay/replay-scenario effective-trace)]
        (when (and (= :fail (:outcome replay-result))
                   (= :invariant-violation (:halt-reason replay-result)))
          (let [minimized (minimizer/minimize effective-trace target-invariant)]
            (swap! results conj
                   {:trace-id (:scenario-id effective-trace)
                    :target-invariant target-invariant
                    :event-count (count (:events minimized))
                    :original-event-count (count (:events effective-trace))
                    :reduction (- (count (:events effective-trace)) (count (:events minimized)))
                    :minimized-trace minimized})))))
    
    {:suite-id suite-key
     :target-invariant target-invariant
     :minimized-count (count @results)
     :results @results}))

(defn save-minimized-trace
  "Save a minimized trace to the fixtures/traces/ directory.
   
   Returns {:ok? bool :path str :error kw}"
  [{:keys [trace-id minimized-trace]}]
  (let [path (str "fixtures/traces/" (name trace-id) ".minimized.trace.json")]
    (try
      (with-open [w (io/writer path)]
        (json/write minimized-trace w :key-fn name :value-fn (fn [k v] (if (keyword? v) (name v) v))))
      {:ok? true :path path}
      (catch Exception e
        {:ok? false :error :write-failed :detail (.getMessage e)}))))

