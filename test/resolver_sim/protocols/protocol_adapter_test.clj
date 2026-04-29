(ns resolver-sim.protocols.protocol-adapter-test
  "Verifies the DisputeProtocol adapter layer.

   Tests:
   1. SEWProtocol via replay-with-protocol produces identical outcomes to
      direct replay-scenario across all invariant scenarios.
   2. DummyProtocol runs through scenarios and produces :pass outcomes
      (no invariant violations since the Dummy checks nothing)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.contract-model.replay              :as replay]
            [resolver-sim.protocols.sew.invariant-scenarios :as sc]
            [resolver-sim.protocols.protocol                 :as proto]
            [resolver-sim.protocols.sew                      :as sew]
            [resolver-sim.protocols.dummy                    :as dummy]))

(defn- single-scenario [entry]
  (if (map? entry) entry (first entry)))

(deftest sew-protocol-matches-direct-replay
  "replay-with-protocol using SEWProtocol must produce identical results to
   direct replay-scenario for every invariant scenario — same outcome,
   same event count, same metrics."
  (testing "all scenarios"
    (doseq [[name entry] sc/all-scenarios]
      (let [scenario (single-scenario entry)
            direct   (replay/replay-scenario scenario)
            via-prot (replay/replay-with-protocol sew/protocol scenario)]
        (is (= (:outcome direct) (:outcome via-prot))
            (str name ": outcome mismatch — direct=" (:outcome direct)
                 " via-protocol=" (:outcome via-prot)))
        (is (= (:events-processed direct) (:events-processed via-prot))
            (str name ": events-processed mismatch — direct=" (:events-processed direct)
                 " via-protocol=" (:events-processed via-prot)))
        (is (= (:metrics direct) (:metrics via-prot))
            (str name ": metrics mismatch"))))))

(deftest dummy-protocol-passes-scenarios
  "DummyProtocol (no invariant enforcement) must complete without crash for
   every invariant scenario.  Outcome is never :invalid — the generic kernel
   machinery (alias resolution, metrics, trace shape) must work independently
   of SEW semantics."
  (testing "all scenarios complete without structural failure"
    (doseq [[name entry] sc/all-scenarios]
      (let [scenario (single-scenario entry)
            result   (replay/replay-with-protocol dummy/protocol scenario)]
        (is (#{:pass :fail} (:outcome result))
            (str name ": unexpected outcome " (:outcome result)))
        (is (not= :invalid (:outcome result))
            (str name ": structural failure — " (:halt-reason result)))))))

(deftest protocol-interface-is-satisfied
  "Both SEWProtocol and DummyProtocol must fully satisfy DisputeProtocol."
  (is (satisfies? proto/DisputeProtocol sew/protocol))
  (is (satisfies? proto/DisputeProtocol dummy/protocol)))
