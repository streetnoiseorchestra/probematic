(ns app.dashboard.calendar.views
  (:require
   [app.datastar :as d*]
   [app.ui2 :as ui2]
   [app.urls :as urls]))

(defn page [{:keys [tr]}]
  (ui2/plain-page
   [:div {:class "dashboard-calendar-page wa-stack wa-gap-l"}
    (ui2/page-header
     {:title   (tr [:nav/calendar])
      :actions [[:wa-button {:appearance "filled"
                             :variant    "brand"
                             :href       (urls/link-gig-create)}
                 (tr [:action/create-gig])]]})
    [:wa-card
     [:iframe {:class "dashboard-calendar-frame"
               :src   "https://data.streetnoise.at/apps/calendar/embed/yRFYYPnQkasfa8nk/listMonth/now"
               :width "100%"
               :height "1000"}]]]))

(d*/refresh-all!)
