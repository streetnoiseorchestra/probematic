(ns app.settings.index.views
  (:require
   [app.datastar :as d*]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]))

(defn- settings-link-card [{:keys [href icon title body]}]
  [button/Button {:href       href
                  :appearance "plain"
                  :class      "band-settings-index-card-button"}
   [:div {:class "wa-flank wa-flex-nowrap wa-align-items-start"}
    [:wa-avatar {:shape "rounded"}
     [ico/Icon {::ico/library :snoico
                ::ico/name    icon
                :slot         "icon"
                :class        "wa-color-text-link"}]]
    [:div {:class "band-settings-index-card-body"}
     [:strong {:class "wa-color-text-link"} title]
     [:p body]]]])

(defn page [{:keys [tr]}]
  (ui2/plain-page
   [:div {:class "wa-grid" :style "--min-column-size: var(--sno-settings-index-min-column-size);"}
    (ui2/page-header {:class "wa-span-grid"
                      :title (tr [:nav/band-settings])})
    [divider/Divider {:class "wa-span-grid"}]
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
