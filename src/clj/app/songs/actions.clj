(ns app.songs.actions
  (:require
   [app.songs.edit.actions :as edit]
   [app.songs.index.actions :as index]))

(def actions
  (merge edit/actions
         index/actions))
