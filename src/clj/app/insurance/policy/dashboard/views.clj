(ns app.insurance.policy.dashboard.views
  (:require
   [app.datastar :as d*]
   [app.html :as html]
   [app.insurance.policy.dashboard.queries :as queries]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [jsonista.core :as j]))

(def policy-status-data
  {:insurance.policy.status/active {:icon    "circle-check-outline"
                                    :color   "var(--wa-color-success-fill-loud)"
                                    :variant "success"}
   :insurance.policy.status/sent   {:icon    "envelope"
                                    :color   "var(--wa-color-warning-fill-loud)"
                                    :variant "warning"}
   :insurance.policy.status/draft  {:icon    "circle-dot-outline"
                                    :color   "var(--sno-gig-row-gray-400)"
                                    :variant "neutral"}})

(def workflow-status-data
  {:instrument.coverage.status/needs-review    {:icon    "circle-question-outline"
                                                :color   "var(--sno-dashboard-insurance-todo-needs-review-color, var(--wa-color-warning-fill-loud))"
                                                :variant "warning"}
   :instrument.coverage.status/reviewed        {:icon    "circle-dot-outline"
                                                :color   "var(--sno-gig-row-gray-400)"
                                                :variant "neutral"}
   :instrument.coverage.status/coverage-active {:icon    "circle-check-outline"
                                                :color   "var(--wa-color-success-fill-loud)"
                                                :variant "success"}})

(def change-status-data
  {:instrument.coverage.change/changed {:color   "var(--wa-color-warning-fill-loud)"
                                        :variant "warning"}
   :instrument.coverage.change/new     {:color   "var(--wa-color-success-fill-loud)"
                                        :variant "success"}
   :instrument.coverage.change/removed {:color   "var(--wa-color-danger-fill-loud)"
                                        :variant "danger"}
   :instrument.coverage.change/none    {:color   "var(--wa-color-neutral-fill-loud)"
                                        :variant "neutral"}})

(defn- policy-id
  [{:keys [parameters path-params]}]
  (let [value (or (get-in parameters [:path :policy-id])
                  (:policy-id path-params))]
    (cond
      (uuid? value) value
      (string? value) (parse-uuid value))))

(defn- policy-status-badge
  [tr status]
  (let [{:keys [variant]} (policy-status-data status)]
    [:wa-badge {:appearance "outlined"
                :variant    variant
                :pill       true}
     (tr [status])]))

(defn- change-badge
  [tr change]
  (let [{:keys [variant]} (change-status-data change)]
    [:wa-badge {:appearance "outlined"
                :variant    variant
                :pill       true}
     (tr [change])]))

(defn- page-breadcrumb
  [{:keys [tr]} policy]
  [breadcrumb/Breadcrumb
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :shield-check-outline}]
    (tr [:nav/insurance])]
   [breadcrumb/BreadcrumbItem (:insurance.policy/name policy)]])

(defn- page-header
  [{:keys [tr] :as req} {:insurance.policy/keys [name status] :as policy}]
  [:div
   (ui2/page-header
    {:breadcrumb (page-breadcrumb req policy)
     :heading    [:div {:class "wa-flank wa-align-items-center"}
                  (policy-status-badge tr status)
                  [:h1 name]]
     :actions    [[button/Button {:appearance "filled"
                                  :variant    "brand"
                                  :href       "#"}
                   [ico/Icon {::ico/library :phosphor
                              ::ico/name    :hand-pointing
                              :slot         "start"}]
                   (tr [:insurance.dashboard/continue-reviewing])]
                  [button/Button {:appearance "outlined"
                                  :variant    "brand"
                                  :href       "#"}
                   [ico/Icon {::ico/library :phosphor
                              ::ico/name    :table
                              :slot         "start"}]
                   (tr [:insurance.dashboard/coverage-workbench])]]})
   [divider/Divider]])

(defn metric-card
  [{:keys [id tooltip icon label value library]}]
  [:wa-card {:style "flex: auto;"}
   [:div {:class "wa-flank wa-align-items-start"}
    [:wa-avatar {:shape "rounded"}
     [ico/Icon {::ico/library (or library :phosphor)
                ::ico/name    icon
                :slot         "icon"
                :class        "wa-font-size-xl wa-color-text-quiet"
                :aria-hidden  true}]]
    [:div {:class "wa-stack wa-gap-2xs"}
     [:div {:class "wa-cluster wa-gap-xs"}
      [:h3 {:class "wa-caption-s"} label]
      (when tooltip
        (ui2/square-info id))]
     (when tooltip
       [:wa-tooltip {:for id :without-arrow true} tooltip])
     [:div
      {:class "wa-cluster wa-gap-xs"}
      [:span {:class "wa-heading-xl"} value]
      #_[:wa-badge
         {:variant "success", :appearance "filled outlined", :pill ""}
         [:wa-icon {:name "arrow-up", :label "Up"}]
         "212"]]]]])

(defn- metric-grid
  [{:keys [tr] :as _req} {:keys [totals]} currency]
  [:div {:class "wa-cluster wa-gap-xl wa-align-items-start" :style "flex: auto;"}
   (metric-card {:icon    :trumpet
                 :library :snoico
                 :label   (tr [:insurance.dashboard/total-instruments])
                 :id      "metric-total-instruments"
                 :tooltip (tr [:insurance.dashboard/total-instruments-tooltip])
                 :value   (:total-instruments totals)})
   (metric-card {:icon    :bank
                 :label   (tr [:insurance.dashboard/total-insured-value])
                 :id      "metric-total-insured-value"
                 :tooltip (tr [:insurance.dashboard/total-insured-value-tooltip])
                 :value   (ui2/money (:total-insured-value totals) currency)})
   (metric-card {:icon    :currency-eur
                 :label   (tr [:insurance.dashboard/policy-cost])
                 :id      "metric-policy-cost"
                 :tooltip (tr [:insurance.dashboard/policy-cost-tooltip])
                 :value   (ui2/money (:total-cost totals) currency)})])

(defn- overview-section
  [req {:keys [policy totals]}]
  (metric-grid req {:totals totals} (:insurance.policy/currency policy)))

(def review-status-bar-statuses
  [:instrument.coverage.status/coverage-active
   :instrument.coverage.status/reviewed
   :instrument.coverage.status/needs-review])

(def status-colors
  {:instrument.coverage.status/coverage-active "var(--wa-color-success-fill-loud)"
   :instrument.coverage.status/reviewed        "var(--wa-color-neutral-fill-loud)"
   :instrument.coverage.status/needs-review    "var(--sno-dashboard-insurance-todo-needs-review-color, var(--wa-color-warning-fill-loud))"})

(def coverage-mix-colors
  {:band    "var(--wa-color-success-fill-loud)"
   :private "var(--wa-color-warning-fill-loud)"})

(defn- percent-value
  [part total]
  (if (pos? total)
    (* 100.0 (/ (double part) total))
    0.0))

(defn- width-style
  [part total]
  (str (percent-value part total) "%"))

(defn- legend-marker
  [color]
  [:div {:style (str "background: " color ";"
                     " height: var(--wa-font-size-s);"
                     " width: var(--wa-font-size-s);"
                     " border-radius: var(--wa-space-2xs);")}])

(defn- dashboard-row
  [marker label value]
  [:div {:class "wa-flank"}
   marker
   [:div {:class "wa-split"}
    [:div label]
    [:strong value]]])

(defn- detail-row
  [label value]
  [:div {:class "wa-split wa-gap-m"}
   [:div {:class "wa-caption-s wa-color-text-quiet"} label]
   [:div {:style "min-inline-size: 0; overflow-wrap: anywhere; text-align: end;"}
    value]])

(defn- dashboard-card
  [{:keys [class subtitle title]} & children]
  (let [body  (cond-> [:div {:class "wa-stack"}
                       [:div {:class "wa-cluster wa-gap-xs"}
                        [:h2 {:class "wa-heading-l"} title]]]
                subtitle (conj [:div {:class "wa-caption-s wa-color-text-quiet"} subtitle]))
        attrs (cond-> {:appearance "plain"
                       :style      "background: var(--wa-color-surface-default); block-size: 100%"}
                class (assoc :class class))]
    [:wa-card attrs
     (into body children)]))

(defn- divided-rows
  [rows]
  (mapcat (fn [row]
            [row [divider/Divider]])
          rows))

(defn- bar-segment-style
  [color width first? last?]
  (str (when first?
         "border-top-left-radius: var(--wa-space-2xs); border-bottom-left-radius: var(--wa-space-2xs); ")
       (when last?
         "border-top-right-radius: var(--wa-space-2xs); border-bottom-right-radius: var(--wa-space-2xs); ")
       "background-color: " color ";"
       " height: 1.5rem;"
       " width: " width ";"
       " font-size: var(--wa-font-size-s);"
       " text-align: center;"))

(defn- review-status-segment
  [{:keys [first? handled-ratio last? status status-counts total]}]
  (let [count (get status-counts status 0)
        color (status-colors status)]
    [:div {:style (bar-segment-style color (width-style count total) first? last?)}
     (when first?
       [:wa-format-number {:type                    "percent"
                           :value                   handled-ratio
                           :minimum-fraction-digits 0
                           :maximum-fraction-digits 0
                           :class                   "wa-font-weight-bold"
                           :style                   "line-height: 1.7; vertical-align: middle;"}])]))

(defn- review-status-bar
  [status-counts total handled-ratio handled-label]
  (let [statuses (filter #(pos? (get status-counts % 0)) review-status-bar-statuses)]
    [:div {:class         "wa-cluster wa-gap-0"
           :style         "padding: var(--wa-space-2xs) 0"
           :role          "progressbar"
           :aria-valuemin 0
           :aria-valuemax 100
           :aria-valuenow (long (Math/round (double (* handled-ratio 100))))
           :aria-label    handled-label}
     (if (seq statuses)
       (for [status statuses]
         (review-status-segment {:first?        (= status (first statuses))
                                 :handled-ratio handled-ratio
                                 :last?         (= status (last statuses))
                                 :status        status
                                 :status-counts status-counts
                                 :total         total}))
       [:div {:style (bar-segment-style "var(--wa-color-neutral-fill-normal)"
                                        "100%"
                                        true
                                        true)}])]))

(defn- review-status-legend-item
  [tr status-counts status]
  (dashboard-row (legend-marker (status-colors status))
                 (tr [status])
                 (get status-counts status 0)))

(defn- review-status-section
  [{:keys [tr]} {:keys [status-counts totals]}]
  (let [total         (:total-instruments totals)
        needs-review  (get status-counts :instrument.coverage.status/needs-review 0)
        handled       (- total needs-review)
        handled-ratio (if (pos? total) (/ (double handled) total) 0.0)
        handled-label (tr [:insurance.dashboard/review-complete] [handled total])]
    (apply dashboard-card
           {:title (tr [:insurance.dashboard/review-status])}
           (concat
            [(review-status-bar status-counts total handled-ratio handled-label)]
            (divided-rows
             (map #(review-status-legend-item tr status-counts %) review-status-bar-statuses))
            [[:div {:class "wa-caption-s wa-text-end"}
              handled-label]]))))

(def health-checks
  [{:label-key [:insurance.dashboard/missing-photos]
    :count-key :missing-photo-count}
   {:label-key [:insurance.dashboard/missing-insurer-ids]
    :count-key :missing-insurer-id-count}])

(defn- health-check-icon
  [healthy?]
  [ico/Icon {::ico/library :phosphor
             ::ico/name    (if healthy? :check :warning)
             :class        "wa-font-size-l"
             :style        (str "color: var("
                                (if healthy?
                                  "--wa-color-success-fill-loud"
                                  "--wa-color-danger-fill-loud")
                                ");")
             :aria-hidden  true}])

(defn- health-check-passed?
  [totals {:keys [count-key]}]
  (zero? (get totals count-key 0)))

(defn- health-check-row
  [tr totals {:keys [count-key label-key] :as check}]
  (let [count    (get totals count-key 0)
        healthy? (health-check-passed? totals check)]
    (dashboard-row (health-check-icon healthy?)
                   (tr label-key)
                   count)))

(defn- health-checklist-section
  [{:keys [tr]} {:keys [totals]}]
  (let [total        (count health-checks)
        passed       (count (filter #(health-check-passed? totals %) health-checks))
        passed-label (tr [:insurance.dashboard/health-checks-complete] [passed total])]
    (apply dashboard-card
           {:title    (tr [:insurance.dashboard/health-checklist])
            :subtitle (tr [:insurance.dashboard/health-checklist-subtitle])}
           (concat
            (divided-rows
             (map #(health-check-row tr totals %) health-checks))
            [[:div {:class "wa-caption-s wa-text-end"}
              passed-label]]))))

(defn- coverage-mix-chart-data
  [tr {:keys [band-count private-count]}]
  {:labels     [(tr [:insurance.dashboard/band-instruments])
                (tr [:insurance.dashboard/private-instruments])]
   :values     [(or band-count 0) (or private-count 0)]
   :emptyLabel (tr [:insurance.dashboard/no-covered-instruments])})

(defn- coverage-mix-legend-item
  [label color count]
  (dashboard-row (legend-marker color) label count))

(defn- coverage-mix-section
  [{:keys [tr]} {:keys [totals]}]
  (let [data-id "insurance-dashboard-coverage-mix-data"
        data    (coverage-mix-chart-data tr totals)
        total   (or (:total-instruments totals) 0)]
    (apply dashboard-card
           {:title    (tr [:insurance.dashboard/coverage-mix])
            :subtitle (tr [:insurance.dashboard/coverage-mix-subtitle])}
           (concat
            [[:script {:id   data-id
                       :type "application/json"}
              (html/raw (j/write-value-as-string data))]
             [:div {:style "position: relative; inline-size: min(var(--sno-size-full), 12rem); block-size: 12rem; margin-inline: auto;"}
              [:canvas {:class             "insurance-dashboard-pie-chart"
                        :data-ignore-morph true
                        :data-values       (str "#" data-id)
                        :role              "img"
                        :aria-label        (tr [:insurance.dashboard/coverage-mix])
                        :style             "inline-size: var(--sno-size-full); block-size: var(--sno-size-full);"}]]]
            (divided-rows
             [(coverage-mix-legend-item (tr [:insurance.dashboard/band-instruments])
                                        (:band coverage-mix-colors)
                                        (:band-count totals))
              (coverage-mix-legend-item (tr [:insurance.dashboard/private-instruments])
                                        (:private coverage-mix-colors)
                                        (:private-count totals))])
            [[:div {:class "wa-caption-s wa-text-end"}
              (tr [:insurance.dashboard/coverage-mix-total] [total])]]))))

(defn- future-action-row
  [tr {:keys [icon label-key]}]
  [:a {:href  "#"
       :class "wa-flank"
       :style "color: inherit; text-decoration: none;"}
   [ico/Icon {::ico/library :snoico
              ::ico/name    icon
              :class        "wa-font-size-xl wa-color-text-quiet"
              :aria-hidden  true}]
   [:div {:class "wa-split"}
    [:span (tr label-key)]
    [:wa-badge {:appearance "outlined" :pill true :variant "neutral"}
     (tr [:insurance.dashboard/opens-later])]]])

(defn- next-actions-section
  [{:keys [tr]}]
  (apply dashboard-card
         {:title    (tr [:insurance.dashboard/next-actions])
          :subtitle (tr [:insurance.dashboard/action-pages-subtitle])}
         (divided-rows
          [(future-action-row tr {:icon      "circle-question-outline"
                                  :label-key [:insurance.dashboard/review-queue]})
           (future-action-row tr {:icon      "circle-exclamation"
                                  :label-key [:insurance.dashboard/coverage-workbench]})
           (future-action-row tr {:icon      "circle-dot-outline"
                                  :label-key [:insurance.dashboard/activity-log]})
           (future-action-row tr {:icon      "cog"
                                  :label-key [:insurance.dashboard/policy-setup]})])))

(defn- change-row
  [tr {:keys [change coverage instrument-name owner-name]}]
  (let [{:keys [color]} (change-status-data change)]
    [:div {:class "wa-flank"}
     (legend-marker (or color "var(--wa-color-neutral-fill-loud)"))
     [:div {:class "wa-split"}
      [:div {:class "wa-stack wa-gap-3xs" :style "min-inline-size: 0;"}
       [:a {:href (urls/link-coverage coverage)} instrument-name]
       [:span {:class "wa-caption-s wa-color-text-quiet"} owner-name]]
      (change-badge tr change)]]))

(defn- recent-changes-section
  [{:keys [tr]} {:keys [recent-changes]}]
  (apply dashboard-card
         {:title    (tr [:insurance.dashboard/recent-changes])
          :subtitle (tr [:insurance.dashboard/recent-changes-subtitle])}
         (if (seq recent-changes)
           (divided-rows
            (map #(change-row tr %) (take 8 recent-changes)))
           [[:div {:class "wa-caption-s wa-color-text-quiet"}
             (tr [:insurance.dashboard/no-recent-changes])]])))

(defn- policy-details-section
  [{:keys [tr] :as req} {:insurance.policy/keys [effective-at effective-until premium-factor status] :as policy}]
  (apply dashboard-card
         {:title (tr [:insurance.dashboard/policy-details])}
         (divided-rows
          [(detail-row (tr [:insurance/name]) (:insurance.policy/name policy))
           (detail-row (tr [:insurance.dashboard/policy-status]) (policy-status-badge tr status))
           (detail-row (tr [:insurance/effective-at]) (ui2/date-display req :medium effective-at))
           (detail-row (tr [:insurance/effective-until]) (ui2/date-display req :medium effective-until))
           (detail-row (tr [:insurance/premium-base-factor]) premium-factor)])))

(defn page
  [{:keys [db] :as req}]
  (let [dashboard (queries/policy-dashboard db (policy-id req))
        policy    (:policy dashboard)]
    (ui2/datastar-page
     [:script {:src "/vendor/chart.js@4.4.0/chart.umd.js"}]
     [:script {:src "/vendor/chartjs-plugin-datalabels@2.2.0/chartjs-plugin-datalabels.min.js"}]
     [:script {:src "/js/widgets/insurance-dashboard-chart.js" :type "module"}]
     [:div {:class "wa-stack"}
      (page-header req policy)
      (overview-section req dashboard)
      [:div {:class "wa-flank:end wa-align-items-start" :style "--flank-size: 42ch;"}
       [:div {:class "wa-grid wa-align-items-start" :style "--min-column-size: 30ch;"}
        (policy-details-section req policy)
        (review-status-section req dashboard)
        (health-checklist-section req dashboard)
        (coverage-mix-section req dashboard)
        (next-actions-section req)]
       [:aside {:class "wa-grid wa-align-items-start" :style "--min-column-size: 30ch;"}
        (recent-changes-section req dashboard)]]])))

(d*/refresh-all!)
