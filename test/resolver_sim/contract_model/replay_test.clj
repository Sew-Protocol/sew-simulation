(ns resolver-sim.contract-model.replay-test
  "Unit tests for the open-world scenario replay engine.

   Covers:
     - Structural validation (schema-version, seq, time)
     - Happy-path and dispute scenarios
     - Adversarial rejections (not halts)
     - Invariant enforcement (solvency = not <=, terminal irreversibility)
     - Edge cases: time regression, unknown agent, overflow guard, duplicate seq
     - Escalation flows: full chain (0→1→2), mid-pending escalation, adversarial rejections"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay    :as replay]
            [resolver-sim.contract-model.invariants :as inv]
            [resolver-sim.contract-model.types     :as t]
            [resolver-sim.contract-model.resolution :as res]))

;; ---------------------------------------------------------------------------
;; Scenario builder helpers
;; ---------------------------------------------------------------------------

(def ^:private alice    {:id "alice"    :type "honest"        :address "0xAlice"})
(def ^:private bob      {:id "bob"      :type "honest"        :address "0xBob"})
(def ^:private mallory  {:id "mallory"  :type "attacker"      :address "0xMallory"})
(def ^:private resolver {:id "resolver" :type "honest"        :address "0xResolver"})

(def ^:private default-params
  {:resolver-fee-bps 50 :appeal-window-duration 0
   :max-dispute-duration 2592000 :appeal-bond-protocol-fee-bps 0})

(defn- sc [& {:keys [agents params init-time events schema-version]
              :or   {agents       [alice bob resolver]
                     params       default-params
                     init-time    1000
                     schema-version "1.0"}}]
  {:scenario-id        "test"
   :schema-version     schema-version
   :seed               42
   :agents             agents
   :protocol-params    params
   :initial-block-time init-time
   :events             events})

;; ---------------------------------------------------------------------------
;; Section 1: Structural validation
;; ---------------------------------------------------------------------------

(deftest test-validation-wrong-schema-version
  (let [r (replay/replay-scenario
           (sc :schema-version "2.0"
               :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :unsupported-schema-version (:halt-reason r)))))

(deftest test-validation-missing-schema-version
  (let [r (replay/replay-scenario
           (assoc (sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                                :params {:token "0xUSDC" :to "0xBob" :amount 5000}}])
                  :schema-version nil))]
    (is (= :invalid (:outcome r)))))

(deftest test-validation-non-contiguous-seq
  (let [r (replay/replay-scenario
           (sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                        {:seq 2 :time 1001 :agent "alice" :action "release"  ; gap at 1
                          :params {:workflow-id 0}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :non-contiguous-event-seq (:halt-reason r)))))

(deftest test-validation-duplicate-seq
  (let [r (replay/replay-scenario
           (sc :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                        {:seq 0 :time 1001 :agent "alice" :action "release"  ; dup
                          :params {:workflow-id 0}}]))]
    (is (= :invalid (:outcome r)))))

(deftest test-validation-non-monotonic-time
  (let [r (replay/replay-scenario
           (sc :events [{:seq 0 :time 1005 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                        {:seq 1 :time 1000 :agent "alice" :action "release"  ; backward
                          :params {:workflow-id 0}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :non-monotonic-event-time (:halt-reason r)))))

(deftest test-validation-event-time-before-initial
  (let [r (replay/replay-scenario
           (sc :init-time 2000
               :events [{:seq 0 :time 999 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :event-time-before-initial (:halt-reason r)))))

(deftest test-validation-unknown-agent-in-event
  (let [r (replay/replay-scenario
           (sc :events [{:seq 0 :time 1000 :agent "nobody" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :unknown-agent-in-event (:halt-reason r)))))

(deftest test-validation-duplicate-agent-ids
  (let [alice2 {:id "alice" :type "attacker" :address "0xEvil"}  ; same id, different address
        r (replay/replay-scenario
           (sc :agents [alice alice2 bob resolver]
               :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :duplicate-agent-ids (:halt-reason r)))))

(deftest test-validation-duplicate-agent-addresses
  (let [alice2 {:id "alice2" :type "attacker" :address "0xAlice"}  ; same address, different id
        r (replay/replay-scenario
           (sc :agents [alice alice2 bob resolver]
               :events [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                          :params {:token "0xUSDC" :to "0xBob" :amount 5000}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :duplicate-agent-addresses (:halt-reason r)))))

;; ---------------------------------------------------------------------------
;; Section 2: Time regression in process-step
;; ---------------------------------------------------------------------------

(deftest test-time-regression-is-rejected-not-halted
  ;; Build scenario with valid structure but inject a backward-time event via
  ;; process-step directly (bypasses replay-scenario validation for testing the
  ;; step-level guard independently)
  (let [world0  (t/empty-world 2000)
        context {:agent-index {"alice" alice "resolver" resolver}
                 :snapshot    (t/make-module-snapshot {:escrow-fee-bps 50})}
        event   {:seq 0 :time 999 :agent "alice" :action "advance_time" :params {}}
        result  (replay/process-step context world0 event)]
    (testing "rejected, not halted"
      (is (= :rejected (get-in result [:trace-entry :result])))
      (is (false? (:halted? result))))
    (testing "world unchanged"
      (is (= 2000 (:block-time (:world result)))))))

;; ---------------------------------------------------------------------------
;; Section 3: Happy path
;; ---------------------------------------------------------------------------

(deftest test-happy-path-release
  (let [r (replay/replay-scenario
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 10000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2 (:events-processed r)))
    (is (= 1 (get-in r [:metrics :total-escrows])))
    (is (= 10000 (get-in r [:metrics :total-volume])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))
    (is (= 0 (get-in r [:trace 0 :extra :workflow-id])))))

;; ---------------------------------------------------------------------------
;; Section 4: Dispute + resolution
;; ---------------------------------------------------------------------------

(deftest test-dispute-and-resolution
  (let [r (replay/replay-scenario
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 10000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1001 :agent "bob" :action "raise_dispute"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1005 :agent "resolver" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release true}}]))]
    (is (= :pass (:outcome r)))
    (is (= 1 (get-in r [:metrics :disputes-triggered])))
    (is (= 1 (get-in r [:metrics :resolutions-executed])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (every? #(= :ok (:result %)) (:trace r)))))

;; ---------------------------------------------------------------------------
;; Section 5: Adversarial rejections — non-fatal
;; ---------------------------------------------------------------------------

(deftest test-attacker-unauthorized-resolution-is-revert
  (let [r (replay/replay-scenario
           (sc :agents [alice bob mallory resolver]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 10000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1001 :agent "alice" :action "raise_dispute"
                  :params {:workflow-id 0}}
                ;; Mallory is not the resolver
                {:seq 2 :time 1002 :agent "mallory" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release false}}
                ;; Legit resolver succeeds
                {:seq 3 :time 1003 :agent "resolver" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release true}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (= 1 (get-in r [:metrics :attack-attempts])))
    (is (= 0 (get-in r [:metrics :attack-successes])))))

(deftest test-dispute-after-release-rejected
  (let [r (replay/replay-scenario
           (sc :agents [alice bob mallory]
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 3000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1002 :agent "mallory" :action "raise_dispute"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 2 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

(deftest test-unknown-agent-in-action-is-revert-not-halt
  ;; Unknown agent passes validation because it's not in the event list
  ;; at the scenario level but IS here (we're testing process-step directly)
  (let [world0  (t/empty-world 1000)
        context {:agent-index {"alice" alice}
                 :snapshot    (t/make-module-snapshot {:escrow-fee-bps 50})}
        event   {:seq 0 :time 1000 :agent "nobody" :action "create_escrow"
                 :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
        result  (replay/process-step context world0 event)]
    (is (= :rejected (get-in result [:trace-entry :result])))
    (is (false? (:halted? result)))
    (is (= :unknown-agent (:error (:trace-entry result))))))

;; ---------------------------------------------------------------------------
;; Section 6: Overflow guard
;; ---------------------------------------------------------------------------

(deftest test-amount-overflow-guard
  (let [r (replay/replay-scenario
           (sc :events
               ;; amount > max-safe-amount = 922337203685477
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 999999999999999999}}]))]
    (is (= :pass (:outcome r)))    ; outcome is pass — it's a clean revert
    (is (= :rejected (get-in r [:trace 0 :result])))
    (is (= :amount-out-of-safe-range (get-in r [:trace 0 :error])))))

;; ---------------------------------------------------------------------------
;; Section 7: Mutual cancel
;; ---------------------------------------------------------------------------

(deftest test-mutual-cancel
  (let [r (replay/replay-scenario
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                {:seq 1 :time 1001 :agent "alice" :action "sender_cancel"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1002 :agent "bob" :action "recipient_cancel"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 0 (get-in r [:metrics :invariant-violations])))
    (is (every? #(= :ok (:result %)) (:trace r)))))

;; ---------------------------------------------------------------------------
;; Section 8: Auto-cancel after max dispute duration
;; ---------------------------------------------------------------------------

(deftest test-auto-cancel-after-timeout
  (let [r (replay/replay-scenario
           (sc :params (assoc default-params :max-dispute-duration 500)
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 8000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1000 :agent "bob" :action "raise_dispute"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1501 :agent "alice" :action "auto_cancel_disputed"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 2 :result])))))

;; ---------------------------------------------------------------------------
;; Section 9: Appeal window
;; ---------------------------------------------------------------------------

(deftest test-appeal-window-deferred-settlement
  (let [r (replay/replay-scenario
           (sc :params (assoc default-params :appeal-window-duration 100)
               :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 10000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1000 :agent "alice" :action "raise_dispute"
                  :params {:workflow-id 0}}
                {:seq 2 :time 1000 :agent "resolver" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release true}}
                ;; Before deadline (deadline = 1000 + 100 = 1100)
                {:seq 3 :time 1050 :agent "bob" :action "execute_pending_settlement"
                  :params {:workflow-id 0}}
                ;; After deadline
                {:seq 4 :time 1101 :agent "bob" :action "execute_pending_settlement"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :rejected (get-in r [:trace 3 :result])))
    (is (= :ok (get-in r [:trace 4 :result])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

;; ---------------------------------------------------------------------------
;; Section 10: Multiple concurrent escrows
;; ---------------------------------------------------------------------------

(deftest test-multiple-escrows-isolated
  (let [r (replay/replay-scenario
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000
                            :custom-resolver "0xResolver"}}
                {:seq 1 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 7000
                            :custom-resolver "0xResolver"}}
                {:seq 2 :time 1001 :agent "alice" :action "raise_dispute"
                  :params {:workflow-id 0}}
                {:seq 3 :time 1002 :agent "resolver" :action "execute_resolution"
                  :params {:workflow-id 0 :is-release false}}
                {:seq 4 :time 1003 :agent "alice" :action "release"
                  :params {:workflow-id 1}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2 (get-in r [:metrics :total-escrows])))
    (is (= 0 (get-in r [:metrics :invariant-violations])))))

;; ---------------------------------------------------------------------------
;; Section 11: Solvency invariant — strict equality
;; ---------------------------------------------------------------------------

(deftest test-solvency-strict-equality-catches-overfunded-world
  ;; Directly construct a world where total-held > sum of live escrows
  ;; (simulates a finalization bug that forgot to call sub-held)
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0]
                             {:token "0xUSDC" :to "0xBob" :from "0xAlice"
                              :amount-after-fee 9000
                              :dispute-resolver nil
                              :auto-release-time 0 :auto-cancel-time 0
                              :escrow-state :released     ; terminal — not live
                              :sender-status :none :recipient-status :none})
                  ;; total-held still has 9000 — not decremented (the bug)
                  (assoc-in [:total-held "0xUSDC"] 9000))]
    (let [r (inv/check-all world)]
      (testing "strict equality catches orphaned held amount"
        ;; live-sum = 0 (escrow is terminal); held = 9000; 0 ≠ 9000 → violation
        (is (false? (:all-hold? r)))))))

(deftest test-solvency-strict-equality-passes-clean-world
  ;; Clean world: total-held exactly matches live escrow amount
  (let [world (-> (t/empty-world 1000)
                  (assoc-in [:escrow-transfers 0]
                             {:token "0xUSDC" :to "0xBob" :from "0xAlice"
                              :amount-after-fee 5000
                              :dispute-resolver nil
                              :auto-release-time 0 :auto-cancel-time 0
                              :escrow-state :pending
                              :sender-status :none :recipient-status :none})
                  (assoc-in [:total-held "0xUSDC"] 5000))]
    (let [r (inv/check-all world)]
      (is (true? (:all-hold? r))))))

;; ---------------------------------------------------------------------------
;; Section 12: Terminal state irreversibility
;; ---------------------------------------------------------------------------

(deftest test-terminal-irreversibility-catches-regression
  ;; Simulate a world where an escrow regressed from :released to :pending
  (let [world-before (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :released))
        world-after  (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :pending))
        r            (inv/check-transition world-before world-after)]
    (is (false? (:all-hold? r)))
    (is (seq (get-in r [:results :terminal-states-unchanged :violations])))))

(deftest test-terminal-irreversibility-passes-valid-transition
  (let [world-before (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :pending))
        world-after  (-> (t/empty-world 1000)
                         (assoc-in [:escrow-transfers 0 :escrow-state] :disputed))
        r            (inv/check-transition world-before world-after)]
    (is (true? (:all-hold? r)))))

;; ---------------------------------------------------------------------------
;; Section 13: JSON serialization
;; ---------------------------------------------------------------------------

(deftest test-json-serialization
  (let [r    (replay/replay-scenario
              (sc :events
                  [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                     :params {:token "0xUSDC" :to "0xBob" :amount 500}}]))
        json (replay/result->json-str r)]
    (is (string? json))
    (is (clojure.string/includes? json "pass"))))

;; ---------------------------------------------------------------------------
;; Section 14: advance_time no-op
;; ---------------------------------------------------------------------------

(deftest test-advance-time-event
  (let [r (replay/replay-scenario
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 1000}}
                {:seq 1 :time 2000 :agent "alice" :action "advance_time"
                  :params {}}
                {:seq 2 :time 2000 :agent "alice" :action "sender_cancel"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2000 (get-in r [:trace 1 :world :block-time])))))

;; ---------------------------------------------------------------------------
;; Section 15: Full escalation chain 0 → 1 → 2
;;
;; Uses process-step directly with an escalation-fn in context.
;; Priority-3 authority: no custom-resolver; et.dispute-resolver tracks rounds.
;;
;; Setup:
;;   appeal-window-duration = 500 → execute-resolution defers verdict
;;   Level 0 → Level 1 → Level 2 (final)
;;   At level 2 execute-resolution is IMMEDIATE (bypasses appeal window)
;; ---------------------------------------------------------------------------

(def ^:private res-level-0 "0xResolver0")
(def ^:private res-level-1 "0xResolver1")
(def ^:private res-level-2 "0xResolver2")

(def ^:private appeal-params
  {:resolver-fee-bps 50 :appeal-window-duration 500
   :max-dispute-duration 2592000 :appeal-bond-protocol-fee-bps 0})

(defn- esc-fn-cycling
  "Escalation fn that cycles resolvers by level: 0→res-level-1, 1→res-level-2."
  [_world _wf _caller level]
  {:ok true :new-resolver (case level
                            0 res-level-1
                            1 res-level-2
                            "0xKleros")})

(defn- make-step-context
  "Build a context with appeal-params snapshot and the cycling escalation-fn."
  []
  (let [agents [{:id "alice"    :address "0xAlice"    :type "honest"}
                {:id "bob"      :address "0xBob"      :type "honest"}
                {:id "resolver0" :address res-level-0  :type "resolver"}
                {:id "resolver1" :address res-level-1  :type "resolver"}
                {:id "resolver2" :address res-level-2  :type "resolver"}]]
    (replay/build-context agents appeal-params esc-fn-cycling)))

(defn- initial-disputed-world
  "Build a world with one :disputed escrow whose et.dispute-resolver = res-level-0.
   Uses process-step to drive create_escrow + raise_dispute so invariants hold."
  [context]
  (let [w0 (t/empty-world 1000)
        s1 (replay/process-step context w0
                                {:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                                 :params {:token "0xUSDC" :to "0xBob" :amount 10000}})
        ;; Set Priority-3 resolver (no custom-resolver in create, so we patch directly)
        w1 (assoc-in (:world s1) [:escrow-transfers 0 :dispute-resolver] res-level-0)
        s2 (replay/process-step context w1
                                {:seq 1 :time 1000 :agent "alice" :action "raise_dispute"
                                 :params {:workflow-id 0}})]
    (:world s2)))

(deftest test-full-escalation-chain-0-to-2
  "Full chain: execute-resolution(deferred@0) → escalate → execute-resolution(deferred@1)
               → escalate → execute-resolution(immediate@2=final)."
  (let [ctx  (make-step-context)
        w    (initial-disputed-world ctx)
        ;; Level 0: resolver0 submits verdict → deferred (appeal window = 500)
        s1   (replay/process-step ctx w
                                  {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true}})
        _    (testing "level-0 verdict deferred"
               (is (= :ok (get-in s1 [:trace-entry :result])))
               (is (:exists (t/get-pending (:world s1) 0))))
        ;; Escalate 0→1
        s2   (replay/process-step ctx (:world s1)
                                  {:seq 3 :time 1000 :agent "alice" :action "escalate_dispute"
                                   :params {:workflow-id 0}})
        _    (testing "escalation 0→1"
               (is (= :ok (get-in s2 [:trace-entry :result])))
               (is (= 1 (t/dispute-level (:world s2) 0)))
               (is (= res-level-1 (get-in (:world s2) [:escrow-transfers 0 :dispute-resolver])))
               (is (nil? (get-in (:world s2) [:pending-settlements 0]))
                   "pending cleared by escalation"))
        ;; Level 1: resolver1 submits verdict → still deferred (not yet final round)
        s3   (replay/process-step ctx (:world s2)
                                  {:seq 4 :time 1000 :agent "resolver1" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true}})
        _    (testing "level-1 verdict deferred"
               (is (= :ok (get-in s3 [:trace-entry :result])))
               (is (:exists (t/get-pending (:world s3) 0))))
        ;; Escalate 1→2
        s4   (replay/process-step ctx (:world s3)
                                  {:seq 5 :time 1000 :agent "alice" :action "escalate_dispute"
                                   :params {:workflow-id 0}})
        _    (testing "escalation 1→2"
               (is (= :ok (get-in s4 [:trace-entry :result])))
               (is (= 2 (t/dispute-level (:world s4) 0)))
               (is (= res-level-2 (get-in (:world s4) [:escrow-transfers 0 :dispute-resolver])))
               (is (nil? (get-in (:world s4) [:pending-settlements 0]))))
        ;; Level 2 (final round): resolver2 submits verdict → IMMEDIATE (no pending)
        s5   (replay/process-step ctx (:world s4)
                                  {:seq 6 :time 1000 :agent "resolver2" :action "execute_resolution"
                                   :params {:workflow-id 0 :is-release true}})]
    (testing "final-round verdict is immediate"
      (is (= :ok (get-in s5 [:trace-entry :result])))
      (is (= :released (t/escrow-state (:world s5) 0)))
      (is (not (:exists (t/get-pending (:world s5) 0)))))
    (testing "invariants hold throughout"
      (is (true? (:all-hold? (inv/check-all (:world s5))))))))

;; ---------------------------------------------------------------------------
;; Section 16: Mid-pending escalation
;;
;; Pending settlement at level 0 → escalation clears it → stale execute-pending
;; is rejected → new resolver at level 1 proceeds.
;; ---------------------------------------------------------------------------

(deftest test-escalation-clears-pending-settlement
  "Escalation clears a pending settlement; stale execute-pending-settlement is rejected."
  (let [ctx (make-step-context)
        w   (initial-disputed-world ctx)
        ;; Submit verdict at level 0 → deferred
        s1  (replay/process-step ctx w
                                 {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release false}})
        _   (is (:exists (t/get-pending (:world s1) 0)) "pending exists before escalation")
        ;; Escalate 0→1 — clears pending
        s2  (replay/process-step ctx (:world s1)
                                 {:seq 3 :time 1000 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})
        _   (testing "pending cleared"
              (is (= :ok (get-in s2 [:trace-entry :result])))
              (is (nil? (get-in (:world s2) [:pending-settlements 0]))))
        ;; Try to execute-pending-settlement after escalation → rejected
        s3  (replay/process-step ctx (:world s2)
                                 {:seq 4 :time 1501 :agent "bob" :action "execute_pending_settlement"
                                  :params {:workflow-id 0}})
        _   (testing "stale execute-pending rejected"
              (is (= :rejected (get-in s3 [:trace-entry :result]))))
        ;; New resolver (level 1) successfully resolves
        s4  (replay/process-step ctx (:world s3)
                                 {:seq 5 :time 1501 :agent "resolver1" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release false}})]
    (testing "level-1 resolver succeeds"
      (is (= :ok (get-in s4 [:trace-entry :result]))))
    (testing "invariants hold"
      (is (true? (:all-hold? (inv/check-all (:world s4))))))))

;; ---------------------------------------------------------------------------
;; Section 17: Adversarial escalation rejections
;;
;; a. Non-participant tries to escalate → :not-participant (rejected, not halt)
;; b. After reaching max level, third escalation attempt → :escalation-not-allowed
;; ---------------------------------------------------------------------------

(deftest test-non-participant-escalation-rejected
  "Attacker (not :from or :to) cannot escalate a dispute."
  (let [ctx-with-mallory
        (let [agents [{:id "alice"    :address "0xAlice"    :type "honest"}
                      {:id "bob"      :address "0xBob"      :type "honest"}
                      {:id "mallory"  :address "0xMallory"  :type "attacker"}
                      {:id "resolver0" :address res-level-0 :type "resolver"}
                      {:id "resolver1" :address res-level-1 :type "resolver"}]]
          (replay/build-context agents appeal-params esc-fn-cycling))
        w   (initial-disputed-world ctx-with-mallory)
        ;; Submit verdict → deferred
        s1  (replay/process-step ctx-with-mallory w
                                 {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        ;; Mallory (not a participant) tries to escalate
        s2  (replay/process-step ctx-with-mallory (:world s1)
                                 {:seq 3 :time 1000 :agent "mallory" :action "escalate_dispute"
                                  :params {:workflow-id 0}})]
    (testing "non-participant escalation rejected"
      (is (= :rejected (get-in s2 [:trace-entry :result])))
      (is (= :not-participant (get-in s2 [:trace-entry :error]))))
    (testing "world unchanged (level still 0)"
      (is (= 0 (t/dispute-level (:world s2) 0))))
    (testing "no halt"
      (is (false? (:halted? s2))))))

(deftest test-max-level-escalation-rejected
  "After two escalations (level = max-dispute-level), a third attempt is rejected."
  (let [ctx (make-step-context)
        w   (initial-disputed-world ctx)
        ;; Level 0: submit → deferred → escalate 0→1
        s1  (replay/process-step ctx w
                                 {:seq 2 :time 1000 :agent "resolver0" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        s2  (replay/process-step ctx (:world s1)
                                 {:seq 3 :time 1000 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})
        _   (is (= 1 (t/dispute-level (:world s2) 0)))
        ;; Level 1: submit → deferred → escalate 1→2
        s3  (replay/process-step ctx (:world s2)
                                 {:seq 4 :time 1000 :agent "resolver1" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        s4  (replay/process-step ctx (:world s3)
                                 {:seq 5 :time 1000 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})
        _   (testing "second escalation ok"
              (is (= :ok (get-in s4 [:trace-entry :result])))
              (is (= 2 (t/dispute-level (:world s4) 0))))
        ;; Level 2 is final: execute-resolution is immediate (no pending created)
        s5  (replay/process-step ctx (:world s4)
                                 {:seq 6 :time 1000 :agent "resolver2" :action "execute_resolution"
                                  :params {:workflow-id 0 :is-release true}})
        _   (testing "final-round verdict is immediate"
              (is (= :ok (get-in s5 [:trace-entry :result])))
              (is (= :released (t/escrow-state (:world s5) 0))))
        ;; After finalization, a third escalation is impossible (not :disputed)
        s6  (replay/process-step ctx (:world s5)
                                 {:seq 7 :time 1000 :agent "alice" :action "escalate_dispute"
                                  :params {:workflow-id 0}})]
    (testing "escalation rejected after finalization"
      (is (= :rejected (get-in s6 [:trace-entry :result]))))
    (testing "no halt — just a revert"
      (is (false? (:halted? s6))))
    (testing "invariants hold throughout"
      (is (true? (:all-hold? (inv/check-all (:world s6))))))))

;; ---------------------------------------------------------------------------
;; Section 18: Workflow-id alias resolution
;; ---------------------------------------------------------------------------

(deftest test-wf-alias-save-and-resolve
  ":save-wf-as captures the assigned workflow-id; subsequent events resolve the alias."
  (let [r (replay/replay-scenario
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000
                            :custom-resolver "0xResolver"}
                  :save-wf-as "wf0"}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id "wf0"}}]))]
    (is (= :pass (:outcome r)))
    (is (= 2 (:events-processed r)))
    (is (= :ok (get-in r [:trace 0 :result])))
    (is (= :ok (get-in r [:trace 1 :result])))))

(deftest test-wf-alias-multi-escrow
  "Multiple aliases can be saved and resolved independently."
  (let [r (replay/replay-scenario
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 3000}
                  :save-wf-as "wf0"}
                {:seq 1 :time 1001 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 4000}
                  :save-wf-as "wf1"}
                {:seq 2 :time 1002 :agent "alice" :action "release"
                  :params {:workflow-id "wf1"}}
                {:seq 3 :time 1003 :agent "alice" :action "release"
                  :params {:workflow-id "wf0"}}]))]
    (is (= :pass (:outcome r)))
    (is (= 4 (:events-processed r)))
    (is (= 2 (get-in r [:metrics :total-escrows])))
    (is (= :ok (get-in r [:trace 2 :result])))
    (is (= :ok (get-in r [:trace 3 :result])))))

(deftest test-wf-alias-unresolved-returns-invalid
  "A string alias with no prior :save-wf-as returns :invalid outcome."
  (let [r (replay/replay-scenario
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id "no-such-alias"}}]))]
    (is (= :invalid (:outcome r)))
    (is (= :unresolved-alias (:halt-reason r)))))

(deftest test-wf-alias-integer-passes-through
  "Integer workflow-ids bypass the alias layer unchanged."
  (let [r (replay/replay-scenario
           (sc :events
               [{:seq 0 :time 1000 :agent "alice" :action "create_escrow"
                  :params {:token "0xUSDC" :to "0xBob" :amount 5000}}
                {:seq 1 :time 1001 :agent "alice" :action "release"
                  :params {:workflow-id 0}}]))]
    (is (= :pass (:outcome r)))
    (is (= :ok (get-in r [:trace 1 :result])))))
