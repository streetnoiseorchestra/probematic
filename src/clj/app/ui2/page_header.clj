(ns app.ui2.page-header
  (:require
   [app.ui2.core :as uic]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-page-header
  {:examples ["[page-header/PageHeader
              {::page-header/title \"Policy Settings\"
               ::page-header/subtitle \"Configure settings for Insurance 2026.\"}]"]
   :ns       *ns*
   :as       'page-header
   :name     'PageHeader
   :desc     "Renders a semantic page header with optional breadcrumb, subtitle, and actions."
   :alias    ::page-header
   :schema
   [:map {}
    [::title {:optional true
              :doc      "The page title."}
     :any]
    [::subtitle {:optional true
                 :doc      "Optional subtitle rendered as supporting paragraph text."}
     :any]
    [::breadcrumb {:optional true
                   :doc      "Optional breadcrumb node rendered before the title area."}
     :any]
    [::actions {:optional true
                :doc      "Optional action nodes rendered as a menu."}
     :any]]})

(def ^{:doc (uic/generate-docstring doc-page-header)} PageHeader
  ::page-header)

(def ^:private consumed-props
  #{::actions ::breadcrumb ::subtitle ::title
    :actions :breadcrumb :subtitle :title})

(defn- option [attrs namespaced-key plain-key]
  (cond
    (contains? attrs namespaced-key) (get attrs namespaced-key)
    (contains? attrs plain-key)      (get attrs plain-key)
    :else                           nil))

(defn- hiccup-node? [value]
  (and (vector? value)
       (keyword? (first value))))

(defn- action-nodes [actions]
  (cond
    (nil? actions)        []
    (hiccup-node? actions) [actions]
    :else                 (filter some? actions)))

(defn- hgroup-node [title subtitle]
  (when (or title subtitle)
    [:hgroup
     (when title
       [:h1 title])
     (when subtitle
       [:p subtitle])]))

(defn- actions-node [actions]
  (let [actions (action-nodes actions)]
    (when (seq actions)
      (into [:menu]
            (map (fn [action]
                   [:li action]))
            actions))))

(defmethod c/resolve-alias ::page-header
  [_ attrs _children]
  (let [attrs      (or attrs {})
        _          (uic/validate-opts! doc-page-header attrs)
        actions    (option attrs ::actions :actions)
        breadcrumb (option attrs ::breadcrumb :breadcrumb)
        class      (:class attrs)
        subtitle   (option attrs ::subtitle :subtitle)
        title      (option attrs ::title :title)
        attrs      (apply dissoc attrs consumed-props)]
    (cc/compile
     [:header (cond-> (assoc attrs :class (uic/cs "sno-page-header" class))
                (nil? class) (update :class uic/cs))
      breadcrumb
      (when (or title subtitle actions)
        [:div
         (hgroup-node title subtitle)
         (actions-node actions)])])))
