(ns app.gigs.archive.views
  (:require
   [app.datastar :as d*]
   [app.gigs.archive.actions :as actions]
   [app.gigs.queries :as queries]
   [app.gigs.ui :as gigs.ui]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util.http :as http.util]))

(defn- year-button [selected-year year]
  [:wa-button (cond-> {:appearance "outlined"
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
    (ui2/datastar-page
     [:div {:class        "wa-stack wa-gap-l gigs-archive-page"
            :data-signals (d*/->signals {:gigs-archive archive-page-state})}
      (ui2/page-header
       {:title    (tr [:gigs/title])
        :subtitle selected-year
        :actions  [[:wa-button {:appearance "filled"
                                :variant    "brand"
                                :href       (urls/link-gig-create)}
                    (tr [:action/create])]]})
      (archive-tools req archive-page-state selected-year years)
      (gigs.ui/gig-section req {:title         selected-year
                                :empty-message (tr [:gigs/no-past])
                                :gigs          gigs})])))

(d*/refresh-all!)
