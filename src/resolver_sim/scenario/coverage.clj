(ns resolver-sim.scenario.coverage
  "Coverage report for CDRS v1.1 scenario metadata.

   Scans trace files from disk, extracts :schema-version, :purpose, :threat-tags
   and :id, then produces a structured coverage map. The manifest is derived from
   scenario files — not maintained by hand.

   Two entry points:
     (scan-traces dir)        — load metadata from all .trace.json files in dir
     (coverage-report dir)    — scan + aggregate in one call

   The aggregation functions (group-by-purpose, group-by-threat-tag, etc.) are
   pure and accept a seq of metadata maps, so they can be tested independently."
  (:require [clojure.java.io  :as io]
            [clojure.data.json :as json]
            [clojure.string    :as str]))

;; ---------------------------------------------------------------------------
;; Directory scan
;; ---------------------------------------------------------------------------

(defn- safe-keyword
  "Convert a value to a keyword if it is a non-empty string, else return nil."
  [v]
  (cond
    (keyword? v) v
    (and (string? v) (seq v)) (keyword v)
    :else nil))

(defn- read-trace-metadata
  "Read only the metadata header fields from a .trace.json file.
   Returns nil if the file cannot be parsed."
  [file]
  (try
    (with-open [r (io/reader file)]
      (let [raw (json/read r :key-fn keyword)
            purpose     (safe-keyword (:purpose raw))
            threat-tags (mapv safe-keyword (get raw :threat-tags []))]
        {:file           (.getName file)
         :path           (.getPath file)
         :id             (or (:id raw) (keyword (str/replace (.getName file) #"\.trace\.json$" "")))
         :title          (or (:title raw) "")
         :schema-version (or (str (:schema-version raw)) "unknown")
         :purpose        purpose
         :threat-tags    (filterv some? threat-tags)}))
    (catch Exception _
      nil)))

(defn scan-traces
  "Scan a directory for .trace.json files and return a vector of metadata maps.
   Files that cannot be parsed are silently skipped.

   Each map contains:
     :file           — filename (not full path)
     :path           — full path
     :id             — :id from scenario, or filename-derived keyword
     :title          — :title if present, else empty string
     :schema-version — \"1.0\", \"1.1\", etc.
     :purpose        — keyword or nil
     :threat-tags    — vector of keywords (may be empty)"
  [dir]
  (let [d (io/file dir)]
    (if (.isDirectory d)
      (->> (file-seq d)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".trace.json"))
           (sort-by #(.getPath %))
           (keep read-trace-metadata)
           vec)
      [])))

;; ---------------------------------------------------------------------------
;; Pure aggregation
;; ---------------------------------------------------------------------------

(defn- group-ids-by [key-fn scenarios]
  (->> scenarios
       (group-by key-fn)
       (reduce-kv (fn [m k vs] (assoc m k (mapv :id vs))) {})))

(defn- threat-tag-frequency [scenarios]
  (->> scenarios
       (mapcat :threat-tags)
       frequencies
       (sort-by (comp - val))
       (into {})))

(defn aggregate
  "Build a coverage report map from a seq of metadata maps (as returned by scan-traces).

   Returns:
     :total             — total scenario count
     :schema-versions   — {version-str count}
     :by-purpose        — {purpose [id ...]}  (:unclassified for nil purpose)
     :by-threat-tag     — {tag [id ...]}
     :threat-tag-freq   — {tag count} sorted by frequency desc
     :unclassified-count — count of scenarios with no :purpose
     :scenarios         — full metadata seq"
  [scenarios]
  (let [classified   (filter :purpose scenarios)
        unclassified (remove :purpose scenarios)
        by-purpose   (-> (group-ids-by :purpose classified)
                         (assoc :unclassified (mapv :id unclassified)))
        by-version   (->> (group-by :schema-version scenarios)
                          (reduce-kv (fn [m k vs] (assoc m k (count vs))) {}))]
    {:total              (count scenarios)
     :schema-versions    by-version
     :by-purpose         by-purpose
     :by-threat-tag      (group-ids-by identity
                           (for [s scenarios t (:threat-tags s)]
                             (assoc s :id (:id s) :_tag t)))
     :threat-tag-freq    (threat-tag-frequency scenarios)
     :unclassified-count (count unclassified)
     :scenarios          scenarios}))

;; ---------------------------------------------------------------------------
;; Combined entry point
;; ---------------------------------------------------------------------------

(defn coverage-report
  "Scan dir for .trace.json files and return an aggregated coverage map.
   Uses the default traces directory when called with no arguments."
  ([]
   (coverage-report "data/fixtures/traces"))
  ([dir]
   (-> dir scan-traces aggregate (assoc :scanned-dir dir))))
