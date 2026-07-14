(ns app.gigs.archive.views
  (:require
   [app.datastar :as d*]
   [app.gigs.archive.actions :as actions]
   [app.gigs.queries :as queries]
   [app.gigs.ui :as gigs.ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util.http :as http.util]))

(defn- page-toolbar [selected-year include-year?]
  [page-toolbar/PageToolbar
   {::page-toolbar/breadcrumb
    [breadcrumb/Breadcrumb
     (cond-> {}
       include-year? (assoc ::breadcrumb/max-items [2 3]))
     [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-gigs-home)}
      [:i18n/tr :gigs/title]]
     [breadcrumb/BreadcrumbItem (cond-> {}
                                  include-year? (assoc ::breadcrumb/href
                                                       (urls/link-gig-archive)))
      [:i18n/tr :gigs/archive-title]]
     (when include-year?
       [breadcrumb/BreadcrumbItem selected-year])]
    ::page-toolbar/mobile-back
    [button/BackButton {:href  (urls/link-gigs-home)
                        :label [:i18n/tr :gigs/title]}]
    :aria-label [:i18n/tr :gigs/archive-toolbar-label]}])

(defn- year-button [selected-year year]
  [button/Button (cond-> {:appearance "outlined"
                          :size       "s"
                          :href       (urls/link-gig-archive-year year)}
                   (= selected-year year) (assoc :appearance "filled"
                                                 :variant "brand"))
   year])

(defn- year-selector [selected-year years]
  (into
   [:div {:class "wa-cluster wa-gap-2xs gigs-archive-year-selector"
          :aria-label "Archive years"}]
   (map (partial year-button selected-year) years)))

(defn- archive-tools [{:keys [tr] :as req} _ selected-year years]
  [:div {:class "wa-stack wa-gap-s gigs-archive-tools"}
   (year-selector selected-year years)
   [:wa-input {:type               "search"
               :label              (tr [:action/search])
               :placeholder        "Search gig titles"
               :with-clear         true
               :data-on:input__debounce.250ms
               (str "@post(`" (d*/act req ::actions/set-search-phrase) "&q=${evt.target.value}`)")}]])

(defn page [{:keys [db page-state tr] :as req}]
  (let [{:keys [selected-year years gigs] archive-page-state :page-state}
        (queries/archive-page-data db
                                   (http.util/path-param req :year)
                                   (:gigs-archive page-state))]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width   :wide
       ::page-surface/toolbar (page-toolbar selected-year
                                            (some? (http.util/path-param req :year)))}
      [:div {:class        "wa-stack wa-gap-l"
             :data-signals (d*/->signals {:gigs-archive archive-page-state})}
       [page-header/PageHeader
        {:title    [:i18n/tr :gigs/archive-title]
         :subtitle selected-year}]
       (archive-tools req archive-page-state selected-year years)
       (gigs.ui/gig-section req {:title         selected-year
                                 :empty-message (tr [:gigs/no-past])
                                 :gigs          gigs})]])))

(d*/refresh-all!)
