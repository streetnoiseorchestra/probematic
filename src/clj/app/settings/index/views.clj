(ns app.settings.index.views
  (:require
   [app.datastar :as d*]
   [app.ui2 :as ui2]))

(defn- settings-link-card [{:keys [href icon title body]}]
  [:wa-button {:href       href
               :appearance "plain"
               :class      "band-settings-index-card-button"}
   [:div {:class "wa-flank wa-flex-nowrap wa-align-items-start"}
    [:wa-avatar {:shape "rounded"}
     [:wa-icon {:slot    "icon"
                :library "snoico"
                :name    icon
                :class   "wa-color-text-link"}]]
    [:div {:class "band-settings-index-card-body"}
     [:strong {:class "wa-color-text-link"} title]
     [:p body]]]])

(defn page [{:keys [tr]}]
  (ui2/plain-page
   [:div {:class "wa-grid band-settings-index-grid"}
    (ui2/page-header {:class "wa-span-grid"
                      :title (tr [:nav/band-settings])})
    [:wa-divider {:class "wa-span-grid band-settings-index-divider"}]
    (settings-link-card {:href  "/band-settings/teams"
                         :icon  "users-outline"
                         :title "Teams"
                         :body  "Create teams and manage their members."})
    (settings-link-card {:href  "/band-settings/travel-discounts"
                         :icon  "cog"
                         :title "Travel Discounts"
                         :body  "Manage the reusable travel discount types members can choose."})
    (settings-link-card {:href  "/band-settings/sections"
                         :icon  "trumpet"
                         :title "Sections"
                         :body  "Choose which sections are available and how they are ordered."})]))

(d*/refresh-all!)
