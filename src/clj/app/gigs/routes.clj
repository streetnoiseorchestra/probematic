(ns app.gigs.routes
  (:require
   [app.config :as config]
   [app.gigs.answer-link.views :as answer-link]
   [app.gigs.archive.views]
   [app.gigs.detail.views]
   [app.gigs.edit.views]
   [app.gigs.index.views]
   [app.gigs.log-plays.views]
   [app.gigs.probeplan.views]
   [app.gigs.setlist.views]
   [app.routes.datastar :as ds]))

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
                    :extra-head app.gigs.edit.views/extra-head})])

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
