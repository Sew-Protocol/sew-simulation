(ns resolver-sim.io.trace-metadata
  "Deprecated: use resolver-sim.contract-model.trace-metadata instead.
   This namespace re-exports all vars from the canonical location for backward compatibility."
  (:require [resolver-sim.contract-model.trace-metadata]))

(doseq [[sym v] (ns-publics 'resolver-sim.contract-model.trace-metadata)]
  (intern *ns* sym v))
