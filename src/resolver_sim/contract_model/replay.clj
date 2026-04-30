(ns resolver-sim.contract-model.replay
  "Open-world scenario replay engine. (Protocol-Agnostic Kernel)

   Provides the deterministic harness for executing scenarios.
   Implementation details (actions, invariants, snapshots) are delegated to a
   DisputeProtocol implementation.

   ## Replay Invariants
   After every successful transition:
     1. protocol/check-invariants-single
     2. protocol/check-invariants-transition"
  (:require [clojure.data.json              :as json]
            [clojure.string                :as str]
            [resolver-sim.protocols.protocol :as engine]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def ^:private supported-versions #{"1.0" "1.1"})

;; ---------------------------------------------------------------------------
;; JSON serialisation helpers (Generic)
;; ---------------------------------------------------------------------------

(defn- kw->json-key [k]
  (if (keyword? k) (name k) (str k)))

(defn- kw-val->str [_k v]
  (if (keyword? v) (name v) v))

;; ---------------------------------------------------------------------------
;; Agent Validation (Generic)
;; ---------------------------------------------------------------------------

(defn validate-agents
  "Validate a list of agent maps {:id :address :type ...} for structural correctness.
   Returns {:ok true} or {:ok false :error kw :detail {...}}.

   Checks: non-empty, unique :id values, unique :address values."
  [agents]
  (let [id-counts   (frequencies (map :id agents))
        addr-counts (frequencies (map :address agents))
        dup-ids     (keys (filter (fn [[_ n]] (> n 1)) id-counts))
        dup-addrs   (keys (filter (fn [[_ n]] (> n 1)) addr-counts))]
    (cond
      (empty? agents)   {:ok false :error :no-agents}
      (seq dup-ids)     {:ok false :error :duplicate-agent-ids
                         :detail {:duplicates (vec dup-ids)}}
      (seq dup-addrs)   {:ok false :error :duplicate-agent-addresses
                         :detail {:duplicates (vec dup-addrs)}}
      :else             {:ok true})))

;; ---------------------------------------------------------------------------
;; Input validation (Generic scenario structure)
;; ---------------------------------------------------------------------------

(defn validate-scenario
  "Validate a scenario map for structural correctness before replay."
  [scenario]
  (let [version     (str (:schema-version scenario))
        agents      (:agents scenario)
        events      (sort-by :seq (:events scenario))
        known-ids   (set (map :id agents))
        init-time   (get scenario :initial-block-time 1000)
        agent-check (validate-agents agents)]
    (cond
      (not (contains? supported-versions version))
      {:ok false :error :unsupported-schema-version
       :detail {:expected supported-versions :got version}}

      (and (= version "1.1") (not (:id scenario)))
      {:ok false :error :missing-id :detail "v1.1 scenarios must have a unique :id"}

      (and (= version "1.1") (not (:purpose scenario)))
      {:ok false :error :missing-purpose :detail "v1.1 scenarios must declare a :purpose"}

      (not (:ok agent-check))
      agent-check

      (empty? events)
      {:ok false :error :no-events}

      (not= (mapv :seq events) (vec (range (count events))))
      {:ok false :error :non-contiguous-event-seq :detail {:got (mapv :seq events)}}

      (some (fn [[a b]] (> (:time a) (:time b))) (partition 2 1 events))
      {:ok false :error :non-monotonic-event-time
       :detail {:violations (vec (filter (fn [[a b]] (> (:time a) (:time b))) (partition 2 1 events)))}}

      (some #(< (:time %) init-time) events)
      {:ok false :error :event-time-before-initial
       :detail {:initial-block-time init-time
                :violations (mapv :time (filter #(< (:time %) init-time) events))}}

      (some #(not (contains? known-ids (:agent %))) events)
      {:ok false :error :unknown-agent-in-event
       :detail {:bad-refs (vec (filter #(not (contains? known-ids (:agent %))) events))}}

      :else {:ok true})))

;; ---------------------------------------------------------------------------
;; Metrics (Generic)
;; ---------------------------------------------------------------------------

(defn- zero-metrics []
  {:total-escrows                0
   :total-volume                 0
   :disputes-triggered           0
   :resolutions-executed         0
   :pending-settlements-executed 0
   :attack-attempts              0
   :attack-successes             0
   :reverts                      0
   :invariant-violations         0})

(defn- accum-metrics [metrics event trace-entry agent-index]
  (let [action    (:action event)
        accepted? (= :ok (:result trace-entry))
        agent     (get agent-index (:agent event))
        attack?   (= "attacker" (:type agent))]
    (cond-> metrics
      (and accepted? (= action "create_escrow"))
      (-> (update :total-escrows inc)
          (update :total-volume + (get-in event [:params :amount] 0)))

      (and accepted? (= action "raise_dispute"))
      (update :disputes-triggered inc)

      (and accepted? (= action "execute_resolution"))
      (update :resolutions-executed inc)

      (and accepted? (= action "execute_pending_settlement"))
      (update :pending-settlements-executed inc)

      (and attack? accepted?)
      (update :attack-successes inc)

      attack?
      (update :attack-attempts inc)

      (not accepted?)
      (update :reverts inc)

      (:violations trace-entry)
      (update :invariant-violations inc))))

;; ---------------------------------------------------------------------------
;; Workflow-id alias resolution (Generic)
;; ---------------------------------------------------------------------------

(defn- resolve-wf-alias
  [event wf-alias-map]
  (let [wf-val (get-in event [:params :workflow-id])]
    (if (string? wf-val)
      (if-let [int-id (get wf-alias-map wf-val)]
        {:ok true :event (assoc-in event [:params :workflow-id] int-id)}
        {:ok false :error :unresolved-alias :alias wf-val :seq (:seq event)})
      {:ok true :event event})))

;; ---------------------------------------------------------------------------
;; Step Processing (Kernel)
;; ---------------------------------------------------------------------------

(defn process-step
  "Apply one scenario event using a specific DisputeProtocol implementation."
  [protocol context world event]
  (let [event-time (:time event)
        now        (:block-time world)]
    (if (< event-time now)
      (let [[proj proj-hash] (engine/compute-projection protocol world)]
        {:ok?    true
         :world  world
         :trace-entry {:seq             (:seq event)
                       :time            event-time
                       :agent           (:agent event)
                       :action          (:action event)
                       :result          :rejected
                       :error           :time-regression
                       :extra           nil
                       :invariants-ok?  true
                       :violations      nil
                       :world           (engine/world-snapshot protocol world)
                       :projection      proj
                       :projection-hash proj-hash}
         :halted? false})

      (let [world-t    (if (> event-time now) (assoc world :block-time event-time) world)
            result     (try
                         (engine/dispatch-action protocol context world-t event)
                         (catch Exception e
                           {:ok false :error :dispatch-exception
                            :detail {:message (.getMessage e)}}))
            ok?        (:ok result)
            world-next (if ok? (:world result) world-t)

            inv-single (when ok? (engine/check-invariants-single protocol world-next))
            inv-trans  (when ok? (engine/check-invariants-transition protocol world-t world-next))
            violated?  (and ok? (not (and (:ok? inv-single) (:ok? inv-trans))))
            all-violations (when violated?
                             (merge (when-not (:ok? inv-single) (:violations inv-single))
                                    (when-not (:ok? inv-trans)  (:violations inv-trans))))]

        (let [result-kw    (cond violated? :invariant-violated ok? :ok :else :rejected)
              final-world  (if violated? world-t world-next)
              [proj ph]    (engine/compute-projection protocol final-world)]
          {:ok?    (and ok? (not violated?))
           :world  final-world
           :trace-entry
           {:seq             (:seq event)
            :time            event-time
            :agent           (:agent event)
            :action          (:action event)
            :result          result-kw
            :error           (when-not ok? (:error result))
            :extra           (:extra result)
            :invariants-ok?  (if ok? (and (:ok? inv-single) (:ok? inv-trans)) true)
            :violations      all-violations
            :trace-metadata  (engine/classify-transition protocol (:action event) result-kw)
            :world           (engine/world-snapshot protocol final-world)
            :projection      proj
            :projection-hash ph}
           :halted? violated?})))))

;; ---------------------------------------------------------------------------
;; Public API (Generic)
;; ---------------------------------------------------------------------------

(defn replay-with-protocol
  "Replay a scenario map using a specific DisputeProtocol implementation."
  [protocol scenario]
  (let [validation (validate-scenario scenario)]
    (if-not (:ok validation)
      {:outcome :invalid :scenario-id (:scenario-id scenario) :events-processed 0 :trace [] :metrics (zero-metrics) :halt-reason (:error validation)}
      (let [agents   (get-in scenario [:metadata :agents] (:agents scenario))
            p-params (get-in scenario [:metadata :protocol_params] (get scenario :protocol-params {}))
            context  (engine/build-execution-context protocol agents p-params)
            agent-index (:agent-index context)
            world0  (engine/init-world protocol scenario)
            events  (sort-by :seq (:events scenario))]
        (loop [world world0 events events trace [] metrics (zero-metrics) wf-alias-map {}]
          (if (empty? events)
            (let [open-disputes (when-not (:allow-open-disputes? scenario)
                                  (vec (for [[wf et] (:escrow-transfers world) :when (= :disputed (:escrow-state et))] wf)))]
              (if (seq open-disputes)
                {:outcome :fail :scenario-id (:scenario-id scenario) :events-processed (count trace) :halt-reason :open-disputes-at-end :detail {:open-disputes open-disputes} :trace trace :metrics metrics}
                {:outcome :pass :scenario-id (:scenario-id scenario) :events-processed (count trace) :trace trace :metrics metrics}))
            (let [raw-event (first events)
                  alias-res (resolve-wf-alias raw-event wf-alias-map)]
              (if-not (:ok alias-res)
                {:outcome :invalid :scenario-id (:scenario-id scenario) :events-processed (count trace) :halted-at-seq (:seq raw-event) :halt-reason :unresolved-alias :detail (dissoc alias-res :ok) :trace trace :metrics metrics}
                (let [event (:event alias-res)
                      step  (process-step protocol context world event)
                      entry (:trace-entry step)
                      new-trace (conj trace entry)
                      new-metrics (accum-metrics metrics event entry agent-index)
                      new-alias-map (if (and (= "create_escrow" (:action event)) (= :ok (:result entry)) (:save-wf-as raw-event))
                                      (assoc wf-alias-map (:save-wf-as raw-event) (get-in entry [:extra :workflow-id]))
                                      wf-alias-map)]
                  (if (:halted? step)
                    {:outcome :fail :scenario-id (:scenario-id scenario) :events-processed (count new-trace) :halted-at-seq (:seq event) :halt-reason :invariant-violation :trace new-trace :metrics (update new-metrics :invariant-violations inc)}
                    (recur (:world step) (rest events) new-trace new-metrics new-alias-map)))))))))))

(defn replay-scenario
  "Standard SEW entry point: replays using SEWProtocol.
   Callers that need a different protocol should use replay-with-protocol directly."
  [scenario]
  (replay-with-protocol @(requiring-resolve 'resolver-sim.protocols.sew/protocol) scenario))

;; ---------------------------------------------------------------------------
;; Legacy/Compatibility helpers (Bridge to SEW implementation)
;; ---------------------------------------------------------------------------

(defn- sew-protocol []
  @(requiring-resolve 'resolver-sim.protocols.sew/protocol))

(defn build-context [agents protocol-params]
  (engine/build-execution-context (sew-protocol) agents protocol-params))

(defn sew-dispatch-action [context world event]
  (engine/dispatch-action (sew-protocol) context world event))

(defn sew-check-invariants-single [world]
  (engine/check-invariants-single (sew-protocol) world))

(defn sew-check-invariants-transition [world-before world-after]
  (engine/check-invariants-transition (sew-protocol) world-before world-after))

(defn result->json-str
  "Serialize a replay result to a JSON string."
  [result]
  (json/write-str result :key-fn kw->json-key :value-fn kw-val->str))
