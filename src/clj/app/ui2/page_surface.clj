(ns app.ui2.page-surface
  (:require
   [app.ui2.core :as uic]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-page-surface
  {:examples ["[page-surface/PageSurface
              {::page-surface/width :wide
               ::page-surface/toolbar toolbar}
              page-content]"]
   :ns       *ns*
   :as       'page-surface
   :name     'PageSurface
   :desc     "Renders a raised page workspace with an optional toolbar and responsive width intent."
   :alias    ::page-surface
   :schema
   [:map {}
    [::width {:optional true
              :default  :standard
              :doc      "The compact, standard, or wide content-width intent."}
     [:enum :compact :standard :wide "compact" "standard" "wide"]]
    [::toolbar {:optional true
                :doc      "Optional page toolbar rendered above the padded page body."}
     :any]]})

(def ^{:doc (uic/generate-docstring doc-page-surface)} PageSurface
  ::page-surface)

(defn- width-name [width]
  (name (or width :standard)))

(defmethod c/resolve-alias ::page-surface
  [_ attrs children]
  (let [attrs   (or attrs {})
        _       (uic/validate-opts! doc-page-surface attrs)
        toolbar (get attrs ::toolbar)
        width   (get attrs ::width :standard)
        attrs   (-> (dissoc attrs ::toolbar ::width)
                    (uic/merge-attrs :class "sno-page-surface")
                    (assoc :data-width (width-name width)))]
    (cc/compile
     (cond-> [:section attrs]
       toolbar (conj toolbar)
       true    (conj (into [:div {:class "body"}] children))))))
