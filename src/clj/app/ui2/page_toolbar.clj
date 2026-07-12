(ns app.ui2.page-toolbar
  (:require
   [app.ui2.button :as button]
   [app.ui2.core :as uic]
   [app.ui2.icon :as ico]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-page-toolbar
  {:examples ["[page-toolbar/PageToolbar
              {::page-toolbar/breadcrumb breadcrumb
               ::page-toolbar/mobile-back back-link
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
    [::breadcrumb {:optional true
                   :doc      "Desktop breadcrumb or other contextual node."}
     :any]
    [::mobile-back {:optional true
                    :doc      "Single back target shown at narrow widths."}
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
  #{::actions ::breadcrumb ::mobile-back ::overflow-items ::overflow-label})

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
        _              (uic/validate-opts! doc-page-toolbar attrs)
        breadcrumb     (get attrs ::breadcrumb)
        mobile-back    (get attrs ::mobile-back)
        actions        (nodes (get attrs ::actions))
        overflow-items (get attrs ::overflow-items)
        overflow-label (get attrs ::overflow-label)
        overflow       (overflow-menu overflow-label overflow-items)
        attrs          (-> (apply dissoc attrs consumed-props)
                           (uic/merge-attrs :class "sno-page-toolbar")
                           (update :role #(or % "toolbar")))
        _              (assert (:aria-label attrs)
                               "PageToolbar requires an accessible :aria-label")]
    (cc/compile
     [:header attrs
      [:div {:class "context"}
       [:div {:class "desktop"} breadcrumb]
       [:div {:class "mobile"} mobile-back]]
      (when (or (seq actions) overflow)
        (into [:menu {:class "actions"}]
              (concat (map (fn [action] [:li action]) actions)
                      (when overflow [[:li {:class "overflow"} overflow]]))))])))
