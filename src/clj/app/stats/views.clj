(ns app.stats.views
  (:require
   [app.html :as html]
   [app.stats.queries :as stats]
   [app.stats.state :as state]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.urls :as url]
   [clojure.string :as str]
   [jsonista.core :as j]))

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

(defn- avatar-src [member]
  (when-let [tpl (:member/avatar-template member)]
    (str "https://forum.streetnoise.at"
         (str/replace tpl "{size}" "80"))))

(defn- metric-cell [{:keys [id label tooltip value]}]
  (let [info-id (str id "-info")]
    [:div {:class "wa-stack" :id id}
     [:div {:class "wa-split"}
      [:div
       {:class "wa-cluster wa-gap-xs"}
       [ico/Icon {::ico/library :phosphor
                  ::ico/name    :info
                  ::ico/label   tooltip
                  :id           info-id}]
       (when tooltip
         [:wa-tooltip {:for       info-id
                       :placement "top"
                       :trigger   "hover focus click"}
          tooltip])
       [:span label]]
      #_[:div
         {:class "wa-cluster wa-gap-xs",
          :style {:color "var(--wa-color-green)"}}
         [ico/Icon {::ico/library :phosphor ::ico/name :trend-up}]
         #_[:wa-format-number
            {:class "wa-heading-m", :type "percent", :value ".475"}]]]
     [:div {:class "wa-heading-2xl stats-metric-value"} value]]))

(defn- summary-card [tr stats]
  [:wa-card {:class "stats-summary-card"}
   [:div {:class "wa-grid wa-gap-3xl" :style "--min-column-size: 24ch;"}
    (metric-cell {:id      "stats-gig-attendance-rate"
                  :label   (tr [:stats/gig-attendance-rate])
                  :value   (fmt-percent (:attendance-rate-gigs stats))
                  :tooltip (tr [:stats/gig-attendance-rate-tooltip])})
    (metric-cell {:id      "stats-probe-attendance-rate"
                  :label   (tr [:stats/probe-attendance-rate])
                  :value   (fmt-percent (:attendance-rate-probes stats))
                  :tooltip (tr [:stats/probe-attendance-rate-tooltip])})
    (metric-cell {:id      "stats-mean-attendance-gig"
                  :label   (tr [:stats/mean-attendance-gig])
                  :value   (fmt-double (:mean-attendance-gig stats))
                  :tooltip (tr [:stats/mean-attendance-gig-tooltip])})
    (metric-cell {:id      "stats-mean-attendance-probe"
                  :label   (tr [:stats/mean-attendance-probe])
                  :value   (fmt-double (:mean-attendance-probe stats))
                  :tooltip (tr [:stats/mean-attendance-probe-tooltip])})
    (metric-cell {:id      "stats-total-gigs"
                  :label   (tr [:stats/total-gigs])
                  :value   (fmt-count (:gig-count stats))
                  :tooltip (tr [:stats/total-gigs-tooltip])})
    (metric-cell {:id      "stats-total-probes"
                  :label   (tr [:stats/total-probes])
                  :value   (fmt-count (:probe-count stats))
                  :tooltip (tr [:stats/total-probes-tooltip])})
    (metric-cell {:id      "stats-total-plays"
                  :label   (tr [:stats/total-plays])
                  :value   (fmt-count (:total-plays stats))
                  :tooltip (tr [:stats/total-plays-tooltip])})
    (metric-cell {:id      "stats-active-members-count"
                  :label   (tr [:stats/active-members-count])
                  :value   (fmt-count (:active-members-count stats))
                  :tooltip (tr [:stats/active-members-count-tooltip])})
    (metric-cell {:id      "stats-most-active-gig-count"
                  :label   (tr [:stats/most-active-gig-count])
                  :value   (fmt-count (:most-active-gig-count stats))
                  :tooltip (tr [:stats/most-active-gig-count-tooltip])})
    (metric-cell {:id      "stats-least-active-gig-count"
                  :label   (tr [:stats/least-active-gig-count])
                  :value   (fmt-count (:least-active-gig-count stats))
                  :tooltip (tr [:stats/least-active-gig-count-tooltip])})
    (metric-cell {:id      "stats-most-active-probe-count"
                  :label   (tr [:stats/most-active-probe-count])
                  :value   (fmt-count (:most-active-probe-count stats))
                  :tooltip (tr [:stats/most-active-probe-count-tooltip])})
    (metric-cell {:id      "stats-least-active-probe-count"
                  :label   (tr [:stats/least-active-probe-count])
                  :value   (fmt-count (:least-active-probe-count stats))
                  :tooltip (tr [:stats/least-active-probe-count-tooltip])})]])

(defn- timespan-controls [{:keys [tr] :as req}]
  (into
   [:wa-button-group {:style "justify-content: end;"
                      :label (tr [:stats/timespan-label])}]
   (for [{:keys [id label-key]} state/timespan-options]
     [button/Button (cond-> {:href       (state/timespan-url req id)
                             :appearance "outlined"
                             :size       "s"}
                      (= id (state/selected-timespan-id req))
                      (assoc :variant "brand"))
      (tr label-key)])))

(defn- chart-data [values title x-axis-label y-axis-label color]
  {:values     values
   :xAxisLabel x-axis-label
   :yAxisLabel y-axis-label
   :title      title
   :color      color})

(defn- chart-json [id data]
  [:script {:type "application/json" :id id}
   (html/raw (j/write-value-as-string data))])

(defn- histogram-card [{:keys [canvas-id data-id title]}]
  [:wa-card {:class "stats-chart-card"}
   [:div {:slot "header" :class "wa-split"}
    [:h2 {:class "wa-heading-l"} title]]
   [:div {:class "stats-chart-container"
          :data-init "if (window.SnoStatsCharts) window.SnoStatsCharts.renderAll(el)"}
    [:canvas {:class       "histogram-chart"
              :data-values (str "#" data-id)
              :id          canvas-id}]]])

(defn- charts-section [tr {:keys [gig-histogram probe-histogram]}]
  (let [x-axis-label (tr [:stats/attendance-rate])
        y-axis-label (tr [:stats/num-members])
        gig-title    (tr [:stats/gig-attendance])
        probe-title  (tr [:stats/probe-attendance])]
    [:section {:class "wa-stack wa-gap-m"}
     [:div
      (chart-json "gig-histogram-data"
                  (chart-data gig-histogram gig-title x-axis-label y-axis-label "#f97316"))
      (chart-json "probe-histogram-data"
                  (chart-data probe-histogram probe-title x-axis-label y-axis-label "#22c55e"))]
     [:div {:class "wa-grid wa-gap-l"
            :style "--min-column-size: min(30rem, 100%);"}
      (histogram-card {:canvas-id "gig-histogram"
                       :data-id   "gig-histogram-data"
                       :title     gig-title})
      (histogram-card {:canvas-id "probe-histogram"
                       :data-id   "probe-histogram-data"
                       :title     probe-title})]]))

(defn- methodology [{:keys [tr]}]
  [:wa-details {:class      "stats-methodology"
                :summary    (tr [:stats/methodology-title])
                :appearance "outlined"}
   [:div {:class "wa-stack wa-gap-s stats-methodology-body"}
    [:section {:class "wa-stack wa-gap-2xs"}
     [:h3 {:class "wa-heading-s"} (tr [:stats/methodology-rates-title])]
     [:p (tr [:stats/methodology-rates-body])]]
    [:section {:class "wa-stack wa-gap-2xs"}
     [:h3 {:class "wa-heading-s"} (tr [:stats/methodology-active-members-title])]
     [:p (tr [:stats/methodology-active-members-body])]]
    [:section {:class "wa-stack wa-gap-2xs"}
     [:h3 {:class "wa-heading-s"} (tr [:stats/methodology-histograms-title])]
     [:p (tr [:stats/methodology-histograms-body])]]
    [:section {:class "wa-stack wa-gap-2xs"}
     [:h3 {:class "wa-heading-s"} (tr [:stats/methodology-accuracy-title])]
     [:p (tr [:stats/methodology-accuracy-body])]]]])

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

(defn- member-avatar [{:member/keys [name] :as member}]
  (let [src (avatar-src member)]
    [:wa-avatar (cond-> {:shape "rounded"
                         :label name
                         :style "--size: 2rem; flex: none;"}
                  src (assoc :image src))
     (when-not src
       [ico/Icon {::ico/library :snoico
                  ::ico/name    :user
                  :slot         "icon"}])]))

(defn- member-row [{:keys [tr] :as req} {:keys [member gigs-attended probes-attended last-seen gig-rate probe-rate gig-title]}]
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
    [:span {:class "stats-mobile-stat-label"} (tr [:stats/gigs-short])]
    [:span (fmt-percent gig-rate) " (" (fmt-count gigs-attended) ")"]]
   [:td {:class "stats-table-col--mobile"}
    [:span {:class "stats-mobile-stat-label"} (tr [:stats/probes-short])]
    [:span (fmt-percent probe-rate) " (" (fmt-count probes-attended) ")"]]])

(defn- member-table [{:keys [tr] :as req} per-member-stats]
  (ui2/section-card
   {:title    (tr [:stats/member-table-title])
    :subtitle (tr [:stats/member-table-subtitle])}
   (ui2/table-shell
    (if (seq per-member-stats)
      [:table {:class "stats-member-table"}
       [:thead
        [:tr
         (sortable-header req :member/name (tr [:member/name]) nil)
         (sortable-header req :gigs-attended (tr [:stats/gigs-attended]) (tr [:stats/gigs-attended-tooltip]))
         (sortable-header req :gig-rate (tr [:stats/gigs-percent]) (tr [:stats/gigs-percent-tooltip]))
         (sortable-header req :probes-attended (tr [:stats/probes-attended]) (tr [:stats/probes-attended-tooltip]))
         (sortable-header req :probe-rate (tr [:stats/probes-percent]) (tr [:stats/probes-percent-tooltip]))
         (sortable-header req :last-seen (tr [:stats/last-seen]) (tr [:stats/last-seen-tooltip]))
         (mobile-stat-header "stats-mobile-gigs" (tr [:stats/gigs-short]) (tr [:stats/gigs-percent-tooltip]))
         (mobile-stat-header "stats-mobile-probes" (tr [:stats/probes-short]) (tr [:stats/probes-percent-tooltip]))]]
       [:tbody
        (for [member-stat per-member-stats]
          (member-row req member-stat))]]
      (ui2/empty-state
       (tr [:stats/member-table-empty-title])
       (tr [:stats/member-table-empty-body]))))))

(defn page [{:keys [db tr] :as req}]
  (let [{:keys [from to]} (state/selected-range req)
        stats            (stats/stats-for db from to (state/sort-spec req))]
    (ui2/datastar-page
     [:script {:src "/vendor/chart.js@4.4.0/chart.umd.js"}]
     [:script {:src "/vendor/chartjs-plugin-datalabels@2.2.0/chartjs-plugin-datalabels.min.js"}]
     [:script {:src "/js/widgets/stats-chart.js"}]
     [:div {:class "wa-stack wa-gap-xl"}
      (ui2/page-header
       {:title    (tr [:stats/title])
        :subtitle (tr [:stats/current-range]
                      [(ui2/format-date req :medium from)
                       (ui2/format-date req :medium to)])
        :actions  [(timespan-controls req)]})
      (summary-card tr stats)
      (charts-section tr stats)
      (methodology req)
      (member-table req (:per-member-stats stats))])))
