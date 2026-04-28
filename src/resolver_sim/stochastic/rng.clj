(ns resolver-sim.stochastic.rng
  "RNG management and seeding strategy.
   
   Uses SplittableRandom for parallel determinism.
   Each seed splits into N independent streams, one per core.
   Results are deterministic: same seed = same results, regardless of parallelism."
  (:import [java.util SplittableRandom]))

(defn make-rng
  "Create a SplittableRandom from a seed (reproducible)."
  [^long seed]
  (SplittableRandom. seed))

(defn split-rng
  "Split an RNG into two independent streams.
   Both are deterministic; splitting with same RNG yields same pair."
  [^SplittableRandom rng]
  [(.split rng) (.split rng)])

(defn next-long
  "Generate next long from RNG."
  [^SplittableRandom rng]
  (.nextLong rng))

(defn next-double
  "Generate next double [0, 1) from RNG."
  [^SplittableRandom rng]
  (.nextDouble rng))

(defn next-int
  "Generate next int [0, bound) from RNG."
  [^SplittableRandom rng ^long bound]
  (.nextInt rng bound))

(defn seed-from-index
  "Derive a seed from base-seed + index.
   Ensures each parallel trial gets unique but deterministic seed.
   
   Example:
   (seed-from-index 42 0) ; trial 0
   (seed-from-index 42 1) ; trial 1
   Both are deterministic and different."
  [^long base-seed ^long idx]
  (+ base-seed (* idx 1000000007))) ; Large prime ensures good spacing
