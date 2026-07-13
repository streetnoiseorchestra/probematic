(ns app.poll.view-test-support
  (:require
   [app.poll.test-support :as poll-support]
   [app.test-common :as tc]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn tr
  ([path]
   (case path
     [:polls/draft-status] "draft"
     [:polls/open-status] "open"
     [:polls/closed-status] "closed"
     [:polls/single-choice] "single"
     [:polls/multiple-choice] "multiple"
     (name (last path))))
  ([path data]
   (case path
     [:polls/confirm-close-poll] (str "Close " (:title data) "?")
     [:polls/confirm-delete-poll] (str "Delete " (:title data) "?")
     [:polls/select-num-choices] (str (:min data) "–" (:max data))
     (tr path))))

(defn new-system [prefix]
  (tc/new-system prefix))

(defn request
  ([system]
   (request system {}))
  ([{:keys [conn member-id]} extra]
   (merge {::r/router        router
           :current-locale  :en
           :current-member-id member-id
           :db              (d/db conn)
           :page-state      {}
           :system          {:env {}}
           :tr              tr}
          extra)))

(defn seed-poll!
  ([system status]
   (seed-poll! system status {}))
  ([{:keys [conn member-id]} status attrs]
   (poll-support/seed-poll!
    conn
    member-id
    (merge {:poll/poll-status status} attrs))))

(defn action-keyword [value]
  (when (string? value)
    (let [action-ns   (second (re-find #"[?&]ns=([^&'\")]+)" value))
          action-name (second (re-find #"[?&]kw=([^&'\")]+)" value))]
      (when (and action-ns action-name)
        (keyword action-ns action-name)))))

(defn translation-key [node]
  (some-> (l/select-one :i18n/tr node)
          l/first-child))

(defn node-label [node]
  (or (translation-key node)
      (some-> node l/text not-empty)))

(defn- action-summary [action]
  (merge {:label (node-label action)}
         (select-keys (l/attrs action)
                      [:href :form :type :appearance :variant :data-dialog])))

(defn- overflow-summary [item]
  (merge {:label (node-label item)}
         (select-keys (l/attrs item)
                      [:value :data-dialog :variant])))

(defn page-contract [view]
  (when-let [surface (l/select-one page-surface/PageSurface view)]
    (let [surface-attrs (l/attrs surface)
          toolbar       (::page-surface/toolbar surface-attrs)
          toolbar-attrs (some-> toolbar l/attrs)
          breadcrumb    (::page-toolbar/breadcrumb toolbar-attrs)
          mobile-back   (::page-toolbar/mobile-back toolbar-attrs)
          actions       (::page-toolbar/actions toolbar-attrs)
          overflow      (::page-toolbar/overflow-items toolbar-attrs)]
      {:width       (::page-surface/width surface-attrs)
       :breadcrumbs (mapv node-label
                          (l/select breadcrumb/BreadcrumbItem breadcrumb))
       :mobile      (when-let [back (l/select-one button/Button mobile-back)]
                      {:label (node-label back)
                       :href  (:href (l/attrs back))})
       :actions     (mapv action-summary
                          (l/select button/Button actions))
       :overflow    (mapv overflow-summary
                          (l/select 'wa-dropdown-item overflow))})))

(defn page-structure [view]
  (let [header-attrs (some-> (l/select-one page-header/PageHeader view) l/attrs)]
    {:contract (page-contract view)
     :heading  (some-> (:title header-attrs) node-label)
     :subtitle (:subtitle header-attrs)
     :form-id  (some-> (l/select-one "form#poll-edit-form" view) l/attrs :id)
     :last-tag (when (vector? view)
                 (some-> view l/last-child first))
     :last-id  (when (vector? view)
                 (some-> view l/last-child l/attrs :id))}))
