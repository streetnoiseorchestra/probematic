(ns app.songs.actions
  (:require
   [app.songs.detail.actions :as detail]
   [app.songs.edit.actions :as edit]
   [app.songs.index.actions :as index]))

(def actions
  (merge detail/actions
         edit/actions
         index/actions))
