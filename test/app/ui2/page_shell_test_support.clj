(ns app.ui2.page-shell-test-support
  (:require
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [lookup.core :as l]))

(defn translation-key
  "Returns the first Fluent message key within `node`."
  [node]
  (some-> (l/select-one :i18n/tr node)
          l/first-child))

(defn- node-label [node]
  (if (string? node)
    node
    (or (translation-key node)
        (some-> node l/text not-empty))))

(defn- action-keyword [value]
  (when (string? value)
    (let [action-ns   (second (re-find #"[?&]ns=([^&'\")]+)" value))
          action-name (second (re-find #"[?&]kw=([^&'\")]+)" value))]
      (when (and action-ns action-name)
        (keyword action-ns action-name)))))

(defn- action-summary [action]
  (merge {:label (node-label action)}
         (select-keys (l/attrs action)
                      [:href :form :type :appearance :variant :data-dialog :disabled])))

(defn- overflow-summary [item]
  (merge {:label (node-label item)}
         (select-keys (l/attrs item)
                      [:value :data-dialog :variant :disabled
                       :data-indicator :data-attr:loading :data-attr:disabled])
         (when-let [action (action-keyword (:data-on:click (l/attrs item)))]
           {:action action})))

(defn breadcrumb-max-items
  "Returns an explicit breadcrumb item limit from the PageToolbar in `view`."
  [view]
  (let [attrs (some-> (l/select-one page-surface/PageSurface view)
                      l/attrs
                      ::page-surface/toolbar
                      l/attrs
                      ::page-toolbar/breadcrumb
                      l/attrs)]
    (or (::breadcrumb/max-items attrs)
        (:max-items attrs))))

(defn- breadcrumb-mobile-context [breadcrumb]
  (let [attrs  (l/attrs breadcrumb)
        mode   (or (::breadcrumb/mobile-mode attrs)
                   (:mobile-mode attrs)
                   :parent)
        items  (vec (l/select breadcrumb/BreadcrumbItem breadcrumb))
        parent (when (< 1 (count items))
                 (nth items (- (count items) 2)))
        parent-attrs (some-> parent l/attrs)
        href   (or (::breadcrumb/href parent-attrs)
                   (:href parent-attrs))]
    (case mode
      :hidden nil
      :parent (when parent
                {:label (node-label parent)
                 :href  href})
      :trail {:mode :trail})))

(defn page-contract
  "Returns the shared PageSurface and PageToolbar contract from `view`.

  Options:

  | key                | description |
  | ------------------ | ----------- |
  | `:include-header?` | Include the toolbar label and PageHeader contract. |"
  ([view]
   (page-contract view {}))
  ([view {:keys [include-header?]}]
   (when-let [surface (l/select-one page-surface/PageSurface view)]
     (let [surface-attrs (l/attrs surface)
           toolbar       (::page-surface/toolbar surface-attrs)
           toolbar-attrs (some-> toolbar l/attrs)
           breadcrumb    (::page-toolbar/breadcrumb toolbar-attrs)
           actions       (::page-toolbar/actions toolbar-attrs)
           overflow      (::page-toolbar/overflow-items toolbar-attrs)
           header-attrs  (some-> (l/select-one page-header/PageHeader surface) l/attrs)]
       (cond->
        {:width       (or (::page-surface/width surface-attrs) :standard)
         :breadcrumbs (mapv node-label
                            (l/select breadcrumb/BreadcrumbItem breadcrumb))
         :mobile      (breadcrumb-mobile-context breadcrumb)
         :actions     (mapv action-summary
                            (l/select button/Button actions))
         :overflow    (mapv overflow-summary
                            (l/select 'wa-dropdown-item overflow))}
         include-header?
         (assoc :toolbar-label     (node-label (:aria-label toolbar-attrs))
                :heading           (node-label (or (::page-header/title header-attrs)
                                                   (:title header-attrs)))
                :subtitle          (node-label (or (::page-header/subtitle header-attrs)
                                                   (:subtitle header-attrs)))
                :header-breadcrumb (or (::page-header/breadcrumb header-attrs)
                                       (:breadcrumb header-attrs))))))))

(defn page-structure
  "Returns the shared shell contract and editor-page structure from `view`."
  [view]
  (let [header-attrs (some-> (l/select-one page-header/PageHeader view) l/attrs)]
    {:contract (page-contract view)
     :heading  (node-label (or (::page-header/title header-attrs)
                               (:title header-attrs)))
     :subtitle (or (::page-header/subtitle header-attrs)
                   (:subtitle header-attrs))
     :form-id  (some-> (l/select-one "form[id$=-edit-form]" view) l/attrs :id)
     :last-tag (when (vector? view)
                 (some-> view l/last-child first))
     :last-id  (when (vector? view)
                 (some-> view l/last-child l/attrs :id))}))
