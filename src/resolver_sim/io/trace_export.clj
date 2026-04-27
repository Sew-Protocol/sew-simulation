(ns resolver-sim.io.trace-export
  "Export Clojure simulation traces to Forge-compatible JSON fixtures.

   Converts the output of replay-scenario into the canonical trace format
   that TraceEquivalence.t.sol replays and verifies.

   Supports CDRS v0.1: standardized event schema, state buckets, and
   atomic reconciliation invariants."
  (:require [clojure.data.json                 :as json]
            [clojure.java.io                   :as io]
            [clojure.string                    :as str]
            [resolver-sim.contract-model.diff  :as diff]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.io.scenarios          :as scenarios]
            [resolver-sim.contract-model.trace-metadata     :as meta])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; EscrowState enum mapping (matches EscrowTypes.sol)
;; ---------------------------------------------------------------------------

(def ^:private escrow-state->int
  {:none     0
   :pending  1
   :released 2
   :refunded 3
   :disputed 4
   :resolved 5})

;; ---------------------------------------------------------------------------
;; Action name mapping: Clojure sim → Forge test
;; ---------------------------------------------------------------------------

(defn- clojure-action->forge-action
  "Map Clojure simulation action names to Forge test action names."
  [action raw-evt]
  (case action
    "execute_resolution"
    (if (get-in raw-evt [:params :is-release] true)
      "release_as_dispute_resolver"
      "cancel_as_dispute_resolver")
    action))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- kw-val->str [v]
  (if (keyword? v)
    (if-let [ns (namespace v)]
      (str ns "_" (name v))
      (name v))
    v))

(defn- kw-val->str-flat [v]
  (if (keyword? v) (name v) v))

(defn- projection->expected
  "Convert a diff/projection snapshot into the flat 'expected' map for Forge.
   Includes global_total_held as a sum of all token balances."
  [projection wf-id token-sym]
  (let [et           (get-in projection [:escrow-transfers wf-id])
        state-kw     (:escrow-state et)
        afa          (:amount-after-fee et 0)
        held-map     (:total-held projection {})
        fees-map     (:total-fees projection {})
        global-held  (apply + (vals held-map))
        global-fees  (apply + (vals fees-map))
        ps-exists    (get-in projection [:pending-settlements wf-id :exists] false)
        disp-level   (get-in projection [:dispute-levels wf-id] 0)]
    {:escrow_state              (get escrow-state->int state-kw 0)
     :escrow_amount_after_fee   afa
     :global_total_held         global-held
     :global_total_fees         global-fees
     :pending_settlement_exists ps-exists
     :dispute_level             disp-level}))

;; ---------------------------------------------------------------------------
;; Trace entry → Forge step
;; ---------------------------------------------------------------------------

(defn- step->forge-step
  "Convert one replay trace entry into a CDRS-compliant Forge trace step."
  [entry prev-proj scenario-events wf-alias-map token-sym]
  (let [seq-n    (:seq entry)
        action   (:action entry)
        result   (:result entry)
        proj     (:projection entry)
        metadata (:trace-metadata entry)

        raw-evt  (->> scenario-events (filter #(= (:seq %) seq-n)) first)
        forge-action (clojure-action->forge-action action raw-evt)
        caller-role (get raw-evt :agent "buyer")

        params   (case action
                   "create_escrow"
                   {:to_role  (get-in raw-evt [:params :to] "seller")
                    :amount   (get-in raw-evt [:params :amount] 0)}
                   {})

        wf-id    (cond
                   (= action "create_escrow") (get-in entry [:extra :workflow-id])
                   :else (let [raw-wf-id (or (get-in raw-evt [:params :workflow-id])
                                            (get-in raw-evt [:params :workflow_id]))]
                           (if (string? raw-wf-id) (get wf-alias-map raw-wf-id) raw-wf-id)))

        save-wf-as (:save-wf-as raw-evt)
        wf-alias   (or (some (fn [[k v]] (when (= v wf-id) k)) wf-alias-map)
                       (when wf-id (str "wf" wf-id)))

        expected-raw (projection->expected proj (or wf-id 0) token-sym)
        
        ;; Patch for resolution window logic
        expected-state
        (if (and expected-raw (= action "execute_resolution"))
          (assoc expected-raw :escrow_state 4 :pending_settlement_exists true)
          expected-raw)

        expected
        (if (= result :rejected)
          {:reverted true
           :error    (name (:error entry :unknown))
           :escrow_state (get-in expected-state [:escrow_state] 0)
           :pending_settlement_exists (get-in expected-state [:pending_settlement_exists] false)}
          expected-state)]

    (cond-> {:seq           seq-n
             :cdrs_version  "0.1"
             :event_type    (str/upper-case (str/replace forge-action #"_" " "))
             :step_type     (if (= result :ok) "protocol-transition" "economic-effect")
             :context_id    (or wf-alias "global")
             :actor         caller-role
             :timestamp     (:time entry 0)
             :state_bucket  (meta/state-bucket (:world entry) wf-id)
             :attributes    (cond-> {:action action :seq seq-n}
                              wf-alias (assoc :wf_alias wf-alias)
                              params   (merge params)
                              metadata (merge (into {} (map (fn [[k v]] [(kw-val->str-flat k) (kw-val->str-flat v)]) metadata))))}
      expected (assoc :expected expected))))

;; ---------------------------------------------------------------------------
;; Public: export-trace-fixture
;; ---------------------------------------------------------------------------

(defn export-trace-fixture
  "Convert a replay-scenario result into a Forge-compatible trace fixture map."
  [result scenario & {:keys [token-sym] :or {token-sym "TOKEN"}}]
  (let [trace       (:trace result)
        events      (:events scenario)
        last-world  (:world (last trace))
        ;; Build a wf-alias-map by scanning trace for create_escrow :ok entries
        wf-alias-map
        (reduce (fn [m entry]
                  (let [raw (->> events (filter #(= (:seq %) (:seq entry))) first)]
                    (if (and (= (:action entry) "create_escrow")
                             (= (:result entry) :ok)
                             (:save-wf-as raw))
                      (assoc m (:save-wf-as raw) (get-in entry [:extra :workflow-id]))
                      m)))
                {}
                trace)

        steps (loop [entries   trace
                      prev-proj nil
                      acc       []]
                (if (empty? entries)
                  acc
                  (let [entry (first entries)
                        step  (step->forge-step entry prev-proj events wf-alias-map token-sym)]
                    (recur (rest entries) (:projection entry) (conj acc step)))))]
    {:cdrs_version   "0.1"
     :schema_version "1"
     :scenario_id    (:scenario-id result)
     :description    (str "Generated trace: " (:scenario-id result))
     :fee_bps        (get-in scenario [:protocol-params :escrow-fee-bps] 100)
     :step_count     (count steps)
     :steps          steps
     ;; Resolution summary for all escrows in the trace
     :resolutions    (into {} (for [[alias id] wf-alias-map]
                                [alias (meta/resolution-semantics last-world id)]))}))

;; ---------------------------------------------------------------------------
;; JSON serialisation
;; ---------------------------------------------------------------------------

(defn fixture->json-str
  "Serialize a fixture map to a pretty-printed JSON string."
  [fixture]
  (with-out-str (json/pprint fixture)))

(defn write-fixture-file
  "Write a fixture map to a JSON file at `path`."
  [fixture path]
  (io/make-parents path)
  (spit path (fixture->json-str fixture)))

;; ---------------------------------------------------------------------------
;; CLI entry point
;; ---------------------------------------------------------------------------

(defn -main
  "CLI: replay a scenario file and write the Forge trace fixture."
  [& args]
  (when (not= (count args) 2)
    (println "Usage: trace-export <scenario-json> <output-fixture-json>")
    (System/exit 1))
  (let [[scenario-path output-path] args
        scenario (scenarios/load-scenario-file scenario-path)
        result   (replay/replay-scenario scenario)]
    (if (= :invalid (:outcome result))
      (do (println "ERROR: scenario invalid:" (:halt-reason result))
          (System/exit 2))
      (let [fixture (export-trace-fixture result scenario)]
        (write-fixture-file fixture output-path)
        (println "Written" (count (:steps fixture)) "steps to" output-path)))))
