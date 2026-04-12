(ns resolver-sim.contract-model.diff
  "Canonical world-state hashing and structural diff.

   Used for differential testing: compare Clojure model state step-by-step
   against EVM execution on Anvil.

   Core workflow:
     1. After each replay step, call (world-hash world') to get a SHA-256 digest.
     2. Extract the equivalent minimal state from Anvil via RPC (escrow states,
        dispute levels, balances) and convert to the same canonical structure.
     3. Compare hashes; on mismatch use (diff-worlds sim-world evm-world) to
        locate the first point of divergence.

   Canonical form: all nested maps are key-sorted so pr-str output is
   deterministic regardless of Clojure hash-map insertion order."
  (:require [clojure.data :as data])
  (:import [java.security MessageDigest]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Canonical form
;; ---------------------------------------------------------------------------

(defn- ->sorted-deep
  "Recursively convert all maps to sorted-map.
   Vectors and lists are walked element-by-element; all other values pass through."
  [x]
  (cond
    (map? x)        (into (sorted-map) (map (fn [[k v]] [k (->sorted-deep v)]) x))
    (sequential? x) (mapv ->sorted-deep x)
    :else           x))

(defn canonical-world
  "Return a canonically-ordered copy of world.
   Two worlds with identical logical content always produce identical pr-str."
  [world]
  (->sorted-deep world))

;; ---------------------------------------------------------------------------
;; Hashing
;; ---------------------------------------------------------------------------

(defn world-hash
  "SHA-256 of (pr-str (canonical-world world)), Base64-encoded (no padding).

   Properties:
   - Deterministic: same world → same hash across JVM restarts and runs
   - Collision-resistant: suitable as a per-step checkpoint
   - Comparable: EVM adapter must produce an identical hash from Anvil state
     by mapping contract storage → the same canonical map structure"
  [world]
  (let [s   (pr-str (canonical-world world))
        md  (MessageDigest/getInstance "SHA-256")
        raw (.digest md (.getBytes s "UTF-8"))]
    (.encodeToString (Base64/getEncoder) raw)))

;; ---------------------------------------------------------------------------
;; Structural diff
;; ---------------------------------------------------------------------------

(defn diff-worlds
  "Deep structural diff between two world states.

   Returns nil when worlds are logically identical.
   Otherwise returns:
     {:only-in-a  — keys/values present in world-a but absent or different in world-b
      :only-in-b  — keys/values present in world-b but absent or different in world-a
      :hash-a     — SHA-256 of world-a
      :hash-b     — SHA-256 of world-b}

   Typical usage: diff the Clojure model world against an EVM-reconstructed world
   to find the first divergent field after a state mismatch is detected."
  [world-a world-b]
  (let [[only-a only-b _same] (data/diff (canonical-world world-a)
                                         (canonical-world world-b))]
    (when (or only-a only-b)
      {:only-in-a only-a
       :only-in-b only-b
       :hash-a    (world-hash world-a)
       :hash-b    (world-hash world-b)})))

;; ---------------------------------------------------------------------------
;; EVM state adapter helpers
;; ---------------------------------------------------------------------------

(defn evm-world-skeleton
  "Return the keys that an EVM state adapter must populate to produce a world
   map comparable by (world-hash).

   The Anvil adapter (to be built) must read these fields from contract storage:
     :escrow-transfers    — {wf-id {:escrow-state :amount-after-fee :token ...}}
     :total-held          — {token-addr nat-int}  from EscrowVault.totalHeldPerToken
     :total-fees          — {token-addr nat-int}  from EscrowVault.totalFeesPerToken
     :pending-settlements — {wf-id {:exists :is-release :appeal-deadline}}
     :dispute-levels      — {wf-id nat-int}        from DR module dm.currentRound
     :block-time          — nat-int               from block.timestamp

   Fields that exist in the sim world but have no direct EVM equivalent
   (:escrow-settings, :module-snapshots, :claimable, :dispute-timestamps)
   should be omitted from both sides before comparison by projecting to a
   common subset with (select-keys world comparable-keys).

   See docs/differential-testing.md (to be created) for the full mapping."
  []
  {:escrow-transfers    {}
   :total-held          {}
   :total-fees          {}
   :pending-settlements {}
   :dispute-levels      {}
   :block-time          0})

(defn comparable-keys
  "The world-state keys that have a direct EVM equivalent and should be used
   when comparing model state against Anvil state.

   Use (select-keys world (comparable-keys)) on BOTH sides before hashing to
   avoid false positives from fields that don't exist on-chain."
  []
  #{:escrow-transfers :total-held :total-fees :pending-settlements
    :dispute-levels :block-time})

(defn projection
  "Project world to only the fields that can be compared against EVM state."
  [world]
  (select-keys world (comparable-keys)))

(defn projection-hash
  "SHA-256 of the EVM-comparable projection of world.
   Use this (not world-hash) when comparing against Anvil-derived state."
  [world]
  (world-hash (projection world)))
