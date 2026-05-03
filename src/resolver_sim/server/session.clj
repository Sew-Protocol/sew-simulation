(ns resolver-sim.server.session
  "Stateful session store for the Phase 2 gRPC simulation server.

   Each session owns:
     :world      — canonical world state (pure Clojure map)
     :context    — immutable {:agent-index :snapshot} built at session creation
     :lock       — ReentrantLock; ensures serialised per-session step execution
     :step-count — monotonically increasing counter

   Invariants:
     - Session IDs are caller-supplied strings (typically UUIDs).
     - Duplicate session IDs are rejected.
     - world and context are immutable references; only the :world slot is swapped
       under the session lock after each successful step.
     - Clojure is the sole authority: no state lives outside this store.

   Layering: server/* may import contract_model/*.  Must NOT import db/* or io/*."
  (:require [resolver-sim.protocols.protocol        :as engine]
            [resolver-sim.protocols.sew             :as sew]
            [resolver-sim.contract-model.replay     :as replay])
  (:import [java.util.concurrent.locks ReentrantLock]))

;; ---------------------------------------------------------------------------
;; Session store
;; ---------------------------------------------------------------------------

;; Intentional mutable singleton — the server is stateful by design.
;; defonce ensures a live REPL reload does not drop active sessions.
;; Tests that create sessions must clean up via destroy-session! or
;; reset the atom directly: (reset! #'resolver-sim.server.session/sessions {})
(defonce ^{:private true :doc "Atom: {session-id → {:world :context :lock :step-count}}"}
  sessions
  (atom {}))

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- with-lock
  "Acquire lock, run f (thunk), release lock.  Always releases even on throw."
  [^ReentrantLock lock f]
  (.lock lock)
  (try (f) (finally (.unlock lock))))

(defn- keywordize [m]
  "Recursively convert string keys in a nested map to keywords."
  (cond
    (map? m)    (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) (keywordize v)]) m))
    (vector? m) (mapv keywordize m)
    (seq? m)    (map keywordize m)
    :else       m))

(defn- normalise-agents
  "Accept agents as seq of maps (string or keyword keys) and return keyword-keyed maps.
   Converts :id :address :type to ensure downstream agent-index works correctly."
  [agents]
  (mapv (fn [a]
          (let [m (keywordize a)]
            (cond-> m
              (string? (:id m))      (update :id str)
              (string? (:address m)) (update :address str)
              (string? (:type m))    (update :type str))))
        agents))

(defn- normalise-params
  "Accept protocol-params as a map (string or keyword keys) and return keyword-keyed map."
  [params]
  (if (map? params) (keywordize params) {}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn session-exists?
  "True when session-id is active."
  [session-id]
  (contains? @sessions session-id))

(defn create-session!
  "Create a new session with the given agents and protocol-params.

   session-id       — caller-supplied string (typically a UUID)
   agents           — seq of agent maps {:id :address :type ...} (string keys OK)
   protocol-params  — map of protocol params (string keys OK)
   initial-block-time — initial block timestamp for world0

   Returns {:ok true :session-id sid} or {:ok false :error kw :detail map}.

   Atomicity: uses swap-vals! so that two concurrent create calls for the same
   session-id can never both succeed — one will see the key already present in
   the old value and return :session-already-exists."
  [session-id agents protocol-params initial-block-time]
  (let [agent-list (normalise-agents agents)
        params     (normalise-params protocol-params)
        validation (replay/validate-agents agent-list)]
    (if-not (:ok validation)
      validation
      (let [context (engine/build-execution-context sew/protocol agent-list params)
            world0  (engine/init-world sew/protocol {:initial-block-time initial-block-time})
            session {:world      world0
                     :context    context
                     :lock       (ReentrantLock.)
                     :step-count 0}
            ;; Atomic conditional insert: only insert when key is absent.
            ;; swap-vals! returns [old-map new-map]; if old already had the key
            ;; the swap fn is a no-op and we detect the collision via old-map.
            [old _] (swap-vals! sessions (fn [s]
                                           (if (contains? s session-id)
                                             s
                                             (assoc s session-id session))))]
        (if (contains? old session-id)
          {:ok false :error :session-already-exists :detail {:session-id session-id}}
          {:ok true :session-id session-id})))))

(defn step-session!
  "Execute one event against the session's canonical world state.

   event — map {:seq :time :agent :action :params ...} (keyword keys; string keys OK)

   Returns:
     {:ok true  :step {process-step result}}  — on success (including reverts)
     {:ok false :error kw}                    — when session not found

   The world state is updated atomically under the session lock.
   The lock serialises concurrent calls to the same session.
   Different sessions proceed in parallel (each has its own lock).

   Race safety: after acquiring the lock we re-read the session from the atom.
   If destroy-session! ran and removed the session while we were waiting for the
   lock, we detect the nil and return :session-not-found rather than operating
   on a stale reference.  The final swap! is also guarded so that a destroyed
   session is never resurrected with partial state."
  [session-id event]
  (let [session (get @sessions session-id)]
    (if-not session
      {:ok false :error :session-not-found :detail {:session-id session-id}}
      (with-lock (:lock session)
        (fn []
          ;; Re-read inside the lock — destroy-session! may have removed the
          ;; session while this thread was blocked waiting for the lock.
          (let [current (get @sessions session-id)]
            (if-not current
              {:ok false :error :session-not-found :detail {:session-id session-id}}
              (let [world   (:world current)
                    context (:context session)
                    evt     (keywordize event)
                    step    (replay/process-step sew/protocol context world evt)]
                ;; Guard: only write back if the session still exists.
                ;; Prevents resurrection of a partially-structured map when
                ;; a destroy races with the tail of a step.
                (swap! sessions
                       (fn [s]
                         (if (contains? s session-id)
                           (-> s
                               (assoc-in  [session-id :world] (:world step))
                               (update-in [session-id :step-count] inc))
                           s)))
                {:ok true :step step}))))))))

(defn destroy-session!
  "Remove a session from the store.
   Returns {:ok true} or {:ok false :error :session-not-found}.

   Race safety: acquires the session lock before removing so that any in-progress
   step completes first.  After acquiring the lock, the session is re-checked
   (another concurrent destroy may have already removed it).  Once the remove
   succeeds under the lock, any subsequent step-session! call that was blocked
   on the lock will re-read a nil session and return :session-not-found cleanly."
  [session-id]
  (let [session (get @sessions session-id)]
    (if-not session
      {:ok false :error :session-not-found :detail {:session-id session-id}}
      (with-lock (:lock session)
        (fn []
          ;; Re-check inside the lock: a concurrent destroy may have won the race.
          (if (session-exists? session-id)
            (do (swap! sessions dissoc session-id)
                {:ok true :session-id session-id})
            {:ok false :error :session-not-found :detail {:session-id session-id}}))))))

(defn active-sessions
  "Return a seq of active session IDs. Useful for monitoring."
  []
  (keys @sessions))

(defn session-info
  "Return a lean info map for a session: {:step-count :block-time :escrow-count}.
   Returns nil if session not found."
  [session-id]
  (when-let [s (get @sessions session-id)]
    ;; :escrow-transfers is SEW-specific world state; intentional here since
    ;; session.clj is SEW-wired via sew/protocol.
    {:step-count   (:step-count s)
     :block-time   (get-in s [:world :block-time])
     :escrow-count (count (get-in s [:world :escrow-transfers]))}))

(defn suggest-actions
  "Return lightweight action suggestions for an actor without executing anything.
   This is intentionally advisory to avoid duplicating full protocol transition logic."
  [session-id actor-id]
  (if-let [s (get @sessions session-id)]
    (let [world       (:world s)
          transfers   (get world :escrow-transfers {})
          ids         (vec (keys transfers))
          pending-settlements (get world :pending-settlements {})
          pending-slashes (get world :pending-fraud-slashes {})
          agent-index (get-in s [:context :agent-index] {})
          actor       (get agent-index actor-id)
          actor-addr  (:address actor)
          actor-type  (some-> (:type actor) name)
          governance? (or (= actor-id "governance") (= actor-type "governance"))
          keeper?     (or (= actor-id "keeper") (= actor-type "keeper"))
          resolver?   (or (= actor-id "resolver") (= actor-type "resolver"))
          templates
          (vec
           (concat
            ;; Always legal: can attempt to create a new escrow.
            (when actor
              (let [counterparty (->> agent-index
                                      vals
                                      (remove #(= (:id %) actor-id))
                                      first)]
                [{:actor-id actor-id
                  :action "create_escrow"
                  :params {:token "USDC"
                           :to (or (:address counterparty) "0xseller")
                           :amount 5000}}]))

            ;; State-aware, actor-specific suggestions per workflow.
            (mapcat
             (fn [[wf et]]
               (let [st (:escrow-state et)
                     from (:from et)
                     to   (:to et)
                     disputed? (= st :disputed)
                     resolver-addr (:dispute-resolver et)]
                 (cond
                   ;; Pending: participants may cancel (role-specific) or raise dispute.
                   (= st :pending)
                   (concat
                    (when (= actor-addr from)
                      [{:actor-id actor-id :action "sender_cancel" :params {:id wf}}
                       {:actor-id actor-id :action "release" :params {:id wf}}
                       {:actor-id actor-id :action "raise_dispute" :params {:id wf}}])
                    (when (= actor-addr to)
                      [{:actor-id actor-id :action "recipient_cancel" :params {:id wf}}
                       {:actor-id actor-id :action "raise_dispute" :params {:id wf}}]))

                   ;; Disputed lifecycle candidates.
                   disputed?
                   (concat
                    (when (or (= actor-addr resolver-addr) resolver?)
                      [{:actor-id actor-id
                        :action "execute_resolution"
                        :params {:workflow-id wf :is-release true :resolution-hash "0xadv"}}
                       {:actor-id actor-id
                        :action "execute_resolution"
                        :params {:workflow-id wf :is-release false :resolution-hash "0xadv"}}])
                    (when governance?
                      [{:actor-id actor-id
                        :action "propose_fraud_slash"
                        :params {:workflow-id wf
                                 :resolver-addr (or resolver-addr "0xresolver")
                                 :amount (max 1 (quot (long (or (:amount-after-fee et) 0)) 10))}}])
                    (when keeper?
                      [{:actor-id actor-id
                        :action "execute_pending_settlement"
                        :params {:workflow-id wf}}]))

                   :else
                   [])))
             transfers)

            ;; Governance slash lifecycle suggestions.
            (when governance?
              (mapcat
               (fn [[slash-id slash]]
                 (let [status (:status slash)]
                   (cond
                     (= status :pending)
                     [{:actor-id actor-id :action "execute_fraud_slash" :params {:workflow-id slash-id}}
                      {:actor-id actor-id :action "resolve_appeal" :params {:workflow-id slash-id :upheld? true}}
                      {:actor-id actor-id :action "resolve_appeal" :params {:workflow-id slash-id :upheld? false}}]

                     (= status :appealed)
                     [{:actor-id actor-id :action "resolve_appeal" :params {:workflow-id slash-id :upheld? true}}
                      {:actor-id actor-id :action "resolve_appeal" :params {:workflow-id slash-id :upheld? false}}]

                     :else
                     [])))
               pending-slashes))

            ;; Resolver appeal suggestions for slash lifecycle.
            (when resolver?
              (mapcat
               (fn [[slash-id slash]]
                 (if (= (:status slash) :pending)
                   [{:actor-id actor-id :action "appeal_slash" :params {:workflow-id slash-id}}]
                   []))
               pending-slashes))

            ;; Keeper execution suggestions for existing pending settlements.
            (when keeper?
              (for [[wf pending] pending-settlements
                    :when (:exists pending)]
                {:actor-id actor-id
                 :action "execute_pending_settlement"
                 :params {:workflow-id wf}}))))]
      {:ok true
       :session-id session-id
       :actor-id actor-id
       :active-workflow-ids ids
       :suggested-actions templates})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn session-signals
  "Return read-only risk/economic signals for adversarial strategy search."
  [session-id]
  (if-let [s (get @sessions session-id)]
    (let [world     (:world s)
          transfers (get world :escrow-transfers {})
          pending-slashes (get world :pending-fraud-slashes {})]
      {:ok true
       :session-id session-id
       :block-time (get world :block-time 0)
       :active-workflow-ids (vec (keys transfers))
       :pending-count (get world :pending-count 0)
       :pending-fraud-slashes
       (into {}
             (map (fn [[slash-id v]]
                    [slash-id {:resolver (:resolver v)
                               :amount (get v :amount 0)
                               :status (some-> (:status v) name)
                               :appeal-deadline (get v :appeal-deadline 0)
                               :proposed-at (get v :proposed-at 0)}])
                  pending-slashes))
       :resolver-slash-total (get world :resolver-slash-total {})
       :resolver-frozen-until (get world :resolver-frozen-until {})
       :total-fees (get world :total-fees {})
       :total-held (get world :total-held {})
       :resolver-stakes (get world :resolver-stakes {})})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn evaluate-payoff
  "Return a simple realised payoff projection for actor-id from canonical world.
   Keeps payoff logic in Clojure so Python does not duplicate it."
  [session-id actor-id]
  (if-let [s (get @sessions session-id)]
    (let [world     (:world s)
          stakes    (get world :resolver-stakes {})
          claimable (get world :claimable {})
          bonds     (get world :bond-balances {})
          staked    (get stakes actor-id 0)
          claim     (reduce + 0 (for [[_ wc] claimable] (get wc actor-id 0)))
          bonded    (reduce + 0 (for [[_ wb] bonds] (get wb actor-id 0)))
          net       (+ staked claim bonded)]
      {:ok true
       :session-id session-id
       :actor-id actor-id
       :stake-locked staked
       :slash-loss-realized (get (get world :resolver-slash-total {}) actor-id 0)
       :claimable claim
       :bond-locked bonded
       :net-pnl net})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))

(defn evaluate-attack-objective
  "Evaluate objective-oriented score from canonical world for adversarial search.
   objective: resolver_fraud_profit (default)."
  [session-id actor-id objective]
  (if-let [s (get @sessions session-id)]
    (let [world      (:world s)
          objective' (or objective "resolver_fraud_profit")
          stakes     (get world :resolver-stakes {})
          slashed    (get world :resolver-slash-total {})
          claimable  (get world :claimable {})
          bonds      (get world :bond-balances {})
          staked     (get stakes actor-id 0)
          slash-loss (get slashed actor-id 0)
          claim      (reduce + 0 (for [[_ wc] claimable] (get wc actor-id 0)))
          bonded     (reduce + 0 (for [[_ wb] bonds] (get wb actor-id 0)))
          net-resolver-profit (- (+ staked claim bonded) slash-loss)
          fraud-slash-pending
          (reduce + 0
                  (for [[_ p] (get world :pending-fraud-slashes {})
                        :when (= actor-id (:resolver p))]
                    (get p :amount 0)))
          score (case objective'
                  "resolver_fraud_profit" net-resolver-profit
                  net-resolver-profit)]
      {:ok true
       :session-id session-id
       :actor-id actor-id
       :objective objective'
       :score score
       :decomposition {:stake-locked staked
                       :claimable claim
                       :bond-locked bonded
                       :slash-loss-realized slash-loss
                       :slash-loss-pending fraud-slash-pending
                       :net-resolver-profit net-resolver-profit}})
    {:ok false :error :session-not-found :detail {:session-id session-id}}))
