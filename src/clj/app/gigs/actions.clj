(ns app.gigs.actions
  (:require
   [app.gigs.edit.actions :as edit]))

(def actions
  (merge edit/actions))
