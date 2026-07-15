(ns app.gigs.index.views
  (:require
   [app.datastar :as d*]
   [app.gigs.queries :as queries]
   [app.gigs.ui :as gigs.ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]))

(defn- page-toolbar []
  [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                             [breadcrumb/Breadcrumb {}
                              [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-dashboard)}
                               [:i18n/tr :gigs/dashboard]]
                              [breadcrumb/BreadcrumbItem [:i18n/tr :gigs/title]]]
                             ::page-toolbar/actions
                             [[button/Button {:appearance "filled"
                                              :variant    "brand"
                                              :href       (urls/link-gig-create)}
                               [:i18n/tr :gigs/new-gig]]]
                             ::page-toolbar/overflow-items
                             [[:wa-dropdown-item {:value   (urls/link-gig-archive)
                                                  :onclick "window.location = this.value"}
                               [:i18n/tr :gigs/view-archive]]]
                             ::page-toolbar/overflow-label [:i18n/tr :action/more-actions]
                             :aria-label                    [:i18n/tr :gigs/index-toolbar-label]}])

(defn page [{:keys [db tr] :as req}]
  (let [{:keys [future-gigs past-gigs]} (queries/index-page-data db)]
    (ui2/datastar-page*
     [page-surface/PageSurface {::page-surface/toolbar (page-toolbar)}
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
