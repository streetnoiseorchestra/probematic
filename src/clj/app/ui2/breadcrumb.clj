(ns app.ui2.breadcrumb
  (:require
   [app.ui2.core :as uic]
   [app.ui2.icon :as ico]
   [clojure.string :as str]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-breadcrumb
  {:examples ["[breadcrumb/Breadcrumb
              {::breadcrumb/label \"Page hierarchy\"}
              [breadcrumb/BreadcrumbItem {::breadcrumb/href \"/gigs\"} \"Gigs\"]
              [breadcrumb/BreadcrumbItem \"Probe 6/17/26\"]]"]
   :ns       *ns*
   :as       'breadcrumb
   :name     'Breadcrumb
   :desc     "Breadcrumbs display a trail of links that show users where they are in a site's hierarchy."
   :alias    ::breadcrumb
   :schema
   [:map {}
    [::separator {:optional true
                  :default  :nav-arrow-right
                  :doc      "The separator to use between breadcrumb items. Set to nil to render no separator."}
     :any]
    [::label {:optional true
              :default  "Breadcrumb"
              :doc      "The label to use for the breadcrumb control. This will not be shown on the screen, but it will be announced by screen readers and other assistive devices to provide more context for users."}
     :string]]})

(def ^{:doc (uic/generate-docstring doc-breadcrumb)} Breadcrumb
  ::breadcrumb)

(defn- option [attrs namespaced-key plain-key default]
  (cond
    (contains? attrs namespaced-key) (get attrs namespaced-key)
    (contains? attrs plain-key)      (get attrs plain-key)
    :else                           default))

(def default-separator
  #_[:svg
     {:viewBox "0 0 20 20",
      :fill "currentColor",
      :data-slot "icon",
      :aria-hidden "true",
      :class "size-5 shrink-0 text-gray-400 dark:text-gray-500"}
     [:path
      {:d
       "M8.22 5.22a.75.75 0 0 1 1.06 0l4.25 4.25a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 0 1-1.06-1.06L11.94 10 8.22 6.28a.75.75 0 0 1 0-1.06Z",
       :clip-rule "evenodd",
       :fill-rule "evenodd"}]]
  [ico/Icon {::ico/library :phosphor ::ico/name :caret-right}])

(defn- separator-node [separator]
  (when (some? separator)
    (if (and (vector? separator) (keyword? (first separator)))
      (uic/assoc-attr separator
                      :class "separator"
                      :aria-hidden true)
      [:span {:class "separator"
              :aria-hidden true}
       separator])))

(defn- mark-current-page [item]
  (if (vector? item)
    (let [[tag attrs & children] item]
      (if (map? attrs)
        (into [tag (assoc attrs :aria-current "page")] children)
        (into [tag {:aria-current "page"}] (cons attrs children))))
    item))

(defmethod c/resolve-alias ::breadcrumb
  [_ attrs children]
  (uic/validate-opts! doc-breadcrumb attrs)
  (let [separator (option attrs ::separator :separator default-separator)
        label     (or (option attrs ::label :label nil)
                      (:aria-label attrs)
                      "Breadcrumb")
        attrs     (dissoc attrs ::separator ::label :separator :label)
        items     (vec (filter some? children))
        items     (if (seq items)
                    (update items (dec (count items)) mark-current-page)
                    items)]
    (cc/compile
     [:nav (uic/merge-attrs attrs
                            :class "sno-breadcrumb"
                            :aria-label label)
      (into [:ol {:class "sno-breadcrumb-list" :role "list"}]
            (map-indexed
             (fn [idx child]
               (let [contents (cond-> []
                                (pos? idx) (conj (separator-node separator))
                                true       (conj child))]
                 [:li
                  (into (if (pos? idx)
                          [:div {:class "sno-breadcrumb-step"}]
                          [:div])
                        contents)]))
             items))])))

(def doc-breadcrumb-item
  {:examples ["[breadcrumb/BreadcrumbItem {::breadcrumb/href \"/gigs\"} \"Gigs\"]"
              "[breadcrumb/BreadcrumbItem \"Probe 6/17/26\"]"]
   :ns       *ns*
   :as       'breadcrumb
   :name     'BreadcrumbItem
   :desc     "Breadcrumb items represent individual links inside a breadcrumb, typically one per level of the site hierarchy."
   :alias    ::breadcrumb-item
   :schema
   [:map {}
    [::target {:optional true
               :doc      "Tells the browser where to open the link. Only used when href is set."}
     [:enum "_blank" "_parent" "_self" "_top"]]
    [::rel {:optional true
            :default  "noreferrer noopener"
            :doc      "The rel attribute to use on the link. Only used when href is set."}
     :string]
    [::href {:optional true
             :doc      "Optional URL to direct the user to when the breadcrumb item is activated. When set, a link will be rendered internally. When unset or blank, the item is rendered as the current page."}
     :string]]})

(def ^{:doc (uic/generate-docstring doc-breadcrumb-item)} BreadcrumbItem
  ::breadcrumb-item)

(defmethod c/resolve-alias ::breadcrumb-item
  [_ attrs children]
  (uic/validate-opts! doc-breadcrumb-item attrs)
  (let [href     (option attrs ::href :href nil)
        target   (option attrs ::target :target nil)
        rel      (option attrs ::rel :rel "noreferrer noopener")
        link?    (not (str/blank? href))
        attrs    (dissoc attrs ::href ::target ::rel :href :target :rel)
        children (uic/wrap-text-node children)]
    (cc/compile
     (if link?
       [:a (cond-> (uic/merge-attrs attrs
                                    :href href
                                    :class "sno-breadcrumb-item sno-breadcrumb-link")
             target (assoc :target target)
             (and target rel) (assoc :rel rel))
        children]
       [:span (uic/merge-attrs attrs
                               :class "sno-breadcrumb-item sno-breadcrumb-current")
        children]))))
