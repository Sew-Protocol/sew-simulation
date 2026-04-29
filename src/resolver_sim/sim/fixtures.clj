(ns resolver-sim.sim.fixtures
  "Recursive fixture loader and composer for deterministic simulation suites.

   Handles loading EDN/JSON files from data/fixtures/ based on keyword namespaces.
   Implements canonical action mapping, golden report generation, and
   trace minimisation integration."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.data.json :as json]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.protocols.sew.diff :as diff]
            [resolver-sim.canonical.actions :as canon]
            [resolver-sim.sim.minimizer :as minimizer]
            [clojure.pprint :as pp]))

;; ---------------------------------------------------------------------------
;; Canonical Action Mapping
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
      (str "data/fixtures/" ns "/" nm ext)
      (str "data/fixtures/" nm ext))))

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
                  (let [ns-str (when (keyword? k) (namespace k))]
                    (when (and ns-str
                               (not (contains? #{nil "suite" "protocol" "state" "authority"
                                                 "threshold" "actor" "token"} ns-str)))
                      (throw (ex-info "Unrecognized fixture namespace keyword"
                                      {:key k :namespace ns-str})))
                    (assoc m k (if (contains? #{:suite/id :protocol/id :state/id :authority/id :threshold/id :actor/id :token/id} k)
                                 v
                                 (compose-suite v seen)))))
                {} x)

     (vector? x)
     (mapv #(compose-suite % seen) x)

     :else x)))

;; ---------------------------------------------------------------------------
;; Validation & Golden Reports
;; ---------------------------------------------------------------------------

(defn- validate-thresholds
  "Check replay metrics against a thresholds map.

   Supported keys:
     :solvency :strict  — fails if any invariant-violations > 0
     :max-resolver-profit-ev N  — fails if resolver-profit-ev exceeds N
                                   (Monte Carlo metric; skipped in replay context)
     :min-detection-rate N  — fails if detection-rate falls below N
                               (Monte Carlo metric; skipped in replay context)"
  [result thresholds]
  (let [violations (atom [])
        metrics    (:metrics result {})]
    (when (and (= :strict (:solvency thresholds))
               (pos? (get metrics :invariant-violations 0)))
      (swap! violations conj {:type :solvency-violation :detail "Strict solvency check failed"}))
    (when-let [max-profit (:max-resolver-profit-ev thresholds)]
      (when-let [profit (get metrics :resolver-profit-ev)]
        (when (> profit max-profit)
          (swap! violations conj {:type :profit-ev-exceeded
                                  :detail (str "resolver-profit-ev " profit " > " max-profit)}))))
    (when-let [min-detection (:min-detection-rate thresholds)]
      (when-let [rate (get metrics :detection-rate)]
        (when (< rate min-detection)
          (swap! violations conj {:type :detection-rate-below-minimum
                                  :detail (str "detection-rate " rate " < " min-detection)}))))
    {:ok? (empty? @violations)
     :violations @violations}))

(defn- generate-golden-report
  [suite-id result]
  (let [last-entry (last (:trace result))
        final-hash (get-in last-entry [:projection-hash])]
    {:suite-id suite-id
     :trace-id (:scenario-id result)
     :final-state-hash final-hash
     :metrics (:metrics result)
     :outcome (:outcome result)}))

(defn- save-golden-report
  [suite-key result]
  (let [path (str "data/fixtures/golden/" (name (:trace-id result)) ".report.edn")]
    (with-open [w (io/writer path)]
      (pp/pprint (:golden-report result) w))))

(defn- compare-golden-report
  [suite-key result]
  (let [path (str "data/fixtures/golden/" (name (:trace-id result)) ".report.edn")
        golden (edn/read-string (slurp path))
        report (:golden-report result)]
    (if (= golden report)
      {:ok? true}
      {:ok? false :expected golden :actual report})))

(defn run-suite
  "Execute a suite fixture: compose, replay traces, validate thresholds, and optionally save or verify golden reports."
  ([suite-key] (run-suite suite-key nil))
  ([suite-key mode]
   (let [suite (compose-suite (load-fixture suite-key))
         traces (:traces suite [])
         thresholds (:thresholds suite {})
         proto (:protocol suite)
         state (:state suite)
         authority (:authority suite)
         actors (:actors suite)
         token (:token suite)
         results (mapv (fn [trace]
                         (let [effective-trace (cond-> trace
                                                 proto (assoc :protocol-params proto)
                                                 state (assoc :initial-block-time (:block-time state 1000))
                                                 authority (assoc :authority-params authority)
                                                 actors (assoc :agents (vec (concat (:agents trace []) actors)))
                                                 token (assoc :token-params token))
                               res (replay/replay-scenario effective-trace)
                               report (generate-golden-report suite-key res)
                               comparison (when (= mode :verify) (compare-golden-report suite-key {:trace-id (:scenario-id trace) :golden-report report}))]
                           (when (= mode :save) (save-golden-report suite-key {:trace-id (:scenario-id trace) :golden-report report}))
                           {:trace-id (:scenario-id trace)
                            :outcome (:outcome res)
                            :metrics (:metrics res)
                            :threshold-validation (validate-thresholds res thresholds)
                            :golden-report report
                            :golden-comparison comparison}))
                       traces)
         all-ok? (every? (fn [r] (and (= :pass (:outcome r))
                                      (:ok? (:threshold-validation r))
                                      (if (= mode :verify) (:ok? (:golden-comparison r)) true)))
                         results)]
     {:suite-id suite-key
      :ok? all-ok?
      :results results})))

;; ---------------------------------------------------------------------------
;; Trace Minimisation Interface
;; ---------------------------------------------------------------------------

(defn minimise-suite
  "Minimize all failing traces in a suite to their smallest subset that still
   triggers target-invariant.  Only traces that fail with :invariant-violation
   are minimized; passing traces and structural failures are skipped.

   Returns {:suite-id kw :target-invariant kw :minimized-count int :results [...]}"
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
                   {:trace-id             (:scenario-id effective-trace)
                    :target-invariant     target-invariant
                    :event-count          (count (:events minimized))
                    :original-event-count (count (:events effective-trace))
                    :reduction            (- (count (:events effective-trace))
                                             (count (:events minimized)))
                    :minimized-trace      minimized})))))
    {:suite-id         suite-key
     :target-invariant target-invariant
     :minimized-count  (count @results)
     :results          @results}))

;; ---------------------------------------------------------------------------
;; Suite Discovery
;; ---------------------------------------------------------------------------

(defn list-suites
  "Scan data/fixtures/suites/ and return a map of suite-key → top-level metadata.
   Does not recursively compose fixtures; returns only the declared suite keys
   (:suite/id :suite/title :suite/purpose :suite/class :suite/criticality
   :suite/prevents) without loading traces or thresholds."
  []
  (let [suites-dir (io/file "data/fixtures/suites")]
    (->> (.listFiles suites-dir)
         (filter #(str/ends-with? (.getName %) ".edn"))
         (map (fn [f]
                (with-open [r (io/reader f)]
                  (let [suite (edn/read (java.io.PushbackReader. r))]
                    [(:suite/id suite)
                     (select-keys suite [:suite/id :suite/title :suite/purpose
                                         :suite/class :suite/criticality
                                         :suite/prevents])]))))
         (into {}))))
