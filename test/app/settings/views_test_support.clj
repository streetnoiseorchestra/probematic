(ns app.settings.views-test-support
  (:require
   [app.i18n :as i18n]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn fluent-tr [locale]
  (i18n/tr-with
   (update-vals (i18n/read-langs) #(assoc % :tempura {}))
   [locale]))
