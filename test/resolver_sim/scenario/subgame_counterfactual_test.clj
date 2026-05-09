(ns resolver-sim.scenario.subgame-counterfactual-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [resolver-sim.scenario.subgame-counterfactual :as cf]))

(defn -main
  [& _]
  (run-tests 'resolver-sim.scenario.subgame-counterfactual-test))

(deftest evaluate-subgame-counterfactual-basic-pass
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:bond-balances {"e1" {"buyer" 50}}}}
                                {:world {:claimable {"e1" {"buyer" 150}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "escalate_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 0}}
        out (cf/evaluate-subgame-counterfactual projection)]
    (is (= :pass (:status out)))
    (is (= 0 (:max-regret out)))
    (is (= 1 (:checked-nodes out)))))

(deftest evaluate-subgame-counterfactual-basic-fail-and-deterministic
  (let [projection {:raw-trace [{:world {:claimable {"e1" {"buyer" 0}}}}
                                {:world {:bond-balances {"e1" {"buyer" 50}}}}
                                {:world {:claimable {"e1" {"buyer" 0}}}}]
                    :decisions [{:seq 1 :agent "buyer" :action "escalate_dispute"}]
                    :terminal-world {:terminal? true}
                    :spe-config {:regret-threshold 0}}
        out1 (cf/evaluate-subgame-counterfactual projection)
        out2 (cf/evaluate-subgame-counterfactual projection)]
    (is (= :fail (:status out1)))
    (is (= 50 (:max-regret out1)))
    (is (= (:regret-table out1) (:regret-table out2)))))
