(ns app.gigs.index.views
  (:require
   [app.datastar :as d*]
   [app.gigs.queries :as queries]
   [app.gigs.ui :as gigs.ui]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.urls :as urls]))

(defn- archive-menu-item []
  [:wa-dropdown-item {:value   (urls/link-gig-archive)
                      :onclick "window.location = this.value"}
   [:i18n/tr :gigs/view-archive]])

(defn- page-toolbar []
  (gigs.ui/page-toolbar
   {:breadcrumb     (gigs.ui/breadcrumb-trail
                     (gigs.ui/breadcrumb-link (urls/link-dashboard)
                                              [:i18n/tr :gigs/dashboard])
                     (gigs.ui/breadcrumb-current [:i18n/tr :gigs/title]))
    :mobile-href    (urls/link-dashboard)
    :mobile-label   [:i18n/tr :gigs/dashboard]
    :actions        [[button/Button {:appearance "filled"
                                     :variant    "brand"
                                     :href       (urls/link-gig-create)}
                      [:i18n/tr :gigs/new-gig]]]
    :overflow-items [(archive-menu-item)]
    :overflow-label [:i18n/tr :action/more-actions]
    :aria-label     [:i18n/tr :gigs/index-toolbar-label]}))

(defn page [{:keys [db tr] :as req}]
  (let [{:keys [future-gigs past-gigs]} (queries/index-page-data db)]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width   :wide
       ::page-surface/toolbar (page-toolbar)}
      [:div {:class "wa-stack wa-gap-l"}
       [page-header/PageHeader {:title (tr [:gigs/title])}]
       [:div {:class "wa-grid wa-gap-m gigs-index-columns"}
        (gigs.ui/gig-section req {:title         (tr [:gigs/upcoming])
                                  :empty-message (tr [:gigs/no-future])
                                  :gigs          future-gigs})
        (gigs.ui/gig-section req {:title         (tr [:gigs/past])
                                  :empty-message (tr [:gigs/no-past])
                                  :gigs          past-gigs})]]])))

(d*/refresh-all!)
