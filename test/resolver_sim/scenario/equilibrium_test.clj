(ns resolver-sim.scenario.equilibrium-test
  "Unit tests for the trace-end mechanism-property and equilibrium-concept validators.
   Uses synthetic projections — no replay required.

   These tests cover cases that cannot be included in the fixture suite directly
   (mechanism :fail cases) as well as the full set of :pass, :inconclusive, and
   :not-applicable paths for each validator."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.equilibrium :as eq]
            [resolver-sim.scenario.projection  :as proj]))

;; ---------------------------------------------------------------------------
;; Synthetic projection helpers
;; ---------------------------------------------------------------------------

(defn- projection
  "Build a minimal synthetic projection for unit tests."
  [{:keys [terminal? halt-reason total-held
           attack-attempts attack-successes funds-lost
           invariant-violations negative-payoff-count
           coalition-net-profit]
    :or   {terminal?          true
           halt-reason        :all-terminal
           total-held         {}
           attack-attempts    0
           attack-successes   0
           funds-lost         0
           invariant-violations 0}}]
  {:terminal-world {:terminal?          terminal?
                    :total-held-by-token total-held
                    :escrow-count       1}
   :metrics        {:attack-attempts       attack-attempts
                    :attack-successes      attack-successes
                    :funds-lost            funds-lost
                    :invariant-violations  invariant-violations
                    :negative-payoff-count negative-payoff-count
                    :coalition-net-profit  coalition-net-profit}
   :trace-summary  {:halt-reason   halt-reason
                    :events-count  2
                    :actors        ["buyer" "seller"]
                    :terminal-time 1100}})

;; ---------------------------------------------------------------------------
;; budget-balance
;; ---------------------------------------------------------------------------

(deftest test-budget-balance-pass
  (testing "terminal escrows, total-held = 0 → :pass"
    (let [proj (projection {:terminal? true :total-held {"USDC" 0}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj)
                     :budget-balance)]
      (is (= :pass (:status result)))
      (is (= :hard (:severity result))))))

(deftest test-budget-balance-fail
  (testing "terminal escrows but total-held > 0 → :fail"
    (let [proj (projection {:terminal? true :total-held {"USDC" 500}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj)
                     :budget-balance)]
      (is (= :fail (:status result)))
      (is (seq (:offending result))))))

(deftest test-budget-balance-not-applicable-non-terminal
  (testing "non-terminal escrows → :not-applicable"
    (let [proj (projection {:terminal? false :total-held {"USDC" 5000}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj)
                     :budget-balance)]
      (is (= :not-applicable (:status result))))))

(deftest test-budget-balance-not-applicable-open-disputes
  (testing "halt-reason :open-disputes-at-end → :not-applicable"
    (let [proj (projection {:terminal? false
                            :halt-reason :open-disputes-at-end
                            :total-held {"USDC" 5000}})
          result (-> (eq/evaluate-mechanism-properties [:budget-balance] proj)
                     :budget-balance)]
      (is (= :not-applicable (:status result))))))

;; ---------------------------------------------------------------------------
;; incentive-compatibility
;; ---------------------------------------------------------------------------

(deftest test-incentive-compat-inconclusive-no-adversarial
  (testing "no adversarial actors → :inconclusive"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :inconclusive (:status result))))))

(deftest test-incentive-compat-pass-adversarial-rejected
  (testing "attacks attempted, all rejected, no funds lost → :pass"
    (let [proj (projection {:attack-attempts 3 :attack-successes 0 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :pass (:status result))))))

(deftest test-incentive-compat-fail-attack-success
  (testing "attack succeeded (attack-successes > 0) → :fail"
    (let [proj (projection {:attack-attempts 1 :attack-successes 1 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :fail (:status result))))))

(deftest test-incentive-compat-fail-funds-lost
  (testing "funds-lost > 0 → :fail"
    (let [proj (projection {:attack-attempts 1 :attack-successes 0 :funds-lost 100})
          result (-> (eq/evaluate-mechanism-properties [:incentive-compatibility] proj)
                     :incentive-compatibility)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; individual-rationality
;; ---------------------------------------------------------------------------

(deftest test-individual-rationality-inconclusive
  (testing "no payoff-ledger (negative-payoff-count nil), funds-lost = 0 → :inconclusive"
    (let [proj (projection {:negative-payoff-count nil :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj)
                     :individual-rationality)]
      (is (= :inconclusive (:status result))))))

(deftest test-individual-rationality-pass-with-ledger
  (testing "negative-payoff-count = 0 → :pass"
    (let [proj (projection {:negative-payoff-count 0 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj)
                     :individual-rationality)]
      (is (= :pass (:status result))))))

(deftest test-individual-rationality-fail-negative-payoff
  (testing "negative-payoff-count > 0 → :fail"
    (let [proj (projection {:negative-payoff-count 2 :funds-lost 0})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj)
                     :individual-rationality)]
      (is (= :fail (:status result))))))

(deftest test-individual-rationality-fail-funds-lost
  (testing "funds-lost > 0 (partial proxy) → :fail"
    (let [proj (projection {:negative-payoff-count nil :funds-lost 100})
          result (-> (eq/evaluate-mechanism-properties [:individual-rationality] proj)
                     :individual-rationality)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; collusion-resistance
;; ---------------------------------------------------------------------------

(deftest test-collusion-resistance-inconclusive
  (testing "coalition-net-profit absent → :inconclusive"
    (let [proj (projection {:coalition-net-profit nil})
          result (-> (eq/evaluate-mechanism-properties [:collusion-resistance] proj)
                     :collusion-resistance)]
      (is (= :inconclusive (:status result))))))

(deftest test-collusion-resistance-pass
  (testing "coalition-net-profit <= 0 → :pass"
    (let [proj (projection {:coalition-net-profit -50})
          result (-> (eq/evaluate-mechanism-properties [:collusion-resistance] proj)
                     :collusion-resistance)]
      (is (= :pass (:status result))))))

(deftest test-collusion-resistance-fail
  (testing "coalition-net-profit > 0 → :fail"
    (let [proj (projection {:coalition-net-profit 100})
          result (-> (eq/evaluate-mechanism-properties [:collusion-resistance] proj)
                     :collusion-resistance)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; dominant-strategy-equilibrium
;; ---------------------------------------------------------------------------

(deftest test-dominant-strategy-inconclusive-no-attacks
  (testing "no adversarial actors, no violations → :inconclusive"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :inconclusive (:status result))))))

(deftest test-dominant-strategy-pass
  (testing "attacks present, none succeeded, no violations → :pass"
    (let [proj (projection {:attack-attempts 3 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :pass (:status result))))))

(deftest test-dominant-strategy-fail-attack-success
  (testing "attack-successes > 0 → :fail"
    (let [proj (projection {:attack-attempts 1 :attack-successes 1 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :fail (:status result))))))

(deftest test-dominant-strategy-fail-invariant-violation
  (testing "invariant-violations > 0 → :fail"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :invariant-violations 2})
          result (-> (eq/evaluate-equilibrium-concepts [:dominant-strategy-equilibrium] proj)
                     :dominant-strategy-equilibrium)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; nash-equilibrium
;; ---------------------------------------------------------------------------

(deftest test-nash-inconclusive-no-attacks
  (testing "no adversarial actors → :inconclusive"
    (let [proj (projection {:attack-attempts 0 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium] proj)
                     :nash-equilibrium)]
      (is (= :inconclusive (:status result))))))

(deftest test-nash-pass
  (testing "attacks rejected, no violations → :pass"
    (let [proj (projection {:attack-attempts 2 :attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium] proj)
                     :nash-equilibrium)]
      (is (= :pass (:status result))))))

(deftest test-nash-fail-attack-success
  (testing "attack-successes > 0 → :fail (eq-v9 scenario)"
    (let [proj (projection {:attack-attempts 1 :attack-successes 1 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:nash-equilibrium] proj)
                     :nash-equilibrium)]
      (is (= :fail (:status result))))))

;; ---------------------------------------------------------------------------
;; SPE / BNE — always :inconclusive
;; ---------------------------------------------------------------------------

(deftest test-subgame-perfect-equilibrium
  (testing "SPE inconclusive when no strategic decisions made"
    (let [proj {:raw-trace [{:world {}}] :decisions [] :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :inconclusive (:status result)))
      (is (= :absent-evidence (:basis result)))))

  (testing "SPE inconclusive when trace is not terminal"
    (let [proj {:raw-trace [{:world {}}]
                :decisions [{:index 0 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? false}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :inconclusive (:status result)))
      (is (= :multi-trace-required (:basis result)))))

  (testing "SPE PASS: rational escalation (won after appeal)"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}    ; t=0
                            {:world {:bond-balances {"e1" {"buyer" 50}}}}  ; t=1 (escalate)
                            {:world {:claimable {"e1" {"buyer" 150}}}}] ; t=2 (won: escrow 100 + bond 50)
                :decisions [{:index 1 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :pass (:status result)))
      (is (= :single-trace-node-proxy (:basis result)))
      (is (= 1 (get-in result [:observed :decisions-checked])))
      (is (= :pass (get-in result [:observed :spe-status])))))

  (testing "SPE FAIL: irrational escalation (lost after appeal)"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}    ; t=0
                            {:world {:bond-balances {"e1" {"buyer" 50}}}}  ; t=1 (escalate)
                            {:world {:claimable {"e1" {"buyer" 0}}}}] ; t=2 (lost: bond slashed)
                :decisions [{:index 1 :seq 1 :agent "buyer" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :fail (:status result)))
      (is (= :single-trace-node-proxy (:basis result)))
      (is (= 1 (count (get-in result [:observed :spe-violations]))))
      (is (= 50 (get-in result [:offending 0 :loss])))
      (is (= :ex-post-regret (get-in result [:offending 0 :class])))))

  (testing "SPE PASS: rational dispute"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"seller" 0}}}}
                            {:world {:claimable {"e1" {"seller" 0}}}}
                            {:world {:claimable {"e1" {"seller" 100}}}}]
                :decisions [{:index 1 :seq 1 :agent "seller" :action "raise_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :pass (:status result)))))

  (testing "SPE FAIL: multiple violations"
    (let [proj {:raw-trace [{:world {:claimable {"e1" {"a" 0 "b" 0}}}}
                            {:world {:bond-balances {"e1" {"a" 10}}}} ; a escalate
                            {:world {:bond-balances {"e1" {"a" 10 "b" 10}}}} ; b escalate
                            {:world {:claimable {"e1" {"a" 0 "b" 0}}}}] ; both lost
                :decisions [{:index 1 :seq 1 :agent "a" :action "escalate_dispute"}
                            {:index 2 :seq 2 :agent "b" :action "escalate_dispute"}]
                :terminal-world {:terminal? true}}
          result (-> (eq/evaluate-equilibrium-concepts [:subgame-perfect-equilibrium] proj)
                     :subgame-perfect-equilibrium)]
      (is (= :fail (:status result)))
      (is (= 2 (count (get-in result [:observed :spe-violations])))))))


(deftest test-bne-always-inconclusive
  (testing "bayesian-nash-equilibrium always returns :inconclusive"
    (let [proj (projection {:attack-successes 0 :invariant-violations 0})
          result (-> (eq/evaluate-equilibrium-concepts [:bayesian-nash-equilibrium] proj)
                     :bayesian-nash-equilibrium)]
      (is (= :inconclusive (:status result))))))

;; ---------------------------------------------------------------------------
;; Status roll-up
;; ---------------------------------------------------------------------------

(deftest test-evaluate-equilibrium-all-pass
  (testing "evaluate-equilibrium with clean metrics → mechanism :pass (no attacks → :inconclusive)"
    (let [theory  {:mechanism-properties [:budget-balance]
                   :equilibrium-concept  [:dominant-strategy-equilibrium]}
          result  {:metrics {:attack-attempts 2 :attack-successes 0
                             :invariant-violations 0 :funds-lost 0}
                   :trace   [{:world {:total-held    {"USDC" 0}
                                      :total-fees    {"USDC" 0}
                                      :live-states   {1 :released}
                                      :escrow-count  1}}]}
          eq-out  (eq/evaluate-equilibrium theory result)]
      ;; budget-balance: terminal world with 0 held → :pass
      (is (= :pass (get-in eq-out [:mechanism-results :budget-balance :status])))
      ;; dominant-strategy: attack-attempts 2, successes 0 → :pass
      (is (= :pass (get-in eq-out [:equilibrium-results :dominant-strategy-equilibrium :status])))
      (is (= :pass (:mechanism-status eq-out)))
      (is (= :pass (:equilibrium-status eq-out))))))

(deftest test-evaluate-equilibrium-fail-propagates
  (testing "a :fail in mechanism results rolls up to :fail status"
    (let [theory {:mechanism-properties [:budget-balance]}
          result {:metrics {:attack-attempts 0 :attack-successes 0
                            :invariant-violations 0 :funds-lost 0}
                  :trace   [{:world {:total-held    {"USDC" 5000}
                                     :total-fees    {"USDC" 0}
                                     :live-states   {1 :released}
                                     :escrow-count  1}}]}
          eq-out (eq/evaluate-equilibrium theory result)]
      ;; Escrow is in :released but total-held = 5000 → terminal? check
      ;; live-states has 1 :released → terminal? = true → budget-balance FAIL
      (is (= :fail (get-in eq-out [:mechanism-results :budget-balance :status])))
      (is (= :fail (:mechanism-status eq-out))))))

(deftest test-unknown-property-inconclusive
  (testing "unknown mechanism property → :inconclusive with absent-evidence basis"
    (let [proj (projection {})
          result (-> (eq/evaluate-mechanism-properties [:unknown-future-property] proj)
                     :unknown-future-property)]
      (is (= :inconclusive (:status result)))
      (is (= :absent-evidence (:basis result))))))
