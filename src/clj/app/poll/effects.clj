(ns app.poll.effects
  (:require
   [app.email :as email]))

(defn send-poll-opened-fx
  [_ {:keys [request]} poll-id]
  (email/send-poll-opened! request poll-id)
  nil)
