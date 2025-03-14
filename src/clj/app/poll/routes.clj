(ns app.poll.routes
  (:require
   [app.layout :as layout]
   [app.poll.views :as view]
   [app.queries :as q]
   [app.util.http :as http.util]
   [ctmx.core :as ctmx]
   [app.datomic.shim :as d]))

(defn polls-detail []
  (ctmx/make-routes
   "/poll/{poll-id}"
   (fn [req]
     (layout/app-shell req
                       (view/poll-detail-page req)))))

(defn polls-new-routes []
  (ctmx/make-routes
   "/polls/new"
   (fn [req]
     (layout/app-shell req
                       (view/polls-create-page req)))))
(defn polls-index []
  (ctmx/make-routes
   "/polls"
   (fn [req]
     (layout/app-shell req
                       (view/polls-index-page req)))))
(def polls-interceptors [{:name ::polls--interceptor
                          :enter (fn [ctx]
                                   (let [conn (-> ctx :request :datomic-conn)
                                         db (d/db conn)
                                         poll-id (http.util/path-param-uuid! (:request ctx) :poll-id)
                                         poll (q/retrieve-poll db poll-id)]
                                     (if poll
                                       (assoc-in ctx [:request :poll] poll)
                                       (throw (ex-info "Poll not found" {:app/error-type :app.error.type/not-found
                                                                         :poll/poll-id poll-id})))))}])

(defn routes []
  ["" {:app.route/name :app/polls}
   ["" {:interceptors  polls-interceptors}
    (polls-detail)]
   (polls-new-routes)
   (polls-index)])
