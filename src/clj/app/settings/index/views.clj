(ns app.settings.index.views
  (:require
   [app.datastar :as d*]
   [app.settings.view-support :as support]))

(defn- settings-link-card [{:keys [href title body]}]
  [:wa-button {:href       href
               :appearance "plain"
               :class      "band-settings-index-card-button"}
   [:div {:class "wa-stack wa-gap-2xs wa-align-items-start"}
    [:strong {:class "wa-color-text-link"} title]
    [:p body]]])

(defn page [{:keys [tr]}]
  (let [tr (or tr (fn [path & _] (name (last path))))]
    (support/plain-page
     [:div {:class "wa-grid"
            :style "--min-column-size: 28ch"}
      [:h1 {:class "wa-span-grid"} (tr [:nav/band-settings])]
      (settings-link-card {:href  "/band-settings/teams"
                           :title "Teams"
                           :body  "Create teams and manage their members."})
      (settings-link-card {:href  "/band-settings/travel-discounts"
                           :title "Travel Discounts"
                           :body  "Manage the reusable travel discount types members can choose."})
      (settings-link-card {:href  "/band-settings/sections"
                           :title "Sections"
                           :body  "Choose which sections are available and how they are ordered."})])))

(d*/refresh-all!)
