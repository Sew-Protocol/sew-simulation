(ns resolver-sim.protocols.sew
  "SEWProtocol — implementation of DisputeProtocol for the SEW state machine."
  (:require [resolver-sim.protocols.protocol             :as proto]
            [resolver-sim.protocols.sew.types       :as t]
            [resolver-sim.protocols.sew.diff        :as diff]
            [resolver-sim.protocols.sew.state-machine  :as sm]
            [resolver-sim.protocols.sew.lifecycle      :as lc]
            [resolver-sim.protocols.sew.resolution     :as res]
            [resolver-sim.protocols.sew.registry       :as reg]
            [resolver-sim.protocols.sew.authority      :as auth]
            [resolver-sim.protocols.sew.trace-metadata :as meta]
            [resolver-sim.protocols.sew.invariants     :as inv]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

;; Maximum safe amount: prevents Long overflow in (* amount fee-bps)
;; fee-bps max = 10000; Long/MAX_VALUE / 10000 ≈ 922_337_203_685_477
(def ^:private max-safe-amount 922337203685477)

;; ---------------------------------------------------------------------------
;; Helpers (Moved from replay.clj)
;; ---------------------------------------------------------------------------

(defn- resolve-address
  "Return {:ok true :address addr} or {:ok false :error :unknown-agent}.
   Never throws."
  [agent-index agent-id]
  (if-let [agent (get agent-index agent-id)]
    {:ok true :address (:address agent)}
    {:ok false :error :unknown-agent :detail {:agent-id agent-id}}))

(defn- build-snapshot [pp]
  (t/make-module-snapshot
   {:escrow-fee-bps               (get pp :resolver-fee-bps 50)
    :resolution-module            (get pp :resolution-module nil)
    :appeal-window-duration       (get pp :appeal-window-duration 0)
    :max-dispute-duration         (get pp :max-dispute-duration 2592000)
    :appeal-bond-protocol-fee-bps (get pp :appeal-bond-protocol-fee-bps 0)
    :dispute-resolver             (get pp :dispute-resolver nil)
    :appeal-bond-bps              (get pp :appeal-bond-bps 0)
    :resolver-bond-bps            (get pp :resolver-bond-bps 1000) ; 10%
    :appeal-bond-amount           (get pp :appeal-bond-amount 0)
    :reversal-slash-bps           (get pp :reversal-slash-bps 0)
    :fraud-slash-bps              (get pp :fraud-slash-bps 5000)
    :challenge-window-duration    (get pp :challenge-window-duration 0)
    :challenge-bond-bps           (get pp :challenge-bond-bps 0)
    :challenge-bounty-bps         (get pp :challenge-bounty-bps 0)
    :default-auto-release-delay   (get pp :default-auto-release-delay 0)
    :default-auto-cancel-delay    (get pp :default-auto-cancel-delay 0)
    :yield-generation-module      (get pp :yield-generation-module nil)
    :yield-distribution-module    (get pp :yield-distribution-module nil)
    :yield-protocol-fee-bps       (get pp :yield-protocol-fee-bps 0)
    :cancellation-strategy        (get pp :cancellation-strategy nil)
    :release-strategy             (get pp :release-strategy nil)
    :incentive-module             (get pp :incentive-module nil)}))

(defn- sender-only-release [world workflow-id caller]
  (let [et (t/get-transfer world workflow-id)]
    {:allowed? (= caller (:from et)) :reason-code 0}))

;; ---------------------------------------------------------------------------
;; Dispatch (The SEW State Machine Actions)
;; ---------------------------------------------------------------------------

(defmulti apply-action
  (fn [_ctx _world event] (:action event)))

(defmethod apply-action "create_escrow"
  [{:keys [agent-index snapshot]} world event]
  (let [p       (:params event)
        ar      (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [caller (:address ar)
            token  (:token p)
            to     (:to p)
            amount (:amount p)]
        ;; Guard against Long overflow in fee arithmetic
        (if (or (nil? amount) (<= amount 0) (> amount max-safe-amount))
          {:ok false :error :amount-out-of-safe-range
           :detail {:amount amount :max max-safe-amount}}
          (let [cres     (get p :custom-resolver)
                settings (t/make-escrow-settings {:custom-resolver cres})
                result   (lc/create-escrow world caller token to amount settings snapshot)]
            (if (:ok result)
              (assoc result :extra {:workflow-id (:workflow-id result)})
              result)))))))

(defmethod apply-action "raise_dispute"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (lc/raise-dispute world (get-in event [:params :workflow-id]) (:address ar)))))

(defmethod apply-action "execute_resolution"
  [{:keys [agent-index resolution-module-fn resolution-level-map]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p               (:params event)
            workflow-id     (:workflow-id p)
            is-release      (get p :is-release true)
            resolution-hash (get p :resolution-hash "0xsimhash")
            ;; Kleros mode: build module fn per-step so it reads the live dispute level
            ;; from world (level changes after each escalation).
            effective-rm-fn (or (when resolution-level-map
                                  (auth/make-kleros-module
                                   resolution-level-map
                                   #(t/dispute-level world %)))
                                resolution-module-fn)]
        (res/execute-resolution world workflow-id (:address ar)
                                is-release resolution-hash effective-rm-fn)))))

(defmethod apply-action "execute_pending_settlement"
  [_ctx world event]
  (res/execute-pending-settlement world (get-in event [:params :workflow-id])))

(defmethod apply-action "automate_timed_actions"
  [_ctx world event]
  (res/automate-timed-actions world (get-in event [:params :workflow-id])))

(defmethod apply-action "release"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (lc/release world (get-in event [:params :workflow-id])
                  (:address ar) sender-only-release))))

(defmethod apply-action "sender_cancel"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      ;; nil cancel-strategy → mutual-consent path only
      (lc/sender-cancel world (get-in event [:params :workflow-id]) (:address ar) nil))))

(defmethod apply-action "recipient_cancel"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (lc/recipient-cancel world (get-in event [:params :workflow-id]) (:address ar) nil))))

(defmethod apply-action "auto_cancel_disputed"
  [_ctx world event]
  (lc/auto-cancel-disputed-escrow world (get-in event [:params :workflow-id])))

(defmethod apply-action "escalate_dispute"
  [{:keys [agent-index escalation-fn]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [workflow-id (get-in event [:params :workflow-id])
            result      (res/escalate-dispute world workflow-id (:address ar) escalation-fn)]
        (if (:ok result)
          (assoc result :extra {:new-level    (:new-level result)
                                :new-resolver (:new-resolver result)})
          result)))))

(defmethod apply-action "rotate_dispute_resolver"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [workflow-id   (get-in event [:params :workflow-id])
            new-resolver  (get-in event [:params :new-resolver])
            result        (res/rotate-dispute-resolver world workflow-id new-resolver)]
        (if (:ok result)
          (assoc result :extra {:old-resolver (:old-resolver result)
                                :new-resolver (:new-resolver result)})
          result)))))

(defmethod apply-action "register_stake"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [amount (get-in event [:params :amount] 0)]
        (t/ok (reg/register-stake world (:address ar) amount))))))

(defmethod apply-action "register_resolver_bond"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p      (:params event)
            stable (get p :stable 0)
            sew    (get p :sew 0)]
        (t/ok (assoc-in world [:resolver-bonds (:address ar)]
                        {:stable stable :sew sew}))))))

(defmethod apply-action "register_senior_bond"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p            (:params event)
            coverage-max (get p :coverage-max 0)]
        (t/ok (assoc-in world [:senior-bonds (:address ar)]
                        {:coverage-max coverage-max :reserved-coverage 0}))))))

(defmethod apply-action "delegate_to_senior"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p           (:params event)
            senior-addr (:senior-addr p)
            coverage    (:coverage p 0)
            senior-bond (get-in world [:senior-bonds senior-addr])]
        (if (nil? senior-bond)
          (t/fail :senior-not-registered)
          (let [new-reserved (+ (:reserved-coverage senior-bond) coverage)
                max-coverage (:coverage-max senior-bond)]
            (if (> new-reserved max-coverage)
              (t/fail :senior-coverage-exceeded)
              (t/ok (assoc-in world [:senior-bonds senior-addr :reserved-coverage]
                              new-reserved)))))))))

(defmethod apply-action "propose_fraud_slash"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p             (:params event)
            workflow-id   (:workflow-id p)
            resolver-addr (:resolver-addr p)
            amount        (:amount p)]
        (res/propose-fraud-slash world workflow-id (:address ar) resolver-addr amount)))))

(defmethod apply-action "challenge_resolution"
  [{:keys [agent-index escalation-fn]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (res/challenge-resolution world (get-in event [:params :workflow-id]) (:address ar) escalation-fn))))

(defmethod apply-action "appeal_slash"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p (:params event)]
        (res/appeal-slash world (:workflow-id p) (:address ar)
                          (or (:slash-id p) (:workflow-id p)))))))

(defmethod apply-action "resolve_appeal"
  [{:keys [agent-index]} world event]
  (let [ar (resolve-address agent-index (:agent event))]
    (if-not (:ok ar)
      ar
      (let [p (:params event)]
        (res/resolve-appeal world (:workflow-id p) (:address ar) (:upheld? p))))))

(defmethod apply-action "execute_fraud_slash"
  [_ctx world event]
  (let [p (:params event)]
    (res/execute-fraud-slash world (:workflow-id p)
                             (or (:slash-id p) (:workflow-id p)))))

(defmethod apply-action "advance_time"
  [_ctx world _event]
  ;; Time is already advanced before dispatch — this is a pure no-op.
  (t/ok world))

(defmethod apply-action :default
  [_ctx _world event]
  {:ok false :error :unknown-action :detail {:action (:action event)}})

;; ---------------------------------------------------------------------------
;; Invariant Checks
;; ---------------------------------------------------------------------------

(defn- check-invariants-single
  "Single-world invariants (solvency, fee non-negative).
   Returns {:ok? bool :violations map-or-nil}."
  [world]
  (let [r (inv/check-all world)]
    {:ok?        (:all-hold? r)
     :violations (when-not (:all-hold? r) (:results r))}))

(defn- check-invariants-transition
  "Cross-world invariants (terminal state irreversibility).
   Returns {:ok? bool :violations map-or-nil}."
  [world-before world-after]
  (let [r (inv/check-transition world-before world-after)]
    {:ok?        (:all-hold? r)
     :violations (when-not (:all-hold? r) (:results r))}))

;; ---------------------------------------------------------------------------
;; Trace Snapshot
;; ---------------------------------------------------------------------------

(defn- world-snapshot [world]
  {:block-time         (:block-time world)
   :escrow-count       (count (:escrow-transfers world))
   :total-held         (:total-held world)
   :total-fees         (:total-fees world)
   :pending-count      (count (filter #(:exists (val %)) (:pending-settlements world)))
   :live-states        (into {} (map (fn [[id et]] [id (:escrow-state et)])
                                     (:escrow-transfers world)))
   :dispute-levels     (into {} (:dispute-levels world))
   :dispute-resolvers  (into {} (map (fn [[id et]] [id (:dispute-resolver et)])
                                     (:escrow-transfers world)))
   :resolver-rotations (into {} (:resolver-rotations world))
   :escrow-amounts     (into {} (map (fn [[id et]] [id (:amount-after-fee et)])
                                     (:escrow-transfers world)))
   :resolver-stakes     (:resolver-stakes world)
   :bond-distribution   (:bond-distribution world)})

;; ---------------------------------------------------------------------------
;; SEWProtocol Implementation
;; ---------------------------------------------------------------------------

(deftype SEWProtocol []
  proto/DisputeProtocol

  (build-execution-context [_ agents protocol-params]
    (let [pp         protocol-params
          snapshot   (build-snapshot pp)
          rm-addr    (get pp :resolution-module nil)
          esc-map    (get pp :escalation-resolvers nil)
          level-map  (when esc-map
                       (into {} (map (fn [[k v]] [(parse-long (name k)) v]) esc-map)))
          rm-fn      (when (and rm-addr (not= rm-addr "") (nil? level-map))
                       (auth/make-default-resolution-module rm-addr))
          esc-fn     (when level-map
                       (fn [_world _wf-id _caller current-level]
                         (let [next-level   (inc current-level)
                               new-resolver (get level-map next-level)]
                           (if new-resolver
                             {:ok true :new-resolver new-resolver}
                             {:ok false :error :escalation-not-allowed}))))]
      {:agent-index          (into {} (map (juxt :id identity) agents))
       :snapshot             snapshot
       :escalation-fn        esc-fn
       :resolution-module-fn rm-fn
       :resolution-level-map level-map}))

  (dispatch-action [_ context world event]
    (apply-action context world event))

  (check-invariants-single [_ world]
    (check-invariants-single world))

  (check-invariants-transition [_ world-before world-after]
    (check-invariants-transition world-before world-after))

  (world-snapshot [_ world]
    (world-snapshot world))

  (init-world [_ scenario]
    (let [init-time    (get scenario :initial-block-time 1000)
          tp           (:token-params scenario)
          fot-bps      (when tp (get tp :fee-on-transfer 0))
          s-tokens     (into #{} (keep #(get-in % [:params :token]) (:events scenario)))
          base         (t/empty-world init-time)]
      (if (and fot-bps (pos? fot-bps) (seq s-tokens))
        (reduce (fn [w tok] (assoc-in w [:token-fot-bps tok] fot-bps)) base s-tokens)
        base)))

  (compute-projection [_ world]
    [(diff/projection world) (diff/projection-hash world)])

  (classify-transition [_ action result-kw]
    {:transition/type (meta/transition-type action)
     :effect/type     (meta/effect-type result-kw)
     :resolution/path (meta/resolution-path action)}))

(def protocol (SEWProtocol.))
