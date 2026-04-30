(ns resolver-sim.scenario.expectations-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.scenario.expectations :as expectations]))

;; Unit tests for evaluate-invariants 3-tier fallback logic
;; These tests use synthetic result maps with no replay needed.

(deftest test-empty-named-invariants
  (testing "Empty invariants list returns success"
    (let [result {:metrics {:invariant-violations 0 :invariant-results {}}}
          r (expectations/evaluate-invariants result [])]
      (is (= true (:ok? r)))
      (is (= [] (:violations r))))))

(deftest test-invariant-present-in-results-map
  (testing "Named invariant present in per-invariant map → precise :fail"
    (let [result {:metrics {:invariant-violations 1
                            :invariant-results {:conservation-of-funds :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds])]
      (is (= false (:ok? r)))
      (is (= 1 (count (:violations r))))
      (is (= :conservation-of-funds (get-in (:violations r) [0 :invariant])))
      (is (= "per-invariant result: fail" (get-in (:violations r) [0 :note]))))))

(deftest test-invariant-not-in-map-violations-zero
  (testing "Named NOT in map, but :invariant-violations = 0 → :pass"
    (let [result {:metrics {:invariant-violations 0
                            :invariant-results {}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds])]
      (is (= true (:ok? r)))
      (is (= [] (:violations r))))))

(deftest test-invariant-not-in-map-violations-gt-zero
  (testing "Named NOT in map, but :invariant-violations > 0 → conservative :fail"
    (let [result {:metrics {:invariant-violations 2
                            :invariant-results {:solvency :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds])]
      (is (= false (:ok? r)))
      (is (= 1 (count (:violations r))))
      (is (= :conservation-of-funds (get-in (:violations r) [0 :invariant])))
      (is (clojure.string/includes? (get-in (:violations r) [0 :note])
                                    "aggregate fallback")))))

(deftest test-multiple-invariants-one-failing
  (testing "Multiple invariants queried; only one fails in the map, other not in map but agg > 0 → both fail conservatively"
    (let [result {:metrics {:invariant-violations 1
                            :invariant-results {:conservation-of-funds :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds :solvency])]
      (is (= false (:ok? r)))
      ;; conservation-of-funds is in the map (precise fail)
      ;; solvency is NOT in the map, but agg > 0 so fails conservatively
      (is (= 2 (count (:violations r)))))))

(deftest test-multiple-invariants-both-in-map
  (testing "Multiple invariants both in the fail map → both fail"
    (let [result {:metrics {:invariant-violations 2
                            :invariant-results {:conservation-of-funds :fail
                                                :solvency :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds :solvency])]
      (is (= false (:ok? r)))
      (is (= 2 (count (:violations r)))))))

(deftest test-multiple-invariants-one-pass-one-fail-in-map
  (testing "One invariant fails in the map, other not in map but agg > 0 → both fail"
    (let [result {:metrics {:invariant-violations 1
                            :invariant-results {:conservation-of-funds :fail}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds :solvency])]
      ;; conservation-of-funds is in the map → precise fail
      ;; solvency is NOT in the map, but agg > 0 → conservative fail
      (is (= false (:ok? r)))
      (is (= 2 (count (:violations r)))))))

(deftest test-string-invariant-name-converted-to-keyword
  (testing "String invariant names are converted to keywords"
    (let [result {:metrics {:invariant-violations 1
                            :invariant-results {:conservation-of-funds :fail}}}
          r (expectations/evaluate-invariants result ["conservation-of-funds"])]
      (is (= false (:ok? r)))
      (is (= :conservation-of-funds (get-in (:violations r) [0 :invariant]))))))

(deftest test-invariant-pass-when-violations-zero
  (testing "All invariants pass when violations = 0"
    (let [result {:metrics {:invariant-violations 0
                            :invariant-results {}}}
          r (expectations/evaluate-invariants result [:conservation-of-funds :solvency :time-lock-integrity])]
      (is (= true (:ok? r)))
      (is (= [] (:violations r))))))
