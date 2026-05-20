(ns app.gigs.actions
  (:require
   [app.gigs.archive.actions :as archive]
   [app.gigs.detail.actions :as detail]
   [app.gigs.edit.actions :as edit]
   [app.gigs.log-plays.actions :as log-plays]
   [app.gigs.probeplan.actions :as probeplan]
   [app.gigs.setlist.actions :as setlist]))

(def actions
  (merge edit/actions
         archive/actions
         detail/actions
         log-plays/actions
         probeplan/actions
         setlist/actions))
