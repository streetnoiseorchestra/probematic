(ns app.poll.view-test-support
  (:require
   [app.poll.test-support :as poll-support]
   [app.test-common :as tc]
   [datomic.api :as d]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn tr
  ([path]
   (case path
     [:polls/draft-status] "draft"
     [:polls/open-status] "open"
     [:polls/closed-status] "closed"
     [:polls/single-choice] "single"
     [:polls/multiple-choice] "multiple"
     (name (last path))))
  ([path data]
   (case path
     [:polls/confirm-close-poll] (str "Close " (:title data) "?")
     [:polls/confirm-delete-poll] (str "Delete " (:title data) "?")
     [:polls/select-num-choices] (str (:min data) "–" (:max data))
     (tr path))))

(defn new-system [prefix]
  (tc/new-system prefix))

(defn request
  ([system]
   (request system {}))
  ([{:keys [conn member-id]} extra]
   (merge {::r/router        router
           :current-locale  :en
           :current-member-id member-id
           :db              (d/db conn)
           :page-state      {}
           :system          {:env {}}
           :tr              tr}
          extra)))

(defn seed-poll!
  ([system status]
   (seed-poll! system status {}))
  ([{:keys [conn member-id]} status attrs]
   (poll-support/seed-poll!
    conn
    member-id
    (merge {:poll/poll-status status} attrs))))
