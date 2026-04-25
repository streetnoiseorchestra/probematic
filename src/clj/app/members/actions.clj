(ns app.members.actions
  (:require
   [app.members.detail.actions :as detail]
   [app.members.index.actions :as index]
   [app.members.invite.actions :as invite]))

(def actions
  (merge index/actions
         invite/actions
         detail/actions))
