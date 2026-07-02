(ns app.file-browser.routes
  (:require
   [app.file-browser.views :as views]
   [app.routes.datastar :as ds]))

(defn choose-file-page []
  (ds/page-routes {:page-name ::choose-file
                   :path      "/choose-file"
                   :page      #'views/page}))

(defn routes []
  ["" {:app.route/name :app/file-browser}
   (choose-file-page)])
