(ns app.gigs.index.views
  (:require
   [app.datastar :as d*]
   [app.gigs.queries :as queries]
   [app.gigs.ui :as gigs.ui]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.urls :as urls]))

(defn page [{:keys [db tr] :as req}]
  (let [{:keys [future-gigs past-gigs]} (queries/index-page-data db)]
    (ui2/plain-page
     [:div {:class "wa-stack wa-gap-l"}
      (ui2/page-header
       {:title   (tr [:gigs/title])
        :actions [[button/Button {:appearance "filled"
                                  :variant    "brand"
                                  :href       (urls/link-gig-create)}
                   (tr [:action/create])]]})
      [:div {:class "wa-grid wa-gap-m gigs-index-columns"}
       (gigs.ui/gig-section req {:title         (tr [:gigs/upcoming])
                                 :empty-message (tr [:gigs/no-future])
                                 :gigs          future-gigs})
       (gigs.ui/gig-section req {:title         (tr [:gigs/past])
                                 :empty-message (tr [:gigs/no-past])
                                 :gigs          past-gigs
                                 :footer        [:div {:class "gigs-list-footer"}
                                                 [button/Button {:appearance "plain"
                                                                 :href       (urls/link-gig-archive)}
                                                  (tr [:gigs/view-archive])]]})]])))

(d*/refresh-all!)
