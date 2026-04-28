(ns app.gigs.actions
  (:require
   [app.gigs.detail.actions :as detail]
   [app.gigs.edit.actions :as edit]))

(def actions
  (merge edit/actions
         detail/actions))
