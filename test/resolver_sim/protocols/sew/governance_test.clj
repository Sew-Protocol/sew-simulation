(ns resolver-sim.protocols.sew.governance-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types      :as t]
            [resolver-sim.protocols.sew.lifecycle  :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry   :as reg]))

(deftest governance-sandwich-test
  (let [world (t/empty-world 1000)
        buyer "0xBuyer"
        seller "0xSeller"
        honest-r "0xHonestRes"
        malicious-r "0xMaliciousRes"
        gov "0xGov"
        token "0xToken"
        snap (t/make-module-snapshot {:dispute-resolver honest-r 
                                      :escrow-fee-bps 50})
        
        ;; Register stakes
        world (-> world
                  (reg/register-stake honest-r 1000)
                  (reg/register-stake malicious-r 1000))
        
        ;; 1. Create Escrow
        {:keys [world workflow-id]} (lc/create-escrow world buyer token seller 1000 {} snap)
        
        ;; 2. Raise Dispute
        world (-> (lc/raise-dispute world workflow-id buyer) :world)]

    (testing "Governance rotates resolver mid-dispute"
      (let [r-rot (res/rotate-dispute-resolver world workflow-id malicious-r)
            world-rot (:world r-rot)]
        (is (true? (:ok r-rot)))
        (is (= malicious-r (get-in world-rot [:escrow-transfers workflow-id :dispute-resolver])))
        
        (testing "Malicious resolver resolves the dispute"
          (let [r-final (res/execute-resolution world-rot workflow-id malicious-r false "h1" nil)
                world-final (:world r-final)]
            (is (true? (:ok r-final)))
            (is (= :refunded (t/escrow-state world-final workflow-id)))))))))
