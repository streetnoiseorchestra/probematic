(ns app.gigs.index.views
  (:require
   [app.datastar :as d*]
   [app.gigs.index.queries :as queries]
   [app.gigs.ui :as gigs.ui]
   [app.ui2 :as ui2]
   [app.urls :as urls]))

(defn page [{:keys [db tr]}]
  (let [{:keys [future-gigs past-gigs]} (queries/page-data db)]
    (ui2/plain-page
     [:div {:class "wa-stack wa-gap-l gigs-index-page"}
      [:div {:class "wa-flank:end wa-align-items-end wa-gap-s gigs-index-toolbar"}
       [:div {:class "wa-stack wa-gap-2xs"}
        [:h1 (tr [:gigs/title])]]
       [:wa-button {:appearance "filled"
                    :variant    "brand"
                    :href       (urls/link-gig-create)}
        (tr [:action/create])]]
      [:div {:class "wa-grid wa-gap-m gigs-index-columns"}
       (gigs.ui/gig-section {:title         (tr [:gigs/upcoming])
                             :empty-message (tr [:gigs/no-future])
                             :gigs          future-gigs})
       (gigs.ui/gig-section {:title         (tr [:gigs/past])
                             :empty-message (tr [:gigs/no-past])
                             :gigs          past-gigs
                             :footer        [:div {:class "gigs-list-footer"}
                                             [:wa-button {:appearance "plain"
                                                          :href       (urls/link-gig-archive)}
                                              (tr [:gigs/view-archive])]]})]])))

(d*/refresh-all!)
