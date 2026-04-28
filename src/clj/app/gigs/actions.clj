(ns app.gigs.actions
  (:require
   [app.gigs.detail.actions :as detail]
   [app.gigs.edit.actions :as edit]
   [app.gigs.probeplan.actions :as probeplan]))

(def actions
  (merge edit/actions
         detail/actions
         probeplan/actions))
