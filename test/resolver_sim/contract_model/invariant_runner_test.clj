(ns resolver-sim.contract-model.invariant-runner-test
  "Smoke tests for the S01–S23 deterministic invariant suite runner."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.invariant-runner :as runner]
            [resolver-sim.contract-model.invariant-scenarios :as sc]))

(deftest test-registry-size
  "all-scenarios must contain exactly 41 entries."
  (is (= 41 (count sc/all-scenarios))))

(deftest test-run-all-41-of-41
  "Every deterministic scenario must pass in-process."
  (let [{:keys [passed total results]} (runner/run-all)]
    (is (= 41 total))
    (is (= passed total))
    (testing "no invariant violations in any scenario"
      (is (every? #(zero? (:violations %)) results)))))

(deftest test-run-all-shape
  "run-all returns the expected summary keys."
  (let [summary (runner/run-all)]
    (is (contains? summary :passed))
    (is (contains? summary :total))
    (is (contains? summary :elapsed-ms))
    (is (contains? summary :results))
    (is (every? #(contains? % :name) (:results summary)))))

(deftest test-print-report-exit-code
  "print-report returns 0 when all scenarios pass."
  (let [summary (runner/run-all)
        code    (runner/print-report summary)]
    (is (= 0 code))))
