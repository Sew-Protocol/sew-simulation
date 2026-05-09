(ns resolver-sim.scenario.subgame-counterfactual
  "Bounded subgame counterfactual evaluator (Phase 4 v1).

   Uses deterministic, local counterfactuals around strategic decision nodes.
   This v1 evaluator is intentionally bounded:
   - decision nodes are limited to key strategic actions,
   - alternatives are generated from a small fixed action set,
   - utilities are computed from world snapshots in the same replay trace.

   Output is deterministic and suitable as SPE-proxy evidence."
  (:require [clojure.string :as str]))

(def ^:private strategic-actions
  #{"raise_dispute" "escalate_dispute" "execute_resolution"})

(def ^:private action-alternatives
  {"raise_dispute" ["settle_now" "wait"]
   "escalate_dispute" ["settle_now" "wait"]
   "execute_resolution" ["defer_verdict" "alternate_verdict"]})

(defn- get-agent-wealth [world agent-id]
  (let [stakes    (get world :resolver-stakes {})
        claimable (get world :claimable {})
        bonds     (get world :bond-balances {})
        s (get stakes agent-id 0)
        c (reduce + 0 (for [[_ wf] claimable] (get wf agent-id 0)))
        b (reduce + 0 (for [[_ wf] bonds] (get wf agent-id 0)))]
    (+ s c b)))

(defn- node->table-row
  [{:keys [raw-trace terminal-state]} {:keys [agent address action] :as node}]
  (let [node-seq        (:seq node)
        idx             (long node-seq)
        pre-entry      (when (pos? idx) (nth raw-trace (dec idx) nil))
        chosen-entry   (nth raw-trace idx nil)
        actor          (or address agent)
        pre-world      (:world pre-entry)
        chosen-world   (:world chosen-entry)
        alternatives   (get action-alternatives action [])
        pre-utility    (when pre-world (get-agent-wealth pre-world actor))
        chosen-utility (when terminal-state
                         (get-agent-wealth terminal-state actor))
        local-alt-utility
        (when chosen-world
          (let [chosen-local (get-agent-wealth chosen-world actor)]
            (if (and (some? pre-utility) (some? chosen-local))
              ;; bounded local replay proxy: if chosen action immediately reduces
              ;; wealth (e.g. bond lock/slash), alternatives "wait/settle" avoid
              ;; that immediate drop in this local subgame snapshot.
              (max pre-utility chosen-local)
              chosen-local)))
        best-alt-utility (if (seq alternatives)
                           (max (or local-alt-utility Long/MIN_VALUE)
                                (or chosen-utility Long/MIN_VALUE))
                           chosen-utility)
        regret (if (and (some? best-alt-utility) (some? chosen-utility))
                 (max 0 (- best-alt-utility chosen-utility))
                 nil)]
    {:node-index idx
     :agent agent
     :address actor
     :chosen-action action
     :alternatives alternatives
     :chosen-utility chosen-utility
     :best-alt-utility best-alt-utility
     :local-regret regret
     :deterministic-key (str idx "|" agent "|" action)}))

(defn evaluate-subgame-counterfactual
  "Compute bounded local regret evidence for strategic decision nodes.

   Returns:
   {:status :pass|:fail|:inconclusive
    :basis  kw
    :regret-table [...]
    :max-regret n
    :threshold n
    :checked-nodes n
    :requires [...]}"
  [{:keys [raw-trace decisions terminal-world spe-config]}]
  (let [decision-nodes (->> decisions
                            (filter #(contains? strategic-actions (:action %)))
                            (sort-by (juxt :seq :agent :action))
                            vec)
        threshold      (long (get spe-config :regret-threshold 0))
        terminal-state (:world (last raw-trace))]
    (cond
      (empty? decision-nodes)
      {:status :inconclusive
       :basis :absent-evidence
       :regret-table []
       :max-regret nil
       :threshold threshold
       :checked-nodes 0
       :requires ["no strategic decision nodes (disputes/escalations/resolution) in trace"]}

      (not (:terminal? terminal-world))
      {:status :inconclusive
       :basis :multi-trace-required
       :regret-table []
       :max-regret nil
       :threshold threshold
       :checked-nodes (count decision-nodes)
       :requires ["trace ends before terminal settlement; counterfactual SPE proxy unavailable"]}

      :else
      (let [rows       (mapv #(node->table-row {:raw-trace raw-trace
                                                :terminal-state terminal-state}
                                               %)
                             decision-nodes)
            regrets    (keep :local-regret rows)
            max-regret (when (seq regrets) (apply max regrets))
            pass?      (and (some? max-regret) (<= max-regret threshold))]
        {:status (if pass? :pass :fail)
         :basis :single-trace-node-counterfactual-proxy
         :regret-table rows
         :max-regret max-regret
         :threshold threshold
         :checked-nodes (count rows)
         :requires []}))))
