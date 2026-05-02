(ns notebooks.serve
  (:require [nextjournal.clerk :as clerk]))

(defn -main [& args]
  (clerk/serve! {:watch-paths ["notebooks"] :browse true}))
