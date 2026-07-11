(ns app.dashboard.calendar.views
  (:require
   [app.datastar :as d*]
   [app.ui2 :as ui2]
   [app.ui2.card :as card]
   [app.ui2.page-header :as page-header]
   [app.ui2.button :as button]
   [app.urls :as urls]))

(defn page [{:keys [tr]}]
  (ui2/plain-page
   [:div {:class "wa-stack wa-gap-l"}
    [page-header/PageHeader
     {:title   (tr [:nav/calendar])
      :actions [[button/Button {:appearance "filled"
                                :variant    "brand"
                                :href       (urls/link-gig-create)}
                 (tr [:action/create-gig])]]}]
    [card/Card
     [:iframe {:class "dashboard-calendar-frame"
               :src   "https://data.streetnoise.at/apps/calendar/embed/yRFYYPnQkasfa8nk/listMonth/now"
               :width "100%"
               :height "1000"}]]]))

(d*/refresh-all!)
