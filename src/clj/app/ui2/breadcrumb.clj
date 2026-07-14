(ns app.ui2.breadcrumb
  (:require
   [app.ui2.button :as button]
   [app.ui2.core :as uic]
   [app.ui2.icon :as ico]
   [clojure.string :as str]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c])
  (:import
   [java.net URI URISyntaxException]))

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
     :string]
    [::max-items {:optional true
                  :doc      "Maximum number of visible breadcrumb positions, including the collapse trigger when present. A two-item vector configures [mobile desktop] limits."}
     [:or pos-int? [:tuple pos-int? pos-int?]]]
    [::items-before-collapse
     {:optional true
      :default  0
      :doc      "Number of leading breadcrumb items to preserve before the collapse trigger."}
     nat-int?]]})

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

(defn- item-parts [item]
  (let [[_ attrs & children] (uic/norm item)]
    {:attrs    attrs
     :children children}))

(defn- internal-href? [href]
  (and (string? href)
       (not (str/blank? href))
       (str/starts-with? href "/")
       (not (str/starts-with? href "//"))
       (try
         (let [uri  (URI. href)
               path (.getRawPath uri)]
           (and (not (.isAbsolute uri))
                (nil? (.getRawAuthority uri))
                (some? path)
                (str/starts-with? path "/")))
         (catch URISyntaxException _
           false))))

(defn- collapse-dropdown [items]
  (let [items (mapv (fn [item]
                      (let [{:keys [attrs children]} (item-parts item)
                            href (option attrs ::href :href nil)]
                        (when-not (internal-href? href)
                          (throw
                           (ex-info
                            "Collapsed breadcrumb items require an internal href"
                            {:href href
                             :item item})))
                        (into [:wa-dropdown-item {:value href}] children)))
                    items)]
    (into
     [:wa-dropdown
      {:placement         "bottom-start"
       :data-on:wa-select "window.location.href = evt.detail.item.value"}
      [button/Button {:slot       "trigger"
                      :appearance "plain"
                      :size       "s"}
       [ico/Icon {::ico/library :snoico
                  ::ico/name    :ellipsis}]
       [:span {:class "wa-visually-hidden"}
        [:i18n/tr :action/show-hidden-breadcrumb-items {:count (count items)}]]]]
     items)))

(defn- visible-positions [items max-items items-before-collapse]
  (if-not (and max-items (> (count items) max-items))
    (mapv (fn [item] {:child item}) items)
    (do
      (when (< max-items 2)
        (throw
         (ex-info "Breadcrumb max-items must be at least 2 when collapsing"
                  {:max-items max-items})))
      (when (> items-before-collapse (- max-items 2))
        (throw
         (ex-info
          "Breadcrumb items-before-collapse must not exceed max-items - 2"
          {:items-before-collapse items-before-collapse
           :max-items             max-items})))
      (let [trailing-count (- max-items items-before-collapse 1)
            trailing-start (- (count items) trailing-count)
            leading        (subvec items 0 items-before-collapse)
            collapsed      (subvec items items-before-collapse trailing-start)
            trailing       (subvec items trailing-start)]
        (into
         (mapv (fn [item] {:child item}) leading)
         (concat
          [{:child     (collapse-dropdown collapsed)
            :collapse? true}]
          (map (fn [item] {:child item}) trailing)))))))

(defmethod c/resolve-alias ::breadcrumb
  [_ attrs children]
  (uic/validate-opts! doc-breadcrumb attrs)
  (let [max-items             (option attrs ::max-items :max-items nil)
        responsive?          (vector? max-items)
        valid-max-items?     (or (nil? max-items)
                                 (pos-int? max-items)
                                 (and responsive?
                                      (= 2 (count max-items))
                                      (every? pos-int? max-items)))
        _                    (when-not valid-max-items?
                               (throw
                                (ex-info
                                 "Breadcrumb max-items must be a positive integer or [mobile desktop] pair"
                                 {:max-items max-items})))
        max-items-variants   (if responsive?
                               [[(first max-items)
                                 "sno-breadcrumb-list-mobile"]
                                [(second max-items)
                                 "sno-breadcrumb-list-desktop"]]
                               [[max-items nil]])
        items-before-collapse (option attrs
                                      ::items-before-collapse
                                      :items-before-collapse
                                      0)
        _                    (when-not (nat-int? items-before-collapse)
                               (throw
                                (ex-info
                                 "Breadcrumb items-before-collapse must be a non-negative integer"
                                 {:items-before-collapse items-before-collapse})))
        separator            (option attrs ::separator :separator default-separator)
        label                (or (option attrs ::label :label nil)
                                 (:aria-label attrs)
                                 "Breadcrumb")
        attrs                (dissoc attrs
                                     ::separator
                                     ::label
                                     ::max-items
                                     ::items-before-collapse
                                     :separator
                                     :label
                                     :max-items
                                     :items-before-collapse)
        items                (vec (filter some? children))
        items                (if (seq items)
                               (update items (dec (count items)) mark-current-page)
                               items)]
    (cc/compile
     (into
      [:nav (uic/merge-attrs attrs
                             :class "sno-breadcrumb"
                             :aria-label label)]
      (map
       (fn [[variant-max-items variant-class]]
         (let [positions (visible-positions items
                                            variant-max-items
                                            items-before-collapse)]
           (into [:ol {:class (uic/cs "sno-breadcrumb-list" variant-class)
                       :role  "list"}]
                 (map-indexed
                  (fn [idx {:keys [child collapse?]}]
                    (let [contents (cond-> []
                                     (pos? idx) (conj (separator-node separator))
                                     true       (conj child))]
                      (cond-> [:li]
                        collapse? (conj {:class "collapse"})
                        true
                        (conj
                         (into (if (pos? idx)
                                 [:div {:class "sno-breadcrumb-step"}]
                                 [:div])
                               contents)))))
                  positions))))
       max-items-variants)))))

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
