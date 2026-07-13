(ns app.songs.ui
  (:require
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-toolbar :as toolbar]))

(defn breadcrumb-link [href label]
  [breadcrumb/BreadcrumbItem {::breadcrumb/href href}
   label])

(defn breadcrumb-current [label]
  [breadcrumb/BreadcrumbItem label])

(defn breadcrumb-trail [& items]
  (into [breadcrumb/Breadcrumb {}] items))

(defn- mobile-back [href label]
  [button/Button {:appearance "plain"
                  :href       href}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    :arrow-left
              :slot         "start"}]
   label])

(defn page-toolbar
  [{:keys [actions aria-label breadcrumb mobile-href mobile-label
           overflow-items overflow-label]}]
  [toolbar/PageToolbar
   (cond-> {::toolbar/breadcrumb  breadcrumb
            ::toolbar/mobile-back (mobile-back mobile-href mobile-label)
            :aria-label            aria-label}
     (seq actions)
     (assoc ::toolbar/actions actions)

     (seq overflow-items)
     (assoc ::toolbar/overflow-items overflow-items
            ::toolbar/overflow-label overflow-label))])
