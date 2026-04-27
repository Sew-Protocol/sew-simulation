(ns resolver-sim.contract-model.trace-metadata
  "Typed metadata for simulation traces.
   Categorizes actions, effects, and failures to improve auditability
   and facilitate CDRS v0.1 compatibility."
  (:require [resolver-sim.contract-model.types :as t]))

;; ---------------------------------------------------------------------------
;; Issue & Failure Types
;; ---------------------------------------------------------------------------

(defn classify-issue [result]
  (let [metrics (:metrics result {})
        liveness-fail? (pos? (get-in result [:score-components :liveness-failure] 0))
        invariant-fail? (pos? (:invariant-violations metrics 0))]
    (cond
      invariant-fail? :issue/invariant-violation
      liveness-fail?  :issue/liveness-failure
      :else           :issue/none)))

;; ---------------------------------------------------------------------------
;; Action & Effect Types
;; ---------------------------------------------------------------------------

(defn transition-type [action]
  (case action
    "create_escrow"             :transition/initialization
    "raise_dispute"              :transition/dispute-initiation
    "execute_resolution"         :transition/resolution-proposal
    "execute_pending_settlement" :transition/settlement-execution
    "automate_timed_actions"     :transition/maintenance
    "release"                   :transition/terminal
    "sender_cancel"             :transition/cancellation
    "recipient_cancel"          :transition/cancellation
    "auto_cancel_disputed"      :transition/liveness-exit
    :transition/unknown))

(defn effect-type [result-kw]
  (case result-kw
    :ok                 :effect/state-change
    :rejected           :effect/revert
    :invariant-violated :effect/halt
    :effect/none))

;; ---------------------------------------------------------------------------
;; Resolution Semantics
;; ---------------------------------------------------------------------------

(defn resolution-path [action]
  (case action
    "execute_resolution"         :resolution/standard
    "execute_pending_settlement" :resolution/delayed
    "auto_cancel_disputed"      :resolution/timeout
    :resolution/none))

(defn resolution-outcome [world workflow-id]
  (let [state (t/escrow-state world workflow-id)]
    (case state
      :released :resolution/release
      :refunded :resolution/refund
      :resolved :resolution/settled
      :resolution/pending)))

;; ---------------------------------------------------------------------------
;; CDRS v0.1 Canonical Buckets
;; ---------------------------------------------------------------------------

(defn- clean-id [id]
  (if (string? id)
    (try (Integer/parseInt (clojure.string/replace id #"^wf" ""))
         (catch Exception _ id))
    id))

(defn state-bucket [world workflow-id]
  (let [id      (clean-id workflow-id)
        state   (or (get-in world [:escrow-transfers id :escrow-state])
                    (get-in world [:live-states id])
                    :none)
        pending (or (get-in world [:pending-settlements id])
                    (when (pos? (get world :pending-count 0))
                      {:exists true}) ;; Rough approximation for snapshot
                    {:exists false})]
    (cond
      (= :none state)      "IDLE"
      (= :pending state)   "ACTIVE"
      (and (= :disputed state) (:exists pending)) "RECONCILING"
      (= :disputed state)  "CHALLENGED"
      (contains? #{:released :refunded :resolved} state) "SETTLED"
      :else "IDLE")))

(defn resolution-semantics [world workflow-id]
  (let [id      (clean-id workflow-id)
        state   (or (get-in world [:escrow-transfers id :escrow-state])
                    (get-in world [:live-states id])
                    :none)
        pending (or (get-in world [:pending-settlements id])
                    {:exists false})]
    (case state
      :released {:outcome "RELEASE" :finality "FINAL" :integrity "FULLY_RECONCILED"}
      :refunded {:outcome "REFUND"  :finality "FINAL" :integrity "FULLY_RECONCILED"}
      :resolved {:outcome "SETTLED" :finality "FINAL" :integrity "FULLY_RECONCILED"}
      :disputed (if (:exists pending)
                  {:outcome "NO_OP" :finality "APPEALABLE" :integrity "MISSING_EFFECTS"}
                  {:outcome "NO_OP" :finality "STALLED"    :integrity "ACCOUNTING_MISMATCH"})
      {:outcome "NO_OP" :finality "STALLED" :integrity "LEAKAGE"})))
