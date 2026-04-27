(ns migrate-scenarios
  (:require [resolver-sim.contract-model.invariant-scenarios :as sc]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- kw->str [k]
  (if (keyword? k) (name k) (str k)))

(defn save-scenario [name scenario]
  (let [filename (str "fixtures/traces/" (str/replace (str/lower-case name) #"[\s\W]+" "-") ".trace.json")]
    (println "Saving" filename)
    (with-open [w (io/writer filename)]
      (json/write scenario w :key-fn kw->str :value-fn (fn [k v] (if (keyword? v) (name v) v))))))

(doseq [[name entry] sc/all-scenarios]
  (if (vector? entry)
    (doseq [sub entry]
      (save-scenario (:scenario-id sub) sub))
    (save-scenario name entry)))

(println "Migration complete.")
