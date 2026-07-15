(ns app.settings.index.views
  (:require
   [app.datastar :as d*]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.avatar :as avatar]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]))

(defn- settings-link-card [{:keys [href icon title body]}]
  [button/Button {:href       href
                  :appearance "plain"
                  :class      "band-settings-index-card-button"}
   [:div {:class "wa-flank wa-flex-nowrap wa-align-items-start"}
    [avatar/Avatar {::avatar/icon icon
                    ::avatar/icon-library :snoico
                    ::avatar/icon-attrs {:class "wa-color-text-link"}
                    :shape "rounded"
                    :style "flex: none"}]
    [:div {:class "band-settings-index-card-body"}
     [:strong {:class "wa-color-text-link"} title]
     [:p body]]]])

(defn page [_req]
  (ui2/datastar-page*
   [page-surface/PageSurface
    {::page-surface/width :standard
     ::page-surface/toolbar
     [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                [breadcrumb/Breadcrumb {}
                                 [breadcrumb/BreadcrumbItem {::breadcrumb/href "/"}
                                  [:i18n/tr :home]]
                                 [breadcrumb/BreadcrumbItem [:i18n/tr :band-settings/title]]]
                                :aria-label [:i18n/tr :band-settings/toolbar-label]}]}
    [:div {:class "wa-grid" :style "--min-column-size: var(--sno-settings-index-min-column-size);"}
     [page-header/PageHeader {:class "wa-span-grid"
                              :title [:i18n/tr :band-settings/title]}]
     [divider/Divider {:class "wa-span-grid"}]
     (settings-link-card {:href  "/band-settings/teams"
                          :icon  "users-outline"
                          :title [:i18n/tr :band-settings/team-title]
                          :body  [:i18n/tr :band-settings/team-page-subtitle]})
     (settings-link-card {:href  "/band-settings/travel-discounts"
                          :icon  "cog"
                          :title [:i18n/tr :band-settings/travel-discount-title]
                          :body  [:i18n/tr :band-settings/travel-discount-page-subtitle]})
     (settings-link-card {:href  "/band-settings/sections"
                          :icon  "trumpet"
                          :title [:i18n/tr :band-settings/section-title]
                          :body  [:i18n/tr :band-settings/section-page-subtitle]})]]))

(d*/refresh-all!)
