(ns resolver-sim.stochastic.calibration-test
  "Cross-engine calibration tests.

   The Monte Carlo engine (stochastic/) and the Replay engine (protocols/sew/) are
   two independent implementations of the SEW economic model. They must agree on
   the numeric formulas for fees, bonds, and slashing — otherwise MC results and
   replay results are incommensurable.

   This file tests every formula that appears in BOTH engines.

   ── KNOWN CALIBRATION GAP (not tested here; documented as a limit) ──────────
   The MC `profit-malice` model is:

       profit-malice = fee - (bond × slash-rate × detection-prob)

   This measures only the RESOLVER'S PROTOCOL INCOME. It omits the malicious
   gain from outcome direction: a resolver who successfully misdirects escrow
   hands the full escrow amount to a colluding party. That gain is:

       fraud-upside = escrow × (1 - fee-rate)          ; if resolver is the beneficiary
                    ≥ escrow × (1 - fee-rate) × bribe   ; if resolver is a hired actor

   For a 10 000 wei escrow with fee-bps=150 (fee=15), the hidden upside is
   ≈9 985 wei — roughly 665× the visible fee. The MC dominance ratio therefore
   understates how attractive fraud is under the model's own assumptions.

   The replay engine captures this via its state-machine invariants (funds-conservation,
   no-double-release, etc.), but the MC engine does not.

   Consequence: MC results establish that PROTOCOL INCOME from honest participation
   exceeds protocol income from malice. They do not establish that the total
   economic payoff from fraud is unattractive. The replay invariant suite is the
   correct evidence source for the latter claim.
   ─────────────────────────────────────────────────────────────────────────────"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.stochastic.economics :as econ]
            [resolver-sim.protocols.sew.types  :as t]
            [resolver-sim.economics.payoffs    :as payoffs]))

;; ── Test grid ────────────────────────────────────────────────────────────────
;; Representative escrow amounts (wei) and fee/bond/slash bps values drawn
;; from actual parameter files in data/params/.

(def ^:private amounts [0 1 999 1000 9999 10000 100000 1000000])
(def ^:private fee-bps-values [100 150 200 500])
(def ^:private bond-bps-values [500 700 1000])
(def ^:private slash-bps-values [200 2500 5000])   ; timeout / reversal / fraud

;; ── 1. Fee formula ───────────────────────────────────────────────────────────

(deftest fee-formula-identity
  (testing "stochastic/calculate-fee == types/compute-fee for all (amount, fee-bps) pairs"
    (doseq [amt amounts
            bps fee-bps-values]
      (is (= (econ/calculate-fee amt bps)
             (t/compute-fee      amt bps))
          (str "fee mismatch at amount=" amt " fee-bps=" bps)))))

;; ── 2. Bond formula ──────────────────────────────────────────────────────────

(deftest bond-formula-identity
  (testing "stochastic/calculate-bond == types/compute-fee with bond-bps for all pairs"
    (doseq [amt amounts
            bps bond-bps-values]
      (is (= (econ/calculate-bond amt bps)
             (t/compute-fee       amt bps))
          (str "bond mismatch at amount=" amt " bond-bps=" bps)))))

;; ── 3. Slash amount formula ───────────────────────────────────────────────────
;; In stochastic/dispute.clj the fraud/reversal branch sets:
;;   effective-slash-multiplier = (/ slash-bps 10000.0)
;;   total-bond-slashing = (calculate-slashing-loss bond effective-slash-multiplier)
;;                       = (* bond (/ slash-bps 10000.0))
;;
;; In payoffs.clj (used by the replay engine):
;;   (calculate-reversal-slash afa reversal-slash-bps) = (compute-fee afa reversal-slash-bps)
;;                                                      = (quot (* afa slash-bps) 10000)
;;
;; These must agree on integer truncation semantics. The stochastic path multiplies
;; by a float then truncates; the replay path uses integer arithmetic throughout.
;; We test that the two paths yield the same value after truncation.

(deftest reversal-slash-formula-identity
  (testing "stochastic slash (via effective-slash-multiplier) == payoffs/calculate-reversal-slash"
    (doseq [amt amounts
            bps slash-bps-values]
      (let [stochastic-slash (long (econ/calculate-slashing-loss amt (/ bps 10000.0)))
            replay-slash     (payoffs/calculate-reversal-slash amt bps)]
        (is (= stochastic-slash replay-slash)
            (str "slash mismatch at amount=" amt " slash-bps=" bps
                 " stochastic=" stochastic-slash " replay=" replay-slash))))))

;; ── 4. Appeal bond fee formula ────────────────────────────────────────────────
;; Both engines use types/compute-fee for the protocol fee on appeal bonds.
;; The stochastic engine does not model bond fee deduction — it uses the gross
;; bond amount. We verify that payoffs/calculate-appeal-bond-fee deducts correctly
;; and is consistent with types/compute-fee.

(deftest appeal-bond-fee-consistency
  (testing "payoffs/calculate-appeal-bond-fee net = amount - types/compute-fee"
    (doseq [amt [100 1000 10000]
            fee-bps [0 50 100 500]]
      (let [{:keys [fee net]} (payoffs/calculate-appeal-bond-fee amt fee-bps)]
        (is (= fee (t/compute-fee amt fee-bps))
            (str "bond-fee mismatch at amount=" amt " fee-bps=" fee-bps))
        (is (= net (- amt fee))
            (str "net mismatch at amount=" amt " fee-bps=" fee-bps))))))

;; ── 5. Slashing distribution adds up ─────────────────────────────────────────
;; payoffs/calculate-slashing-distribution must be lossless (no wei destroyed).
;; insurance + protocol + burned = amount - bounty.

(deftest slashing-distribution-conservation
  (testing "slashing distribution is lossless (no wei destroyed)"
    (doseq [amount [0 100 1000 9999 10000]
            bounty [0 10 50]]
      (when (<= bounty amount)
        (let [{:keys [insurance protocol burned]}
              (payoffs/calculate-slashing-distribution amount bounty)
              total (+ insurance protocol burned)]
          (is (= total (- amount bounty))
              (str "distribution not lossless: amount=" amount
                   " bounty=" bounty
                   " distributed=" total)))))))
