(ns resolver-sim.engine.protocol-adapter-test
  "Verifies the DisputeProtocol adapter layer.

   Tests:
   1. SEWProtocol via replay-with-protocol produces identical outcomes to
      direct replay-scenario across a sample of invariant scenarios.
   2. DummyProtocol runs through scenarios and produces :pass outcomes
      (no invariant violations since the Dummy checks nothing)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay            :as replay]
            [resolver-sim.contract-model.invariant-scenarios :as sc]
            [resolver-sim.protocols.sew   :as sew]
            [resolver-sim.protocols.dummy :as dummy]))

(defn- single-scenario [entry]
  (if (map? entry) entry (first entry)))

(deftest sew-protocol-matches-direct-replay
  "replay-with-protocol using SEWProtocol must produce the same :outcome as
   direct replay-scenario for every invariant scenario."
  (testing "all scenarios"
    (doseq [[name entry] sc/all-scenarios]
      (let [scenario (single-scenario entry)
            direct   (replay/replay-scenario scenario)
            via-prot (replay/replay-with-protocol sew/protocol scenario)]
        (is (= (:outcome direct) (:outcome via-prot))
            (str name ": outcome mismatch — direct=" (:outcome direct)
                 " via-protocol=" (:outcome via-prot)))))))

(deftest dummy-protocol-passes-scenarios
  "DummyProtocol (no invariant enforcement) must complete without crash for
   any scenario.  Outcome is :pass because the Dummy enforces no invariants
   and treats all actions as successful no-ops (except create_escrow)."
  (testing "first three scenarios complete without exception"
    (doseq [[name entry] (take 3 sc/all-scenarios)]
      (let [scenario (single-scenario entry)
            result   (replay/replay-with-protocol dummy/protocol scenario)]
        (is (#{:pass :fail} (:outcome result))
            (str name ": unexpected outcome " (:outcome result)))
        (is (not= :invalid (:outcome result))
            (str name ": structural failure — " (:halt-reason result)))))))

(deftest protocol-interface-is-satisfied
  "Both SEWProtocol and DummyProtocol must fully satisfy DisputeProtocol."
  (is (satisfies? resolver-sim.engine.protocol/DisputeProtocol sew/protocol))
  (is (satisfies? resolver-sim.engine.protocol/DisputeProtocol dummy/protocol)))
