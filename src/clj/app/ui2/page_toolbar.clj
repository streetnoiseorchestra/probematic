(ns app.ui2.page-toolbar
  (:require
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.core :as uic]
   [app.ui2.icon :as ico]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-page-toolbar
  {:examples ["[page-toolbar/PageToolbar {::page-toolbar/breadcrumb breadcrumb
               ::page-toolbar/actions [log-plays-button]
               ::page-toolbar/overflow-items overflow-items
               ::page-toolbar/overflow-label \"More gig actions\"
               :aria-label \"Gig controls\"}]"]
   :ns       *ns*
   :as       'page-toolbar
   :name     'PageToolbar
   :desc     "Renders responsive page context, visible lifecycle actions, and an overflow menu."
   :alias    ::page-toolbar
   :schema
   [:map {}
    [::breadcrumb {:doc "Breadcrumb context rendered by the toolbar."}
     :any]
    [::actions {:optional true
                :doc      "Visible action node or collection of action nodes."}
     :any]
    [::overflow-items {:optional true
                       :doc      "Web Awesome dropdown items for secondary actions."}
     :any]
    [::overflow-label {:optional true
                       :doc      "Accessible label for the overflow trigger."}
     :any]]})

(def ^{:doc (uic/generate-docstring doc-page-toolbar)} PageToolbar
  ::page-toolbar)

(def ^:private consumed-props
  #{::actions ::breadcrumb ::overflow-items ::overflow-label})

(defn- hiccup-node? [value]
  (and (vector? value)
       (keyword? (first value))))

(defn- nodes [value]
  (cond
    (nil? value)         []
    (hiccup-node? value) [value]
    :else                (filter some? value)))

(defn- overflow-menu [label items]
  (let [items (nodes items)]
    (when (seq items)
      (assert label "PageToolbar overflow items require ::overflow-label")
      (into
       [:wa-dropdown {:placement "bottom-end"}
        [button/Button {:slot       "trigger"
                        :appearance "plain"
                        :aria-label label}
         [ico/Icon {::ico/library :snoico
                    ::ico/name    :ellipsis}]]]
       items))))

(defmethod c/resolve-alias ::page-toolbar
  [_ attrs _children]
  (let [attrs          (or attrs {})
        breadcrumb     (get attrs ::breadcrumb)
        _              (when-not (and (vector? breadcrumb)
                                      (= breadcrumb/Breadcrumb (first breadcrumb)))
                         (throw
                          (ex-info
                           "PageToolbar requires ::breadcrumb/Breadcrumb"
                           {:breadcrumb breadcrumb})))
        _              (uic/validate-opts! doc-page-toolbar attrs)
        actions        (nodes (get attrs ::actions))
        overflow-items (get attrs ::overflow-items)
        overflow-label (get attrs ::overflow-label)
        overflow       (overflow-menu overflow-label overflow-items)
        attrs          (-> (apply dissoc attrs consumed-props)
                           (uic/merge-attrs :class "sno-page-toolbar")
                           (update :role #(or % "toolbar"))
                           (assoc :data-class:stuck "$pageToolbarStuck"))
        _              (assert (:aria-label attrs)
                               "PageToolbar requires an accessible :aria-label")]
    [[:div {:class                                  "sno-page-toolbar-sentinel"
            :aria-hidden                            true
            :data-signals:page-toolbar-stuck        "false"
            :data-on-intersect                      "$pageToolbarStuck = false"
            :data-on-intersect__exit
            "$pageToolbarStuck = el.getBoundingClientRect().top < 0"}]
     [:header attrs
      [:div {:class "context"} breadcrumb]
      (when (or (seq actions) overflow)
        (into [:menu {:class "actions"}]
              (concat (map (fn [action] [:li action]) actions)
                      (when overflow [[:li {:class "overflow"} overflow]]))))]]))
