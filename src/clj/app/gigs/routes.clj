(ns app.gigs.routes
  (:require
   [app.config :as config]
   [app.gigs.answer-link.views :as answer-link]
   [app.gigs.archive.views :as archive.views]
   [app.gigs.detail.views :as detail.views]
   [app.gigs.edit.views :as edit.views]
   [app.gigs.index.views :as index.views]
   [app.gigs.log-plays.views :as log-plays.views]
   [app.gigs.probeplan.views :as probeplan.views]
   [app.gigs.setlist.views :as setlist.views]
   [app.routes.datastar :as ds]))

(defn routes []
  ["" {:app.route/name :app/gigs}
   (ds/page-routes {:page-name ::index
                    :path      "/gigs"
                    :page      #'index.views/page})
   (ds/page-routes {:page-name ::archive
                    :path      "/gigs/archive"
                    :page      #'archive.views/page})
   (ds/page-routes {:page-name ::archive-year
                    :path      "/gigs/archive/{year}"
                    :page      #'archive.views/page})
   (ds/page-routes {:page-name ::create
                    :path      "/gigs/create"
                    :page      #'edit.views/page})
   (ds/page-routes {:page-name ::detail
                    :path      "/gig/{gig/gig-id}"
                    :page      #'detail.views/page})
   (ds/page-routes {:page-name ::probeplan
                    :path      "/gig/{gig/gig-id}/probeplan"
                    :page      #'probeplan.views/page})
   (ds/page-routes {:page-name ::setlist
                    :path      "/gig/{gig/gig-id}/setlist"
                    :page      #'setlist.views/page})
   (ds/page-routes {:page-name ::log-plays
                    :path      "/gig/{gig/gig-id}/log-plays"
                    :page      #'log-plays.views/page})
   (ds/page-routes {:page-name ::edit
                    :path      "/gig/{gig/gig-id}/edit"
                    :page      #'edit.views/page})])

(defn unauthenticated-routes
  ([]
   (unauthenticated-routes nil))
  ([system]
   (cond-> [""
            ["/answer-link" {:app.route/name :app/gig-answer-link
                             :get            answer-link/answer-link}]]
     (config/dev-mode? (:env system))
     (conj ["/dev/answer-link" {:app.route/name :app/gig-answer-link-dev
                                :get            answer-link/answer-link-preview}]))))
