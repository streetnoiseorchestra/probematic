(ns app.settings.actions
  (:require
   [app.settings.discounts.actions :as discounts]
   [app.settings.sections.actions :as sections]
   [app.settings.teams.actions :as teams]))

(def actions
  (merge discounts/actions
         teams/actions
         sections/actions))
