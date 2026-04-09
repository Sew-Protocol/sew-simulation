(ns resolver-sim.db.telemetry-test
  "Unit tests for the telemetry adapter.

   All tests run with ds=nil — no XTDB instance required.
   They verify the pure conversion functions (trial->outcome-record,
   trial->event-records) and confirm that all write calls are no-ops
   on a nil datasource."
  (:require [clojure.test :refer [deftest testing is are]]
            [resolver-sim.contract-model.runner    :as runner]
            [resolver-sim.db.telemetry :as tel]
            [resolver-sim.db.store     :as ss]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private base-params
  {:escrow-size                      10000
   :resolver-fee-bps                 50
   :appeal-bond-bps                  200
   :appeal-window-duration           0
   :max-dispute-duration             2592000
   :slashing-detection-probability   0.5
   :appeal-probability-if-correct    0.0
   :appeal-probability-if-wrong      0.3
   :strategy                         :malicious
   :block-time                       1000})

(defn- rng [] 0.9)  ; deterministic: always above 0.3, so verdict is wrong (malicious)

(defn- run-one [strategy]
  (runner/run-trial rng (assoc base-params :strategy strategy)))

;; ---------------------------------------------------------------------------
;; trial->outcome-record
;; ---------------------------------------------------------------------------

(deftest test-outcome-record-field-mapping
  (testing "strategy field mapped from result"
    (let [result  (run-one :honest)
          record  (tel/trial->outcome-record "t1" :batch-a base-params result)]
      (is (= :honest (:strategy record)))))

  (testing "malicious strategy propagates"
    (let [result (run-one :malicious)
          record (tel/trial->outcome-record "t2" :batch-a
                   (assoc base-params :strategy :malicious) result)]
      (is (= :malicious (:strategy record)))))

  (testing "required numeric fields are longs"
    (let [record (tel/trial->outcome-record "t3" :batch-a base-params (run-one :honest))]
      (is (int? (:profit-honest record)))
      (is (int? (:profit-malice record)))
      (is (int? (:cm-fee record)))
      (is (int? (:cm-afa record)))))

  (testing "cm-afa reflects escrow amount minus fee"
    (let [result (run-one :honest)
          record (tel/trial->outcome-record "t4" :batch-a base-params result)]
      ;; fee = 10000 * 50 / 10000 = 50; afa = 9950
      (is (= 50   (:cm-fee record)))
      (is (= 9950 (:cm-afa record)))))

  (testing "final-state is a keyword"
    (let [record (tel/trial->outcome-record "t5" :batch-a base-params (run-one :honest))]
      (is (keyword? (:final-state record)))))

  (testing "valid-from is a java.util.Date"
    (let [record (tel/trial->outcome-record "t6" :batch-a base-params (run-one :honest))]
      (is (instance? java.util.Date (:valid-from record)))))

  (testing "batch-id preserved"
    (let [record (tel/trial->outcome-record "t7" :my-batch base-params (run-one :honest))]
      (is (= :my-batch (:batch-id record)))))

  (testing "params stored on record"
    (let [record (tel/trial->outcome-record "t8" :batch-a base-params (run-one :honest))]
      (is (= (:escrow-size base-params) (get-in record [:params :escrow-size]))))))

(deftest test-outcome-record-divergence-fields
  (testing "divergence map from run-with-divergence-check is handled"
    (let [result {:contract  (run-one :honest)
                  :idealized {:profit-honest 50 :profit-malice 50
                              :slashed? false :dispute-correct? true
                              :appeal-triggered? false}
                  :divergence {:divergence? false :diffs []}}
          record  (tel/trial->outcome-record "t9" :batch-a base-params result)]
      (is (false? (:divergence? record)))
      (is (= [] (:diffs record)))))

  (testing "divergence? is true when diffs present"
    (let [fake-div {:divergence? true
                    :diffs [{:field :profit-malice :idealized 50 :contract-model 30}]}
          result   {:contract  (run-one :malicious)
                    :idealized {}
                    :divergence fake-div}
          record   (tel/trial->outcome-record "t10" :batch-a
                     (assoc base-params :strategy :malicious) result)]
      (is (true? (:divergence? record)))
      (is (= 1 (count (:diffs record)))))))

;; ---------------------------------------------------------------------------
;; trial->event-records
;; ---------------------------------------------------------------------------

(deftest test-event-records-shape
  (let [result (run-one :honest)
        events (tel/trial->event-records "t-ev" base-params result)]
    (testing "returns exactly 3 events"
      (is (= 3 (count events))))

    (testing "first event is sew/escrow-created in :pending state"
      (let [e (first events)]
        (is (= :sew/escrow-created (:event-type e)))
        (is (= :pending (:escrow-state e)))))

    (testing "second event is sew/dispute-raised in :disputed state"
      (let [e (second events)]
        (is (= :sew/dispute-raised (:event-type e)))
        (is (= :disputed (:escrow-state e)))))

    (testing "last event has a terminal state"
      (let [e (last events)]
        (is (= :sew/escrow-finalized (:event-type e)))
        (is (#{:released :refunded :resolved} (:escrow-state e)))))

    (testing "events share the same trial-id"
      (is (every? #(= "t-ev" (:trial-id %)) events)))

    (testing "block-times are strictly increasing"
      (let [times (mapv :block-time events)]
        (is (apply < times))))))

;; ---------------------------------------------------------------------------
;; record-trial! with nil ds (no-op)
;; ---------------------------------------------------------------------------

(deftest test-record-trial-nil-ds
  (testing "record-trial! with nil ds returns outcome map"
    (let [result  (run-one :honest)
          outcome (tel/record-trial! nil :batch-a "trial-nil" base-params result)]
      (is (map? outcome))
      (is (= "trial-nil" (:id outcome)))))

  (testing "record-batch! with nil ds returns vector of outcomes"
    (let [trials [{:trial-id "b1" :params base-params :result (run-one :honest)}
                  {:trial-id "b2" :params base-params :result (run-one :malicious)}]
          results (tel/record-batch! nil :batch-x trials)]
      (is (= 2 (count results)))
      (is (every? map? results)))))

;; ---------------------------------------------------------------------------
;; sew-store pure function: summarise-batch
;; ---------------------------------------------------------------------------

(deftest test-summarise-batch
  (let [outcomes [{:trial/strategy :honest   :trial/final-state :released
                   :trial/slashed? false :trial/divergence? false :trial/invariants-ok? true
                   :trial/profit-honest 50 :trial/profit-malice 50}
                  {:trial/strategy :malicious :trial/final-state :refunded
                   :trial/slashed? true  :trial/divergence? true  :trial/invariants-ok? true
                   :trial/profit-honest 50 :trial/profit-malice -150}
                  {:trial/strategy :malicious :trial/final-state :resolved
                   :trial/slashed? false :trial/divergence? false :trial/invariants-ok? false
                   :trial/profit-honest 50 :trial/profit-malice 50}]
        summary (ss/summarise-batch outcomes)]
    (testing "total count"
      (is (= 3 (:n summary))))

    (testing "by-strategy counts"
      (is (= 1 (get-in summary [:by-strategy :honest :n])))
      (is (= 2 (get-in summary [:by-strategy :malicious :n]))))

    (testing "slashed count for malicious"
      (is (= 1 (get-in summary [:by-strategy :malicious :slashed]))))

    (testing "invariant failures counted"
      (is (= 1 (get-in summary [:by-strategy :malicious :invariant-failures]))))

    (testing "by-final-state"
      (is (= 1 (get-in summary [:by-final-state :released])))
      (is (= 1 (get-in summary [:by-final-state :refunded]))))

    (testing "profit-honest mean"
      (is (= 50.0 (get-in summary [:profit-honest :mean]))))

    (testing "profit-malice mean includes negative"
      (let [mean (get-in summary [:profit-malice :mean])]
        (is (< mean 50.0))))))

;; ---------------------------------------------------------------------------
;; sew-store nil-ds read functions
;; ---------------------------------------------------------------------------

(deftest test-store-nil-ds-reads
  (testing "sew-trial-outcomes with nil ds returns []"
    (is (= [] (ss/sew-trial-outcomes nil))))

  (testing "sew-trial-outcomes-at with nil ds returns []"
    (is (= [] (ss/sew-trial-outcomes-at nil (java.util.Date.)))))

  (testing "sew-escrow-events-for-trial with nil ds returns []"
    (is (= [] (ss/sew-escrow-events-for-trial nil "any-id"))))

  (testing "batch-summary with nil ds returns {}"
    (is (= {} (tel/batch-summary nil :any-batch)))))
