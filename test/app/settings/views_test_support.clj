(ns app.settings.views-test-support
  (:require
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn legacy-tr
  ([resource-ids]
   (pr-str resource-ids))
  ([resource-ids _data]
   (pr-str resource-ids)))

(defn translation-keys [hiccup]
  (into #{}
        (keep (fn [node]
                (when (and (vector? node)
                           (= :i18n/tr (first node)))
                  (second node))))
        (tree-seq coll? seq hiccup)))
