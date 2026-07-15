(ns app.ui2.breadcrumb
  (:require
   [app.ui2.button :as button]
   [app.ui2.core :as uic]
   [app.ui2.icon :as ico]
   [clojure.string :as str]
   [dev.onionpancakes.chassis.compiler :as cc]
   [dev.onionpancakes.chassis.core :as c]))

(def doc-breadcrumb
  {:examples ["[breadcrumb/Breadcrumb {::breadcrumb/label \"Page hierarchy\"}
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
                  :default  [2 3]
                  :doc      "Maximum number of visible breadcrumb items. The overflow trigger does not count toward this limit. A two-item vector configures [compact expanded] limits; nil renders one unrestricted trail."}
     [:maybe [:or pos-int? [:tuple pos-int? pos-int?]]]]
    [::items-before-collapse
     {:optional true
      :default  0
      :doc      "Number of leading breadcrumb items to preserve before the collapse trigger."}
     nat-int?]
    [::mobile-mode
     {:optional true
      :default  :parent
      :doc      "Mobile presentation for responsive breadcrumbs: the immediate parent link, a collapsed trail, or no mobile context."}
     [:enum :parent :trail :hidden]]]})

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

(defn- item-label [children]
  (case (count children)
    0 nil
    1 (first children)
    (into [:span] children)))

(defn- mobile-parent [item]
  (let [{:keys [attrs children]} (item-parts item)
        href   (option attrs ::href :href nil)
        target (option attrs ::target :target nil)
        rel    (option attrs ::rel :rel (when target "noreferrer noopener"))]
    (when (or (not (string? href)) (str/blank? href))
      (throw
       (ex-info "Breadcrumb mobile parent requires an href"
                {:item item})))
    [button/BackButton
     (cond-> (-> attrs
                 (dissoc ::href ::target ::rel :href :target :rel :aria-current)
                 (uic/merge-attrs :class "mobile-parent")
                 (assoc :href href
                        :label (item-label children)))
       target (assoc :target target)
       rel (assoc :rel rel))]))

(defn- projection-class [compact? expanded?]
  (cond
    (= compact? expanded?) nil
    compact?              "compact-only"
    :else                 "expanded-only"))

(defn- collapse-label [count variant-class]
  [:span (cond-> {:class "wa-visually-hidden"}
           variant-class (uic/merge-attrs :class variant-class))
   [:i18n/tr :action/show-hidden-breadcrumb-items {:count count}]])

(defn- collapse-labels [{:keys [compact expanded]}]
  (cond
    (= compact expanded)
    [(collapse-label compact nil)]

    (zero? expanded)
    [(collapse-label compact nil)]

    :else
    [(collapse-label expanded "expanded-label")
     (collapse-label compact "compact-label")]))

(defn- collapse-popover [positions hidden-counts]
  (let [trigger-id (str "sno-breadcrumb-collapse-" (random-uuid))
        popover-id (str trigger-id "-popover")
        trigger    (str "document.getElementById('" trigger-id "')")]
    [:span {:class "control"}
     (into [button/Button {:id            trigger-id
                           :appearance    "plain"
                           :size          "s"
                           :aria-controls popover-id
                           :aria-expanded "false"
                           :aria-haspopup "dialog"}
            [ico/Icon {::ico/library :snoico
                       ::ico/name    :ellipsis}]]
           (collapse-labels hidden-counts))
     [:wa-popover {:id        popover-id
                   :class     "popover"
                   :for       trigger-id
                   :placement "bottom-start"
                   :without-arrow true
                   :data-on:wa-show
                   (str trigger ".setAttribute('aria-expanded', 'true')")
                   :data-on:wa-hide
                   (str trigger ".setAttribute('aria-expanded', 'false')")}
      (into [:ol {:class "popover-list"
                  :role  "list"}]
            (map (fn [{:keys [child variant-class]}]
                   (cond-> [:li]
                     variant-class (conj {:class variant-class})
                     true          (conj child)))
                 positions))]]))

(defn- item-position [items idx]
  {:child      (nth items idx)
   :separator? (pos? idx)})

(defn- visible-positions [items max-items items-before-collapse]
  (if-not (and max-items (> (count items) max-items))
    (mapv #(item-position items %) (range (count items)))
    (let [trailing-count (- max-items items-before-collapse)
          trailing-start (- (count items) trailing-count)
          leading         (range items-before-collapse)
          collapsed       (range items-before-collapse trailing-start)
          trailing        (range trailing-start (count items))]
      (into
       (mapv #(item-position items %) leading)
       (concat
        [{:child         (collapse-popover
                          (mapv (fn [idx] {:child (nth items idx)}) collapsed)
                          {:compact  (count collapsed)
                           :expanded (count collapsed)})
          :collapse?     true
          :separator?    (pos? items-before-collapse)}]
        (map #(item-position items %) trailing))))))

(defn- collapse-projection [item-count max-items items-before-collapse]
  (if-not (> item-count max-items)
    {:visible   (set (range item-count))
     :collapsed #{}}
    (let [trailing-count (- max-items items-before-collapse)
          trailing-start (- item-count trailing-count)]
      {:visible   (set (concat (range items-before-collapse)
                               (range trailing-start item-count)))
       :collapsed (set (range items-before-collapse trailing-start))})))

(defn- adaptive-positions
  [items compact-max expanded-max items-before-collapse]
  (let [item-count          (count items)
        compact             (collapse-projection item-count
                                                 compact-max
                                                 items-before-collapse)
        expanded            (collapse-projection item-count
                                                 expanded-max
                                                 items-before-collapse)
        compact-visible     (:visible compact)
        expanded-visible    (:visible expanded)
        compact-collapsed   (:collapsed compact)
        expanded-collapsed  (:collapsed expanded)
        visible-indices     (filter #(or (contains? compact-visible %)
                                         (contains? expanded-visible %))
                                    (range item-count))
        collapsed-indices   (filter #(or (contains? compact-collapsed %)
                                         (contains? expanded-collapsed %))
                                    (range item-count))
        collapse?           (seq collapsed-indices)
        position            (fn [idx]
                              (assoc (item-position items idx)
                                     :variant-class
                                     (projection-class
                                      (contains? compact-visible idx)
                                      (contains? expanded-visible idx))))
        collapse-position   (when collapse?
                              {:child
                               (collapse-popover
                                (mapv
                                 (fn [idx]
                                   {:child         (nth items idx)
                                    :variant-class
                                    (projection-class
                                     (contains? compact-collapsed idx)
                                     (contains? expanded-collapsed idx))})
                                 collapsed-indices)
                                {:compact  (count compact-collapsed)
                                 :expanded (count expanded-collapsed)})
                               :collapse?     true
                               :separator?    (pos? items-before-collapse)
                               :variant-class
                               (projection-class
                                (boolean (seq compact-collapsed))
                                (boolean (seq expanded-collapsed)))})
        [leading trailing]  (split-with #(< % items-before-collapse)
                                        visible-indices)]
    (cond-> (mapv position leading)
      collapse-position (conj collapse-position)
      true              (into (map position trailing)))))

(defn- valid-max-items? [max-items]
  (or (nil? max-items)
      (pos-int? max-items)
      (and (vector? max-items)
           (= 2 (count max-items))
           (every? pos-int? max-items))))

(defn- max-item-limits [max-items]
  (cond
    (vector? max-items) max-items
    (some? max-items)   [max-items]
    :else               []))

(defn- validate-collapse-options! [max-items items-before-collapse mobile-mode]
  (when-not (valid-max-items? max-items)
    (throw
     (ex-info
      "Breadcrumb max-items must be a positive integer or [compact expanded] pair"
      {:max-items max-items})))
  (when (and (vector? max-items)
             (> (first max-items) (second max-items)))
    (throw
     (ex-info
      "Breadcrumb compact max-items must not exceed expanded max-items"
      {:max-items max-items})))
  (when-not (nat-int? items-before-collapse)
    (throw
     (ex-info
      "Breadcrumb items-before-collapse must be a non-negative integer"
      {:items-before-collapse items-before-collapse})))
  (when-not (#{:parent :trail :hidden} mobile-mode)
    (throw
     (ex-info "Breadcrumb mobile-mode must be :parent, :trail, or :hidden"
              {:mobile-mode mobile-mode})))
  (when-let [max-items (some #(when (> items-before-collapse (dec %)) %)
                             (max-item-limits max-items))]
    (throw
     (ex-info
      "Breadcrumb items-before-collapse must not exceed max-items - 1"
      {:items-before-collapse items-before-collapse
       :max-items             max-items}))))

(defn- breadcrumb-list-from-positions [positions separator variant-class]
  (into [:ol {:class (uic/cs "sno-breadcrumb-list" variant-class)
              :role  "list"}]
        (map
         (fn [{:keys [child collapse? separator? variant-class]}]
           (let [contents (cond-> []
                            separator? (conj (separator-node separator))
                            true       (conj child))
                 li-class (uic/cs (when collapse? "collapse") variant-class)]
             (cond-> [:li]
               (seq li-class) (conj {:class li-class})
               true
               (conj
                (into (if separator?
                        [:div {:class "sno-breadcrumb-step"}]
                        [:div])
                      contents)))))
         positions)))

(defn- breadcrumb-list [items max-items items-before-collapse separator variant-class]
  (breadcrumb-list-from-positions
   (visible-positions items max-items items-before-collapse)
   separator
   variant-class))

(defn- adaptive-breadcrumb-list
  [items compact-max expanded-max items-before-collapse separator]
  (breadcrumb-list-from-positions
   (adaptive-positions items
                       compact-max
                       expanded-max
                       items-before-collapse)
   separator
   "desktop"))

(defmethod c/resolve-alias ::breadcrumb
  [_ attrs children]
  (uic/validate-opts! doc-breadcrumb attrs)
  (let [max-items             (option attrs ::max-items :max-items [2 3])
        responsive?          (vector? max-items)
        items-before-collapse (option attrs
                                      ::items-before-collapse
                                      :items-before-collapse
                                      0)
        mobile-mode          (option attrs ::mobile-mode :mobile-mode :parent)
        _                    (validate-collapse-options! max-items
                                                         items-before-collapse
                                                         mobile-mode)
        separator            (option attrs ::separator :separator default-separator)
        label                (or (option attrs ::label :label nil)
                                 (:aria-label attrs)
                                 "Breadcrumb")
        attrs                (dissoc attrs
                                     ::separator
                                     ::label
                                     ::max-items
                                     ::items-before-collapse
                                     ::mobile-mode
                                     :separator
                                     :label
                                     :max-items
                                     :items-before-collapse
                                     :mobile-mode)
        items                (vec (filter some? children))
        items                (if (seq items)
                               (update items (dec (count items)) mark-current-page)
                               items)
        mobile-parent        (when (and (= :parent mobile-mode)
                                        (> (count items) 1))
                               (mobile-parent (nth items (- (count items) 2))))
        lists                (cond
                               responsive?
                               (cond-> []
                                 mobile-parent
                                 (conj mobile-parent)

                                 (= :trail mobile-mode)
                                 (conj (breadcrumb-list items
                                                        (first max-items)
                                                        items-before-collapse
                                                        separator
                                                        "mobile"))

                                 true
                                 (conj (adaptive-breadcrumb-list
                                        items
                                        (first max-items)
                                        (second max-items)
                                        items-before-collapse
                                        separator)))

                               (= :trail mobile-mode)
                               [(breadcrumb-list items
                                                 max-items
                                                 items-before-collapse
                                                 separator
                                                 nil)]

                               :else
                               (cond-> []
                                 mobile-parent
                                 (conj mobile-parent)

                                 true
                                 (conj (breadcrumb-list
                                        items
                                        max-items
                                        items-before-collapse
                                        separator
                                        "desktop"))))]
    (cc/compile
     (into
      [:nav (uic/merge-attrs attrs
                             :class "sno-breadcrumb"
                             :aria-label label)]
      lists))))

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
        rel      (option attrs ::rel :rel (when target "noreferrer noopener"))
        link?    (not (str/blank? href))
        attrs    (dissoc attrs ::href ::target ::rel :href :target :rel)
        children (uic/wrap-text-node children)]
    (cc/compile
     (if link?
       [:a (cond-> (uic/merge-attrs attrs
                                    :href href
                                    :class "sno-breadcrumb-item sno-breadcrumb-link")
             target (assoc :target target)
             rel (assoc :rel rel))
        children]
       [:span (uic/merge-attrs attrs
                               :class "sno-breadcrumb-item sno-breadcrumb-current")
        children]))))
