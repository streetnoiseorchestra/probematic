(ns app.gigs.view-test-support
  (:require
   [app.gigs.domain :as domain]
   [app.test-common :as tc]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]
   [tick.core :as t]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(defn new-system [prefix]
  (tc/new-system prefix))

(defn request
  ([conn]
   (request conn {}))
  ([conn extra]
   (merge {::r/router       router
           :current-locale :en
           :db             (d/db conn)
           :page-state     {}
           :system         {:env {}}
           :tr             tr}
          extra)))

(defn seed-gig!
  ([conn gig-id]
   (seed-gig! conn gig-id {}))
  ([conn gig-id gig]
   @(d/transact
     conn
     [(domain/gig->db
       (merge {:gig/gig-id    gig-id
               :gig/title     "Summer Concert"
               :gig/status    :gig.status/confirmed
               :gig/gig-type  :gig.type/gig
               :gig/date      (t/date "2026-07-15")
               :gig/location  "Band room"
               :gig/call-time (t/time "18:00")}
              gig))])))

(defn translation-key [node]
  (some-> (l/select-one :i18n/tr node)
          l/first-child))

(defn node-label [node]
  (or (translation-key node)
      (some-> node l/text not-empty)))

(defn- action-summary [action]
  (merge {:label (node-label action)}
         (select-keys (l/attrs action)
                      [:href :form :type :appearance :variant])))

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
  {:contract    (page-contract view)
   :form-id     (some-> (l/select-one "form#gig-edit-form" view) l/attrs :id)
   :last-tag    (when (vector? view)
                  (some-> view l/last-child first))
   :last-id     (when (vector? view)
                  (some-> view l/last-child l/attrs :id))
   :signal-root (some-> (l/select-one "div[data-signals]" view) l/attrs :class)})
