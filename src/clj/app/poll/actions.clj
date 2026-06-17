(ns app.poll.actions
  (:require
   [app.poll.detail.actions :as detail]
   [app.poll.edit.actions :as edit]))

(def actions
  (merge edit/actions
         detail/actions))
