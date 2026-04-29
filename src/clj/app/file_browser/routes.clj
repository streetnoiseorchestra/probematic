(ns app.file-browser.routes
  (:require
   [app.file-browser.views]
   [app.routes.datastar :as ds]))

(defn choose-file-page []
  (ds/page-routes {:page-name ::choose-file
                   :path      "/choose-file"
                   :view-ns   'app.file-browser.views}))

(defn routes []
  ["" {:app.route/name :app/file-browser}
   (choose-file-page)])
