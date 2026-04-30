(ns resolver-sim.io.trace-metadata
  "Deprecated: use resolver-sim.protocols.sew.trace-metadata instead.
   This namespace re-exports all vars from the canonical location for backward compatibility."
  (:require [resolver-sim.protocols.sew.trace-metadata]))

(doseq [[sym v] (ns-publics 'resolver-sim.protocols.sew.trace-metadata)]
  (intern *ns* sym v))
