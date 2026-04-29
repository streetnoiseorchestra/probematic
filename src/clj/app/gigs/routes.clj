(ns app.gigs.routes
  (:require
   [app.config :as config]
   [app.datomic.shim :as d]
   [app.gigs.answer-link.views :as answer-link]
   [app.gigs.archive.views]
   [app.gigs.detail.views]
   [app.gigs.edit.views]
   [app.gigs.index.views]
   [app.gigs.log-plays.views]
   [app.gigs.probeplan.views]
   [app.gigs.setlist.views]
   [app.gigs.views :as view]
   [app.layout :as layout]
   [app.queries :as q]
   [app.routes.datastar :as ds]
   [app.util.http :as http.util]
   [ctmx.core :as ctmx]))

(defn gig-create-route []
  (ctmx/make-routes
   "/new"
   (fn [req]
     (layout/app-shell req (view/gig-create-page req)))))

(defn gig-log-play-route []
  (ctmx/make-routes
   "/{gig/gig-id}/log-play"
   (fn [req]
     (layout/app-shell req
                       (view/gig-log-plays req)))))

(def gigs-interceptors {:name ::gigs--interceptor
                        :enter (fn [ctx]
                                 (let [conn (-> ctx :request :datomic-conn)
                                       _ (assert conn "datomic connection not available")
                                       db (d/db conn)
                                       gig-id (http.util/path-param-uuid! (:request ctx) :gig/gig-id)
                                       gig (q/retrieve-gig db gig-id)]
                                   (if gig
                                     (assoc-in ctx [:request :gig] gig)
                                     (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                                                      :gig/gig-id gig-id})))))})

(defn gigs-list-route []
  (ctmx/make-routes
   ""
   (fn [req]
     (layout/app-shell req
                       (view/gigs-list-page req)))))
(defn gigs-archive-route []
  (ctmx/make-routes
   "/archive"
   (fn [req]
     (layout/app-shell req
                       (view/gigs-archive-page req)))))

(defn gig-detail-route []
  (ctmx/make-routes
   "/{gig/gig-id}/"
   (fn [req]
     (layout/app-shell req (view/gig-detail-page req false)))))

(defn routes []
  ["" {:app.route/name :app/gigs}
   (ds/page-routes {:page-name ::index
                    :path      "/gigs"
                    :view-ns   'app.gigs.index.views})
   (ds/page-routes {:page-name ::archive
                    :path      "/gigs/archive"
                    :view-ns   'app.gigs.archive.views})
   (ds/page-routes {:page-name ::archive-year
                    :path      "/gigs/archive/{year}"
                    :view-ns   'app.gigs.archive.views})
   (ds/page-routes {:page-name  ::create
                    :path       "/gigs/create"
                    :view-ns    'app.gigs.edit.views
                    :extra-head app.gigs.edit.views/extra-head})
   (ds/page-routes {:page-name ::detail
                    :path      "/gig/{gig/gig-id}"
                    :view-ns   'app.gigs.detail.views})
   (ds/page-routes {:page-name ::detail-trailing-slash
                    :path      "/gig/{gig/gig-id}/"
                    :view-ns   'app.gigs.detail.views})
   (ds/page-routes {:page-name ::probeplan
                    :path      "/gig/{gig/gig-id}/probeplan"
                    :view-ns   'app.gigs.probeplan.views})
   (ds/page-routes {:page-name ::setlist
                    :path      "/gig/{gig/gig-id}/setlist"
                    :view-ns   'app.gigs.setlist.views})
   (ds/page-routes {:page-name ::log-plays
                    :path      "/gig/{gig/gig-id}/log-plays"
                    :view-ns   'app.gigs.log-plays.views})
   (ds/page-routes {:page-name  ::edit
                    :path       "/gig/{gig/gig-id}/edit"
                    :view-ns    'app.gigs.edit.views
                    :extra-head app.gigs.edit.views/extra-head})
   ["/gigs-legacy"
    (gig-create-route)
    (gigs-list-route)
    (gigs-archive-route)]
   ["/gig-legacy" {:interceptors [gigs-interceptors]}
    (gig-detail-route)
    (gig-log-play-route)]])

(defn unauthenticated-routes
  ([]
   (unauthenticated-routes nil))
  ([system]
   (cond-> [""
            ["/answer-link" {:app.route/name :app/gig-answer-link
                             :get            answer-link/answer-link}]
            ["/answer-link/" {:app.route/name :app/gig-answer-link
                              :get            answer-link/answer-link}]]
     (config/dev-mode? (:env system))
     (conj ["/dev/answer-link" {:app.route/name :app/gig-answer-link-dev
                                :get            answer-link/answer-link-preview}]
           ["/dev/answer-link/" {:app.route/name :app/gig-answer-link-dev
                                 :get            answer-link/answer-link-preview}]))))
