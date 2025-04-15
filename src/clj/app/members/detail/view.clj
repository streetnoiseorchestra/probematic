(ns app.members.detail.view
  (:require
   [app.datastar :as d*]
   [starfederation.datastar.clojure.expressions :refer [->expr]]
   [app.html :as html]))

(defn page [req]
  (html/->str
   [:main {:class "flex-1" :id "main"}
    "hello"]))

(d*/refresh-all!)
