(ns resolver-sim.sim.waterfall
  "Waterfall stress testing for coverage adequacy
   
   The waterfall mechanism is a three-phase slashing order that protects
   the system when resolver bonds are depleted:
   
   Phase 1: Slash resolver's own bond first (50% per slash, 100% per epoch cap)
   Phase 2: If exhausted, slash senior's coverage pool (10% per epoch cap)
   Phase 3: If coverage exhausted, unmet obligations tracked
   
   This module simulates the waterfall under various stress conditions to
   determine minimum senior coverage requirements.")

(defn calculate-slash-amount
  "Calculate actual slash amount given bond and slash rate (in basis points)
   
   Applies per-slash cap (50% of bond) and ensures non-negative.
   
   Args:
     bond-amount: Resolver's bond in stables
     slash-rate-bps: Slash rate in basis points (e.g., 50 = 0.5%)
   
   Returns: Amount to slash (before waterfall)"
  [bond-amount slash-rate-bps]
  (let [per-slash-cap (/ bond-amount 2.0)  ; 50% per-slash limit
        calc-amount (* bond-amount (/ slash-rate-bps 10000.0))]
    (double (min per-slash-cap calc-amount))))

(defn apply-junior-slash
  "Slash resolver's own bond (Phase 1)
   
   Returns: {:actually-slashed amount-taken
             :shortage amount-not-covered
             :new-resolver updated-resolver-state}"
  [resolver slash-amount]
  (let [bond-before (:bond-remaining resolver 0)
        amount-slashed (min bond-before slash-amount)
        shortage (- slash-amount amount-slashed)
        bond-after (max 0 (- bond-before amount-slashed))
        is-exhausted (zero? bond-after)]
    {:actually-slashed (double amount-slashed)
     :shortage (double shortage)
     :new-resolver (-> resolver
                       (assoc :bond-remaining (double bond-after))
                       (assoc :is-exhausted? is-exhausted)
                       (update :slash-history (fnil conj [])
                               {:phase :junior
                                :amount (double amount-slashed)
                                :epoch (:current-epoch resolver 0)}))}))

(defn calculate-available-coverage
  "Calculate available coverage for a senior
   
   Available = (bond × utilization) - reserved-for-juniors
   
   Args:
     senior: Senior resolver state with:
       :bond-amount - Senior's bond
       :utilization-factor - % of bond available for coverage (e.g. 0.5)
       :coverage-used - Already-allocated coverage
   
   Returns: Available coverage amount"
  [senior]
  (let [max-coverage (* (:bond-amount senior 0)
                       (:utilization-factor senior 0.5))
        used (:coverage-used senior 0)
        available (max 0 (- max-coverage used))]
    (double available)))

(defn apply-senior-slash
  "Slash senior's coverage pool (Phase 2)
   
   Only triggered if junior's bond was insufficient.
   Applies 10% per-epoch cap (vs. 20% for juniors).
   
   Returns: {:senior-slashed amount-taken
             :unmet-obligation amount-not-covered}"
  [senior shortage]
  (let [available (calculate-available-coverage senior)
        ;; Senior per-epoch cap is 10% of bond
        max-per-epoch (* (:bond-amount senior 0) 0.10)
        can-slash (min available max-per-epoch shortage)
        unmet (- shortage can-slash)
        new-used (+ (:coverage-used senior 0) can-slash)]
    {:senior-slashed (double can-slash)
     :unmet-obligation (double unmet)
     :new-senior (-> senior
                     (assoc :coverage-used (double new-used))
                     (update :slash-history (fnil conj [])
                             {:phase :senior
                              :amount (double can-slash)
                              :epoch (:current-epoch senior 0)}))}))

(defn apply-waterfall-slash
  "Execute complete three-phase waterfall slash
   
   1. Slash junior's bond first (up to 50% per slash)
   2. If insufficient, slash senior's coverage (up to 10% per epoch)
   3. Track unmet obligations for reporting
   
   Args:
     junior: Junior resolver state
     senior: Senior resolver state (or nil if not delegated)
     slash-amount: Total amount to slash
   
   Returns:
     {:junior updated-junior
      :senior updated-senior (or nil)
      :junior-paid amount-slashed-from-junior
      :senior-paid amount-slashed-from-senior
      :unmet-obligation amount-not-covered
      :phases-executed [phase1 phase2 phase3]}"
  [junior senior slash-amount]
  (let [;; Phase 1: Slash junior
        phase1 (apply-junior-slash junior slash-amount)
        junior-slashed (:actually-slashed phase1)
        shortage (:shortage phase1)
        
        ;; Phase 2: Slash senior if needed (and available)
        phase2 (if (and (> shortage 0) senior)
                 (apply-senior-slash senior shortage)
                 {:senior-slashed 0
                  :unmet-obligation shortage
                  :new-senior senior})
        
        senior-slashed (:senior-slashed phase2)
        unmet (:unmet-obligation phase2)]
    
    {:junior (:new-resolver phase1)
     :senior (:new-senior phase2)
     :junior-paid (double junior-slashed)
     :senior-paid (double senior-slashed)
     :unmet-obligation (double unmet)
     :phases-executed [:phase1 :phase2]}))

(defn process-slash-event
  "Process a single slash event through the waterfall
   
   Args:
     event: {:resolver-id string
             :slash-amount double
             :reason keyword (e.g., :fraud, :timeout, :repeat)}
     resolvers: map of {resolver-id -> resolver-state}
     seniors: map of {senior-id -> senior-state}
   
   Returns:
     {:resolvers updated-map
      :seniors updated-map
      :event-result {:junior-paid double
                     :senior-paid double
                     :unmet double}
      :metrics {...}}"
  [event resolvers seniors]
  (let [resolver-id (:resolver-id event)
        resolver (get resolvers resolver-id)
        senior-id (:senior-id event)  ; May be nil
        senior (when senior-id (get seniors senior-id))]
    
    (if (nil? resolver)
      ;; Resolver not found - shouldn't happen
      {:resolvers resolvers
       :seniors seniors
       :event-result {:junior-paid 0
                      :senior-paid 0
                      :unmet (:slash-amount event)
                      :error :resolver-not-found}}
      
      ;; Process waterfall slash
      (let [result (apply-waterfall-slash
                     (assoc resolver :current-epoch (:epoch event 0))
                     (when senior (assoc senior :current-epoch (:epoch event 0)))
                     (:slash-amount event))
            
            updated-resolvers (assoc resolvers resolver-id (:junior result))
            updated-seniors (if senior
                             (assoc seniors senior-id (:senior result))
                             seniors)]
        
        {:resolvers updated-resolvers
         :seniors updated-seniors
         :event-result {:junior-paid (:junior-paid result)
                       :senior-paid (:senior-paid result)
                       :unmet-obligation (:unmet-obligation result)
                       :reason (:reason event)
                       :resolver-id resolver-id
                       :senior-id senior-id}}))))

(defn aggregate-waterfall-metrics
  "Aggregate waterfall metrics across all slashing events
   
   Args:
     resolvers: Final resolver state map
     seniors: Final senior state map
     events: All slash events processed
   
   Returns:
     {:juniors-exhausted-count int
      :juniors-exhausted-pct double
      :avg-junior-bond-remaining double
      :seniors-coverage-used-avg-pct double
      :seniors-at-capacity-count int
      :total-slashes int
      :total-slashed-by-junior double
      :total-slashed-by-senior double
      :total-unmet-obligation double
      :waterfall-saturation-pct double
      :coverage-adequacy-score double}"
  [resolvers seniors events]
  
  (let [junior-resolvers (filter #(not (:is-senior? (val %))) resolvers)
        senior-resolvers (filter #(:is-senior? (val %)) seniors)
        
        ;; Junior stats
        n-juniors (count junior-resolvers)
        exhausted (count (filter #(:is-exhausted? (val %)) junior-resolvers))
        avg-bond-remaining (if (empty? junior-resolvers)
                            0.0
                            (double (/ (reduce + (map #(:bond-remaining (val %) 0) junior-resolvers))
                                      (count junior-resolvers))))
        
        ;; Senior stats
        total-seniors (count senior-resolvers)
        coverage-usages (map #(let [senior (val %)
                                    available (calculate-available-coverage senior)
                                    max-coverage (* (:bond-amount senior 0) 
                                                   (:utilization-factor senior 0.5))
                                    used (:coverage-used senior 0)]
                               (if (zero? max-coverage) 0.0
                                 (/ used max-coverage)))
                           senior-resolvers)
        avg-coverage-pct (if (empty? coverage-usages)
                          0.0
                          (double (* 100.0 (/ (reduce + coverage-usages) (count coverage-usages)))))
        at-capacity (count (filter #(>= % 0.95) coverage-usages))
        
        ;; Event stats
        total-events (count events)
        slashed-by-junior (reduce + (map #(:junior-paid %) events))
        slashed-by-senior (reduce + (map #(:senior-paid %) events))
        total-unmet (reduce + (map #(:unmet-obligation %) events))
        
        ;; Derived metrics
        total-slashed (+ slashed-by-junior slashed-by-senior)
        waterfall-saturation (if (zero? total-slashed)
                              0.0
                              (double (* 100.0 (/ slashed-by-senior total-slashed))))
        adequacy (if (zero? total-events)
                  100.0
                  (double (* 100.0 (- 1.0 (/ total-unmet total-slashed)))))]
    
    {:juniors-exhausted-count exhausted
     :juniors-exhausted-pct (if (zero? n-juniors)
                              0.0
                              (double (* 100.0 (/ exhausted n-juniors))))
     :avg-junior-bond-remaining avg-bond-remaining
     :seniors-coverage-used-avg-pct avg-coverage-pct
     :seniors-at-capacity-count at-capacity
     :total-slashes total-events
     :total-slashed-by-junior slashed-by-junior
     :total-slashed-by-senior slashed-by-senior
     :total-unmet-obligation total-unmet
     :waterfall-saturation-pct waterfall-saturation
     :coverage-adequacy-score adequacy}))

(defn initialize-waterfall-pool
  "Initialize senior and junior resolver pools for waterfall stress testing
   
   Args:
     params: {:n-seniors int
              :n-juniors-per-senior int
              :senior-bond-amount double
              :junior-bond-amount double
              :coverage-multiplier double
              :utilization-factor double}
   
   Returns:
     {:seniors {senior-id -> state}
      :juniors {junior-id -> state}}"
  [params]
  (let [n-seniors (:n-seniors params 5)
        n-juniors-per-senior (:n-juniors-per-senior params 10)
        senior-bond (:senior-bond-amount params 100000)
        junior-bond (:junior-bond-amount params 500)
        util-factor (:utilization-factor params 0.5)]
    
    {:seniors (into {}
                (for [i (range n-seniors)]
                  [(str "s" i)
                   {:resolver-id (str "s" i)
                    :is-senior? true
                    :bond-amount (double senior-bond)
                    :utilization-factor (double util-factor)
                    :coverage-used 0.0
                    :slash-history []
                    :epoch 0}]))
     
     :juniors (into {}
                (for [s (range n-seniors)
                      j (range n-juniors-per-senior)]
                  [(str "j" s "_" j)
                   {:resolver-id (str "j" s "_" j)
                    :is-senior? false
                    :bond-remaining (double junior-bond)
                    :senior-delegation (str "s" s)
                    :is-exhausted? false
                    :slash-history []
                    :epoch 0}]))}))

(comment
  ;; Example usage:
  (let [params {:n-seniors 5
                :n-juniors-per-senior 10
                :senior-bond-amount 100000
                :junior-bond-amount 500
                :utilization-factor 0.5}
        
        {:keys [seniors juniors]} (initialize-waterfall-pool params)
        
        event {:resolver-id "j0_0"
               :senior-id "s0"
               :slash-amount 50
               :reason :fraud
               :epoch 1}]
    
    ;; Process single event
    (process-slash-event event juniors seniors)
    
    ;; Calculate metrics
    (aggregate-waterfall-metrics juniors seniors [event])))
