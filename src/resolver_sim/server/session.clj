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
  (:require [resolver-sim.contract-model.types  :as t]
            [resolver-sim.contract-model.replay :as replay])
  (:import [java.util.concurrent.locks ReentrantLock]))

;; ---------------------------------------------------------------------------
;; Session store
;; ---------------------------------------------------------------------------

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
      (let [context (replay/build-context agent-list params)
            world0  (t/empty-world initial-block-time)
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
                    step    (replay/process-step context world evt)]
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
    {:step-count   (:step-count s)
     :block-time   (get-in s [:world :block-time])
     :escrow-count (count (get-in s [:world :escrow-transfers]))}))
