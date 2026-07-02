(ns app.poll.routes
  (:require
   [app.poll.detail.views :as detail.views]
   [app.poll.edit.views :as edit.views]
   [app.poll.index.views :as index.views]
   [app.routes.datastar :as ds]))

(defn routes []
  ["" {:app.route/name :app/polls}
   (ds/page-routes {:page-name ::index
                    :path      "/polls"
                    :page      #'index.views/page})
   (ds/page-routes {:page-name ::create
                    :path      "/polls/new"
                    :page      #'edit.views/page})
   (ds/page-routes {:page-name ::detail
                    :path      "/poll/{poll/poll-id}"
                    :page      #'detail.views/page})
   (ds/page-routes {:page-name ::edit
                    :path      "/poll/{poll/poll-id}/edit"
                    :page      #'edit.views/page})])
