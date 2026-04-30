(ns resolver-sim.protocols.sew.slashing-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]))

(deftest slashing-logic-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        res "0xRes"
        gov "0xGov"
        snap (t/make-module-snapshot {:appeal-window-duration 86400})]
    
    (testing "Manual slash proposal is appealable"
      (let [world (reg/register-stake world res 1000)
            {:keys [world workflow-id]} (lc/create-escrow world buyer "0xT" seller 1000 {} snap)
            r-prop (res/propose-fraud-slash world workflow-id gov res 500)
            world-prop (:world r-prop)]
        
        (is (= :pending (get-in world-prop [:pending-fraud-slashes workflow-id :status])))
        
        (testing "Resolver appeals"
          (let [r-app (res/appeal-slash world-prop workflow-id res)
                world-app (:world r-app)]
            (is (= :appealed (get-in world-app [:pending-fraud-slashes workflow-id :status])))))
            
        (testing "Governance upholds appeal"
          (let [world-app (-> (res/appeal-slash world-prop workflow-id res) :world)
                r-res (res/resolve-appeal world-app workflow-id gov true)
                world-upheld (:world r-res)]
            (is (= :reversed (get-in world-upheld [:pending-fraud-slashes workflow-id :status])))))))))
