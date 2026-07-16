(ns app.stats.views
  (:require
   [app.datastar :as d*]
   [app.html :as html]
   [app.stats.charts :as charts]
   [app.stats.queries :as stats]
   [app.stats.state :as state]
   [app.ui2 :as ui2]
   [app.ui2.avatar :as avatar]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as url]))

(defn- fmt-double [value]
  (if (some? value)
    (format "%.1f" (double value))
    html/emdash))

(defn- fmt-percent [value]
  (if (some? value)
    (format "%.1f%%" (* 100 (double value)))
    html/emdash))

(defn- fmt-count [value]
  (if (some? value)
    (str value)
    html/emdash))

(defn- metric-cell [{:keys [id label tooltip value]}]
  (let [info-id (str id "-info")]
    [:div {:class "wa-stack" :id id}
     [:div {:class "wa-split"}
      [:div {:class "wa-cluster wa-gap-xs"}
       [ico/Icon {::ico/library :snoico
                  ::ico/name    :square-info
                  ::ico/label   tooltip
                  :id           info-id}]
       [:span {:class "trim-cap"} label]
       (when tooltip
         [:wa-tooltip {:for       info-id
                       :placement "top"
                       :trigger   "hover focus click"}
          tooltip])]
      #_[:div
         {:class "wa-cluster wa-gap-xs",
          :style {:color "var(--wa-color-green)"}}
         [ico/Icon {::ico/library :phosphor ::ico/name :trend-up}]
         #_[:wa-format-number
            {:class "wa-heading-m", :type "percent", :value ".475"}]]]
     [:div {:class "wa-heading-2xl stats-metric-value"} value]]))

(defn- summary-card [stats]
  [card/Card {:class "stats-summary-card"}
   [:div {:class "wa-grid wa-gap-3xl" :style "--min-column-size: 24ch;"}
    (metric-cell {:id      "stats-gig-attendance-rate"
                  :label   [:i18n/tr :statistics/gig-attendance-rate]
                  :value   (fmt-percent (:attendance-rate-gigs stats))
                  :tooltip [:i18n/tr :statistics/gig-attendance-rate-tooltip]})
    (metric-cell {:id      "stats-probe-attendance-rate"
                  :label   [:i18n/tr :statistics/probe-attendance-rate]
                  :value   (fmt-percent (:attendance-rate-probes stats))
                  :tooltip [:i18n/tr :statistics/probe-attendance-rate-tooltip]})
    (metric-cell {:id      "stats-mean-attendance-gig"
                  :label   [:i18n/tr :statistics/mean-attendance-gig]
                  :value   (fmt-double (:mean-attendance-gig stats))
                  :tooltip [:i18n/tr :statistics/mean-attendance-gig-tooltip]})
    (metric-cell {:id      "stats-mean-attendance-probe"
                  :label   [:i18n/tr :statistics/mean-attendance-probe]
                  :value   (fmt-double (:mean-attendance-probe stats))
                  :tooltip [:i18n/tr :statistics/mean-attendance-probe-tooltip]})
    (metric-cell {:id      "stats-total-gigs"
                  :label   [:i18n/tr :statistics/total-gigs]
                  :value   (fmt-count (:gig-count stats))
                  :tooltip [:i18n/tr :statistics/total-gigs-tooltip]})
    (metric-cell {:id      "stats-total-probes"
                  :label   [:i18n/tr :statistics/total-probes]
                  :value   (fmt-count (:probe-count stats))
                  :tooltip [:i18n/tr :statistics/total-probes-tooltip]})
    (metric-cell {:id      "stats-total-plays"
                  :label   [:i18n/tr :statistics/total-plays]
                  :value   (fmt-count (:total-plays stats))
                  :tooltip [:i18n/tr :statistics/total-plays-tooltip]})
    (metric-cell {:id      "stats-active-members-count"
                  :label   [:i18n/tr :statistics/active-members-count]
                  :value   (fmt-count (:active-members-count stats))
                  :tooltip [:i18n/tr :statistics/active-members-count-tooltip]})
    (metric-cell {:id      "stats-most-active-gig-count"
                  :label   [:i18n/tr :statistics/most-active-gig-count]
                  :value   (fmt-count (:most-active-gig-count stats))
                  :tooltip [:i18n/tr :statistics/most-active-gig-count-tooltip]})
    (metric-cell {:id      "stats-least-active-gig-count"
                  :label   [:i18n/tr :statistics/least-active-gig-count]
                  :value   (fmt-count (:least-active-gig-count stats))
                  :tooltip [:i18n/tr :statistics/least-active-gig-count-tooltip]})
    (metric-cell {:id      "stats-most-active-probe-count"
                  :label   [:i18n/tr :statistics/most-active-probe-count]
                  :value   (fmt-count (:most-active-probe-count stats))
                  :tooltip [:i18n/tr :statistics/most-active-probe-count-tooltip]})
    (metric-cell {:id      "stats-least-active-probe-count"
                  :label   [:i18n/tr :statistics/least-active-probe-count]
                  :value   (fmt-count (:least-active-probe-count stats))
                  :tooltip [:i18n/tr :statistics/least-active-probe-count-tooltip]})]])

(defn- timespan-controls [req]
  (into
   [:wa-button-group {:style "justify-content: end;"
                      :label [:i18n/tr :statistics/timespan-label]}]
   (for [{:keys [id label-key]} state/timespan-options]
     [button/Button (cond-> {:href       (state/timespan-url req id)
                             :appearance "outlined"
                             :size       "s"}
                      (= id (state/selected-timespan-id req))
                      (assoc :variant "brand"))
      [:i18n/tr label-key]])))

(defn- histogram-chart-effect [signal-path]
  (str "const chartConfigJson = $"
       signal-path
       "; customElements.whenDefined('wa-chart').then(() => { el.config = JSON.parse(chartConfigJson) })"))

(defn- histogram-card [{:keys [description signal-path title]}]
  [card/Card {:class "stats-chart-card"}
   [:div {:slot "header" :class "wa-split"}
    [:h2 {:class "wa-heading-l"} title]]
   [:div {:class "stats-chart-container"}
    [:wa-chart {:id              "attendance-histogram"
                :label           title
                :description     description
                :legend-position "bottom"
                :without-tooltip true
                :data-effect     (histogram-chart-effect signal-path)
                :style           "display: block; block-size: 100%; inline-size: var(--sno-size-full);"}]]])

(defn charts-section [req stats]
  (let [signals (charts/attendance-histogram-signals req stats)]
    [:section {:class        "wa-stack wa-gap-m"
               :data-signals (d*/->signals signals)}
     (histogram-card {:description [:i18n/tr :statistics/methodology-histograms-body]
                      :signal-path "statsDashboard.attendanceHistogramChartJson"
                      :title       [:i18n/tr :statistics/attendance]})]))

(defn- methodology []
  [:wa-details {:class      "stats-methodology"
                :summary    [:i18n/tr :statistics/methodology-title]
                :appearance "outlined"}
   [:div {:class "wa-stack wa-gap-s stats-methodology-body"}
    [:section {:class "wa-stack wa-gap-2xs"}
     [:h3 {:class "wa-heading-s"} [:i18n/tr :statistics/methodology-rates-title]]
     [:p [:i18n/tr :statistics/methodology-rates-body]]]
    [:section {:class "wa-stack wa-gap-2xs"}
     [:h3 {:class "wa-heading-s"} [:i18n/tr :statistics/methodology-active-members-title]]
     [:p [:i18n/tr :statistics/methodology-active-members-body]]]
    [:section {:class "wa-stack wa-gap-2xs"}
     [:h3 {:class "wa-heading-s"} [:i18n/tr :statistics/methodology-histograms-title]]
     [:p [:i18n/tr :statistics/methodology-histograms-body]]]
    [:section {:class "wa-stack wa-gap-2xs"}
     [:h3 {:class "wa-heading-s"} [:i18n/tr :statistics/methodology-accuracy-title]]
     [:p [:i18n/tr :statistics/methodology-accuracy-body]]]]])

(defn- sortable-header [req field label tooltip]
  (let [target-id (str "stats-sort-" (ui2/safe-dom-id field))
        info-id   (str target-id "-info")]
    [:th (cond-> {:scope "col"}
           (not= :member/name field) (assoc :class "stats-table-col--desktop"))
     [:span {:class "stats-sort-header-label"}
      [:a {:id   target-id
           :href (state/sort-url req field)}
       label]

      (when tooltip
        [ico/Icon {::ico/library :phosphor
                   ::ico/name    :info
                   ::ico/label   tooltip
                   :id           info-id}])
      (when tooltip
        [:wa-tooltip {:for       info-id
                      :placement "top"
                      :trigger   "hover click focus"}
         tooltip])]]))

(defn- mobile-stat-header [id label tooltip]
  (let [info-id (str id "-info")]
    [:th {:scope "col" :class "stats-table-col--mobile"}
     [:span {:class "stats-sort-header-label"}
      [:span label]
      (when tooltip
        [ico/Icon {::ico/library :phosphor
                   ::ico/name    :info
                   ::ico/label   tooltip
                   :id           info-id}])
      (when tooltip
        [:wa-tooltip {:for       info-id
                      :placement "top"
                      :trigger   "hover click focus"}
         tooltip])]]))

(defn- member-avatar [member]
  [avatar/Avatar {::avatar/member member
                  ::avatar/icon :user
                  ::avatar/link? false
                  :shape "rounded"
                  :style "--size: 2rem; flex: none;"}])

(defn- member-row [req {:keys [member gigs-attended probes-attended last-seen gig-rate probe-rate gig-title]}]
  [:tr
   [:td
    [:a {:href (url/link-member member)}
     [:div {:class "wa-cluster wa-gap-xs stats-member"}
      (member-avatar member)
      [:span (:member/name member)]]]]
   [:td {:class "stats-table-col--desktop"} (fmt-count gigs-attended)]
   [:td {:class "stats-table-col--desktop"} (fmt-percent gig-rate)]
   [:td {:class "stats-table-col--desktop"} (fmt-count probes-attended)]
   [:td {:class "stats-table-col--desktop"} (fmt-percent probe-rate)]
   [:td {:class "stats-table-col--desktop" :title gig-title}
    (if last-seen
      (ui2/date-display req :short last-seen)
      html/emdash)]
   [:td {:class "stats-table-col--mobile"}
    [:span {:class "stats-mobile-stat-label"} [:i18n/tr :statistics/gigs-short]]
    [:span (fmt-percent gig-rate) " (" (fmt-count gigs-attended) ")"]]
   [:td {:class "stats-table-col--mobile"}
    [:span {:class "stats-mobile-stat-label"} [:i18n/tr :statistics/probes-short]]
    [:span (fmt-percent probe-rate) " (" (fmt-count probes-attended) ")"]]])

(defn- member-table [req per-member-stats]
  (ui2/section-card
   {:title    [:i18n/tr :statistics/member-table-title]
    :subtitle [:i18n/tr :statistics/member-table-subtitle]}
   (ui2/table-shell
    (if (seq per-member-stats)
      [:table {:class "stats-member-table"}
       [:thead
        [:tr
         (sortable-header req :member/name [:i18n/tr :statistics/member-name] nil)
         (sortable-header req :gigs-attended [:i18n/tr :statistics/gigs-attended] [:i18n/tr :statistics/gigs-attended-tooltip])
         (sortable-header req :gig-rate [:i18n/tr :statistics/gigs-percent] [:i18n/tr :statistics/gigs-percent-tooltip])
         (sortable-header req :probes-attended [:i18n/tr :statistics/probes-attended] [:i18n/tr :statistics/probes-attended-tooltip])
         (sortable-header req :probe-rate [:i18n/tr :statistics/probes-percent] [:i18n/tr :statistics/probes-percent-tooltip])
         (sortable-header req :last-seen [:i18n/tr :statistics/last-seen] [:i18n/tr :statistics/last-seen-tooltip])
         (mobile-stat-header "stats-mobile-gigs" [:i18n/tr :statistics/gigs-short] [:i18n/tr :statistics/gigs-percent-tooltip])
         (mobile-stat-header "stats-mobile-probes" [:i18n/tr :statistics/probes-short] [:i18n/tr :statistics/probes-percent-tooltip])]]
       [:tbody
        (for [member-stat per-member-stats]
          (member-row req member-stat))]]
      (ui2/empty-state
       [:i18n/tr :statistics/member-table-empty-title]
       [:i18n/tr :statistics/member-table-empty-body])))))

(defn page [{:keys [db] :as req}]
  (let [{:keys [from to]} (state/selected-range req)
        stats            (stats/stats-for db from to (state/sort-spec req))]
    (ui2/datastar-page*
     [:script {:type "module"}
      (html/raw "import 'wa/components/chart/chart.js';")]
     [page-surface/PageSurface {::page-surface/toolbar
                                [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                                           [breadcrumb/Breadcrumb {}
                                                            [breadcrumb/BreadcrumbItem {::breadcrumb/href (url/link-dashboard)}
                                                             [:i18n/tr :home]]
                                                            [breadcrumb/BreadcrumbItem [:i18n/tr :statistics/title]]]
                                                           :aria-label [:i18n/tr :statistics/toolbar-label]}]}
      [:div {:class "wa-stack wa-gap-xl"}
       [page-header/PageHeader
        {::page-header/title    [:i18n/tr :statistics/title]
         ::page-header/subtitle [:i18n/tr :statistics/current-range
                                 {:from (ui2/format-date req :medium from)
                                  :to   (ui2/format-date req :medium to)}]}]
       (timespan-controls req)
       (summary-card stats)
       (charts-section req stats)
       (methodology)
       (member-table req (:per-member-stats stats))]])))

(d*/refresh-all!)
