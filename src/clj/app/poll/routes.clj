(ns app.poll.routes
  (:require
   [app.poll.detail.views]
   [app.poll.edit.views]
   [app.poll.index.views]
   [app.routes.datastar :as ds]))

(defn routes []
  ["" {:app.route/name :app/polls}
   (ds/page-routes {:page-name ::index
                    :path      "/polls"
                    :view-ns   'app.poll.index.views})
   (ds/page-routes {:page-name ::create
                    :path      "/polls/new"
                    :view-ns   'app.poll.edit.views})
   (ds/page-routes {:page-name ::detail
                    :path      "/poll/{poll/poll-id}"
                    :view-ns   'app.poll.detail.views})
   (ds/page-routes {:page-name ::edit
                    :path      "/poll/{poll/poll-id}/edit"
                    :view-ns   'app.poll.edit.views})])
