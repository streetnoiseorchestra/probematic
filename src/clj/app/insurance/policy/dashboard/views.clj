(ns app.insurance.policy.dashboard.views
  (:require
   [app.datastar :as d*]
   [app.html :as html]
   [app.insurance.domain :as domain]
   [app.insurance.policy.dashboard.queries :as queries]
   [app.insurance.ui :as insurance-ui]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.avatar :as avatar]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
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

(defn- page-header
  [{:keys [tr]} {:insurance.policy/keys [name status]}]
  [page-header/PageHeader
   {:title [:span {:class "wa-cluster"}
            name
            (policy-status-badge tr status)]}])

(defn- policy-toolbar
  [{:keys [tr]} {:keys [insurance-team-member? policy status-counts survey-progress]}]
  (let [status          (:insurance.policy/status policy)
        draft?          (= :insurance.policy.status/draft status)
        sent?           (= :insurance.policy.status/sent status)
        active?         (= :insurance.policy.status/active status)
        review-todos?   (pos? (get status-counts
                                   :instrument.coverage.status/needs-review
                                   0))
        primary-action  (cond
                          (not insurance-team-member?)                      :add-coverage
                          (and insurance-team-member? draft? review-todos?) :review
                          (and insurance-team-member? draft?)               :send-changes
                          (and insurance-team-member? sent?)                :workbench
                          (and insurance-team-member? active?)               :request-payments
                          :else                                             :add-coverage)
        survey-management? (and insurance-team-member? survey-progress)
        action-configs  {:add-coverage    {:href  (urls/link-coverage-create
                                                   (:insurance.policy/policy-id policy))
                                           :icon  :plus-circle
                                           :label [:i18n/tr :insurance/add-coverage]}
                         :review          {:href  (urls/link-policy-review policy)
                                           :icon  :hand-pointing
                                           :label [:i18n/tr :insurance/review]}
                         :send-changes     {:href  (urls/link-policy-changes policy)
                                            :icon  :paper-plane-right
                                            :label [:i18n/tr :insurance/send-changes]}
                         :workbench        {:href  (urls/link-policy-workbench policy)
                                            :icon  :table
                                            :label [:i18n/tr :insurance/workbench]}
                         :request-payments {:href  (urls/link-policy-send-notifications policy)
                                            :icon  :bell-ringing
                                            :label [:i18n/tr :insurance/request-payments-title]}}
        primary-config  (get action-configs primary-action)
        review-config   (:review action-configs)
        workbench-config (:workbench action-configs)
        review-item     [:wa-dropdown-item
                         {:value   (:href review-config)
                          :onclick "window.location = this.value"}
                         [ico/Icon {::ico/library :phosphor
                                    ::ico/name    (:icon review-config)
                                    :slot         "icon"}]
                         (:label review-config)]
        primary-overflow-item
        [:wa-dropdown-item
         {:value   (:href primary-config)
          :onclick "window.location = this.value"}
         [ico/Icon {::ico/library :phosphor
                    ::ico/name    (:icon primary-config)
                    :slot         "icon"}]
         (:label primary-config)]
        overflow-items  (vec
                         (concat
                          (when survey-management?
                            [primary-overflow-item])
                          (when (and insurance-team-member?
                                     (not= :review primary-action))
                            [review-item])
                          (when (and insurance-team-member?
                                     (not= :workbench primary-action))
                            [[:wa-dropdown-item
                              {:value   (:href workbench-config)
                               :onclick "window.location = this.value"}
                              [ico/Icon {::ico/library :phosphor
                                         ::ico/name    (:icon workbench-config)
                                         :slot         "icon"}]
                              (:label workbench-config)]])
                          (when (not= :add-coverage primary-action)
                            [[:wa-dropdown-item
                              {:value   (urls/link-coverage-create (:insurance.policy/policy-id policy))
                               :onclick "window.location = this.value"}
                              [ico/Icon {::ico/library :phosphor
                                         ::ico/name    :plus-circle
                                         :slot         "icon"}]
                              [:i18n/tr :insurance/add-coverage]]])
                          (when insurance-team-member?
                            [[:wa-dropdown-item
                              {:value   (urls/link-policy-settings policy)
                               :onclick "window.location = this.value"}
                              [ico/Icon {::ico/library :phosphor
                                         ::ico/name    :gear
                                         :slot         "icon"}]
                              [:i18n/tr :insurance/policy-settings]]])
                          (when (and insurance-team-member?
                                     (nil? survey-progress))
                            [[:wa-dropdown-item
                              {:value   (urls/link-policy-surveys policy)
                               :onclick "window.location = this.value"}
                              [ico/Icon {::ico/library :phosphor
                                         ::ico/name    :clipboard-text
                                         :slot         "icon"}]
                              [:i18n/tr :insurance/manage-surveys]]])
                          (when (and insurance-team-member? draft? review-todos?)
                            [[:wa-dropdown-item
                              {:disabled true
                               :title    (tr [:insurance/send-changes-disabled-hint])}
                              [ico/Icon {::ico/library :phosphor
                                         ::ico/name    :paper-plane-right
                                         :slot         "icon"}]
                              [:i18n/tr :insurance/send-changes]]])))
        primary-button  [button/Button {:appearance "filled"
                                        :variant    "brand"
                                        :href       (:href primary-config)}
                         [ico/Icon {::ico/library :phosphor
                                    ::ico/name    (:icon primary-config)
                                    :slot         "start"}]
                         (:label primary-config)]
        manage-surveys-button
        (when (and insurance-team-member? survey-progress)
          [button/Button {:appearance "outlined"
                          :variant    "brand"
                          :href       (urls/link-policy-surveys policy)}
           [ico/Icon {::ico/library :phosphor
                      ::ico/name    :clipboard-text
                      :slot         "start"}]
           [:i18n/tr :insurance/manage-surveys]])]
    [page-toolbar/PageToolbar
     {::page-toolbar/breadcrumb
      [breadcrumb/Breadcrumb
       {}
       [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
        [:i18n/tr :insurance/title]]
       [breadcrumb/BreadcrumbItem (:insurance.policy/name policy)]]
      ::page-toolbar/mobile-back
      [button/BackButton {:href  (urls/link-insurance)
                          :label [:i18n/tr :insurance/title]}]
      ::page-toolbar/actions        [(or manage-surveys-button primary-button)]
      ::page-toolbar/overflow-label [:i18n/tr :action/more-actions]
      ::page-toolbar/overflow-items overflow-items
      :aria-label                    [:i18n/tr :insurance/toolbar-label]}]))

(defn metric-card
  [{:keys [id tooltip icon label value library]}]
  [card/Card {:style "flex: auto;"}
   [:div {:class "wa-flank wa-align-items-start"}
    [avatar/Avatar {::avatar/icon icon
                    ::avatar/icon-library (or library :phosphor)
                    ::avatar/icon-attrs {:class "wa-font-size-xl wa-color-text-quiet"
                                         :aria-hidden true}
                    :shape "rounded"}]
    [:div {:class "wa-stack wa-gap-2xs"}
     [:div {:class "wa-cluster wa-gap-xs"}
      [:h3 {:class "wa-caption-s"} label]
      (when tooltip
        (ui2/square-info id))]
     (when tooltip
       [:wa-tooltip {:for id :without-arrow true} tooltip])
     [:div
      {:class "wa-cluster wa-gap-xs"}
      [:span {:class "wa-heading-xl"} value]]]]])

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
   [:dl {:class "wa-split"}
    [:dt label]
    [:dd value]]])

(defn- detail-row
  [label value]
  [:dl {:class "wa-split wa-gap-m"}
   [:dt label]
   [:dd {:style "min-inline-size: 0; overflow-wrap: anywhere; text-align: end;"}
    value]])

(defn- dashboard-card-header
  [{:keys [subtitle title]}]
  (if subtitle
    [:div {:slot  "header"
           :class "wa-stack wa-gap-2xs"}
     [:h2 {:class "wa-heading-l"
           :style "margin: 0;"}
      title]
     [:p {:class "wa-caption-s wa-color-text-quiet"
          :style "margin: 0;"}
      subtitle]]
    [:h2 {:slot  "header"
          :class "wa-heading-l"
          :style "margin: 0;"}
     title]))

(defn dashboard-card
  [{:keys [class header-actions subtitle title]} & children]
  (let [attrs {:appearance "plain"
               :class      (ui2/cs "insurance-dashboard-card" class)
               :style      "background: var(--wa-color-surface-default); block-size: 100%"}]
    (cond-> [card/Card attrs
             (dashboard-card-header {:subtitle subtitle
                                     :title    title})]
      header-actions (conj header-actions)
      true (conj (into [:div {:class "wa-stack"}] children)))))

(defn- policy-card-action
  [{:keys [enabled? href icon label]}]
  [button/Button (cond-> {:slot       "header-actions"
                          :appearance "plain"
                          :variant    "brand"
                          :title      label
                          :aria-label label}
                   enabled?       (assoc :href href)
                   (not enabled?) (assoc :disabled true))
   [ico/Icon {::ico/library :phosphor
              ::ico/name    icon}]])

(defn- policy-review-action
  [{:keys [tr]} policy enabled?]
  (let [label (tr [:insurance.dashboard/continue-reviewing])]
    (policy-card-action {:enabled? enabled?
                         :href     (urls/link-policy-review policy)
                         :icon     :hand-pointing
                         :label    label})))

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
        color (insurance-ui/status-color status)]
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
  (let [statuses (filter #(pos? (get status-counts % 0)) domain/instrument-coverage-review-progress-statuses)]
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
  (dashboard-row (legend-marker (insurance-ui/status-color status))
                 (tr [status])
                 (get status-counts status 0)))

(defn review-status-section
  [{:keys [tr] :as req} {:keys [insurance-team-member? policy status-counts totals]}]
  (let [total         (:total-instruments totals)
        needs-review  (get status-counts :instrument.coverage.status/needs-review 0)
        handled       (- total needs-review)
        handled-ratio (if (pos? total) (/ (double handled) total) 0.0)
        handled-label (tr [:insurance.dashboard/review-complete] [handled total])]
    (apply dashboard-card
           {:title          (tr [:insurance.dashboard/review-status])
            :header-actions (policy-review-action req
                                                  policy
                                                  (and insurance-team-member?
                                                       (pos? needs-review)))}
           (concat
            [(review-status-bar status-counts total handled-ratio handled-label)]
            (divided-rows
             (map #(review-status-legend-item tr status-counts %) domain/instrument-coverage-review-progress-statuses))
            [[:div {:class "wa-caption-s wa-text-end"}
              handled-label]]))))

(defn survey-progress-section
  [{:keys [tr]} {:keys [insurance-team-member? policy survey-progress]}]
  (when survey-progress
    (let [{:keys [completed-count total-count waiting-count]} survey-progress
          completion-ratio (if (pos? total-count)
                             (/ (double completed-count) total-count)
                             0.0)
          progress-label   [:i18n/tr
                            :insurance/survey-progress-summary
                            {:completed completed-count
                             :total     total-count}]]
      (apply dashboard-card
             {:class          "insurance-dashboard-survey-progress-card"
              :title          [:i18n/tr :insurance/survey-responses-title]
              :header-actions (policy-card-action
                               {:enabled? insurance-team-member?
                                :href     (urls/link-policy-surveys policy)
                                :icon     :clipboard-text
                                :label    (tr [:insurance/manage-surveys])})}
             (concat
              [[:div {:class         "insurance-dashboard-survey-progress"
                      :style         {"--progress-value" (width-style completed-count total-count)}
                      :role          "progressbar"
                      :aria-valuemin 0
                      :aria-valuemax 100
                      :aria-valuenow (long (Math/round (double (* completion-ratio 100))))
                      :aria-label    progress-label}
                [:span {:aria-hidden "true"}]
                [:wa-format-number
                 {:class                   "insurance-dashboard-survey-progress-value"
                  :aria-hidden             "true"
                  :type                    "percent"
                  :value                   completion-ratio
                  :minimum-fraction-digits 0
                  :maximum-fraction-digits 0}]]]
              (divided-rows
               [(dashboard-row
                 (legend-marker "var(--wa-color-success-fill-loud)")
                 [:i18n/tr :insurance/survey-complete]
                 completed-count)
                (dashboard-row
                 (legend-marker "var(--wa-color-neutral-fill-loud)")
                 [:i18n/tr :insurance/survey-incomplete]
                 waiting-count)])
              [[:div {:class "wa-caption-s wa-text-end"}
                progress-label]])))))

(def health-checks
  [{:label-key [:insurance.dashboard/missing-photos]
    :count-key :missing-photo-count}
   {:label-key [:insurance.dashboard/missing-insurer-ids]
    :count-key :missing-insurer-id-count}
   {:label-key     [:insurance.policy-settings/missing-category-factors-title]
    :count-key     :missing-category-factor-count
    :settings-link? true}])

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
  [tr totals policy insurance-team-member? {:keys [count-key label-key settings-link?] :as check}]
  (let [count    (get totals count-key 0)
        healthy? (health-check-passed? totals check)
        label    (tr label-key)
        label    (if (and settings-link? insurance-team-member? (pos? count))
                   [:a {:href (urls/link-policy-settings policy)} label]
                   label)]
    (dashboard-row (health-check-icon healthy?)
                   label
                   count)))

(defn health-checklist-section
  [{:keys [tr] :as req} {:keys [insurance-team-member? policy totals]}]
  (let [total        (count health-checks)
        passed       (count (filter #(health-check-passed? totals %) health-checks))
        passed-label (tr [:insurance.dashboard/health-checks-complete] [passed total])]
    (apply dashboard-card
           {:title          (tr [:insurance.dashboard/health-checklist])
            :subtitle       (tr [:insurance.dashboard/health-checklist-subtitle])
            :header-actions (policy-review-action req
                                                  policy
                                                  (and insurance-team-member?
                                                       (< passed total)))}
           (concat
            (divided-rows
             (map #(health-check-row tr totals policy insurance-team-member? %) health-checks))
            [[:div {:class "wa-caption-s wa-text-end"}
              passed-label]]))))

(defn- round-up-to
  [value step]
  (* step (long (Math/ceil (/ (double value) step)))))

(defn- rounded-count-max
  [value]
  (let [maximum (max 0.0 (double (or value 0)))]
    (cond
      (zero? maximum) 10
      (<= maximum 20) (round-up-to maximum 5)
      (<= maximum 100) (round-up-to maximum 25)
      :else (round-up-to maximum 100))))

(defn- rounded-money-max
  [value]
  (let [maximum (max 0.0 (double (or value 0)))]
    (if (zero? maximum)
      10
      (let [power       (Math/pow 10 (Math/floor (Math/log10 maximum)))
            scaled      (/ maximum power)
            nice-scaled (cond
                          (<= scaled 1) 1
                          (<= scaled 2) 2
                          (<= scaled 2.5) 2.5
                          (<= scaled 5) 5
                          :else 10)]
        (* nice-scaled power)))))

(defn- coverage-mix-total
  [values]
  (reduce + 0M (map #(or % 0M) values)))

(defn- coverage-mix-dataset
  [{:keys [axis-id color data label measure]}]
  {:label              label
   :data               data
   :measure            measure
   :xAxisID            axis-id
   :backgroundColor    color
   :borderColor        "transparent"
   :borderSkipped      false
   :borderWidth        0
   :borderRadius       4
   :barPercentage      0.7
   :categoryPercentage 0.75
   :stack              measure})

(defn- coverage-mix-wa-chart-data
  [tr currency {:keys [band-count band-cost private-count private-cost]}]
  (let [band-count    (or band-count 0)
        private-count (or private-count 0)
        band-cost     (or band-cost 0M)
        private-cost  (or private-cost 0M)
        counts        [band-count private-count]
        costs         [band-cost private-cost]
        count-label   (tr [:insurance/item-count])
        cost-label    (tr [:insurance/cost])
        currency-code (or (some-> currency name) "EUR")
        cost-title    (str cost-label " " (ui2/currency-symbol currency))]
    {:type    "bar"
     :data    {:labels   [count-label cost-label]
               :datasets [(coverage-mix-dataset {:label   (tr [:insurance.dashboard/band-instruments])
                                                 :data    [band-count nil]
                                                 :measure "count"
                                                 :axis-id "count"
                                                 :color   (:band coverage-mix-colors)})
                          (coverage-mix-dataset {:label   (tr [:insurance.dashboard/private-instruments])
                                                 :data    [private-count nil]
                                                 :measure "count"
                                                 :axis-id "count"
                                                 :color   (:private coverage-mix-colors)})
                          (coverage-mix-dataset {:label   (tr [:insurance.dashboard/band-instruments])
                                                 :data    [nil band-cost]
                                                 :measure "cost"
                                                 :axis-id "cost"
                                                 :color   (:band coverage-mix-colors)})
                          (coverage-mix-dataset {:label   (tr [:insurance.dashboard/private-instruments])
                                                 :data    [nil private-cost]
                                                 :measure "cost"
                                                 :axis-id "cost"
                                                 :color   (:private coverage-mix-colors)})]}
     :options {:indexAxis           "y"
               :responsive          true
               :maintainAspectRatio false
               :animation           false
               :interaction         {:mode "index" :intersect false}
               :plugins             {:legend  {:display false}
                                     :tooltip {:enabled false}}
               :scales              {:count {:type         "linear"
                                             :axis         "x"
                                             :position     "top"
                                             :stacked      true
                                             :beginAtZero  true
                                             :suggestedMax (rounded-count-max (coverage-mix-total counts))
                                             :title        {:display true
                                                            :text    count-label}
                                             :ticks        {:precision 0}
                                             :grid         {:drawOnChartArea false}}
                                     :cost  {:type         "linear"
                                             :axis         "x"
                                             :position     "bottom"
                                             :stacked      true
                                             :beginAtZero  true
                                             :suggestedMax (rounded-money-max (coverage-mix-total costs))
                                             :title        {:display true
                                                            :text    cost-title}
                                             :ticks        {:format {:style                 "currency"
                                                                     :currency              currency-code
                                                                     :maximumFractionDigits 0}}}
                                     :x     {:display false
                                             :grid    {:display false}}
                                     :y     {:stacked true
                                             :grid    {:display false}}}}}))

(defn- coverage-mix-wa-chart-signals
  [tr currency totals]
  {:insuranceDashboard
   {:coverageMixChartJson
    (j/write-value-as-string (coverage-mix-wa-chart-data tr currency totals))}})

(defn- coverage-mix-caption
  [_tr count cost currency]
  [:span {:class "trim-cap"}
   [:span    (or count 0)]
   [divider/Divider {::divider/orientation :vertical :style "min-block-size: 0.8lh"}]
   [:span (ui2/money-format (or cost 0M) currency)]])

(defn- coverage-mix-caption-row
  [label color caption]
  [:div {:class "wa-flank"}
   (legend-marker color)
   [:dl {:class "wa-split wa-gap-m"}
    [:dt label]
    [:dd caption]]])

(defn coverage-mix-section
  [{:keys [tr]} {:keys [policy totals]}]
  (let [currency          (:insurance.policy/currency policy)
        chart-description (tr [:insurance.dashboard/coverage-mix-subtitle])]
    (apply dashboard-card
           {:title    (tr [:insurance.dashboard/coverage-mix])
            :subtitle (tr [:insurance.dashboard/coverage-mix-subtitle])}
           (concat
            [[:div {:data-signals (d*/->signals (coverage-mix-wa-chart-signals tr currency totals))}
              [:wa-chart {:description       chart-description
                          :without-legend    true
                          :without-animation true
                          :data-effect       "const chartConfigJson = $insuranceDashboard.coverageMixChartJson; customElements.whenDefined('wa-chart').then(() => { el.config = JSON.parse(chartConfigJson) })"
                          :style             "display: block; block-size: 13rem; inline-size: var(--sno-size-full);"}]]]
            (divided-rows
             [(coverage-mix-caption-row
               (tr [:insurance.dashboard/band-instruments])
               (:band coverage-mix-colors)
               (coverage-mix-caption tr (:band-count totals) (:band-cost totals) currency))
              (coverage-mix-caption-row
               (tr [:insurance.dashboard/private-instruments])
               (:private coverage-mix-colors)
               (coverage-mix-caption tr (:private-count totals) (:private-cost totals) currency))])))))

(defn- change-row
  [tr currency {:keys [change coverage instrument-name owner-name]}]
  (let [color (insurance-ui/change-color change)]
    [:div {:class "wa-flank"}
     (legend-marker (or color "var(--wa-color-neutral-fill-loud)"))
     [:div {:class "wa-split"}
      [:div {:class "wa-stack wa-gap-3xs" :style "min-inline-size: 0;"}
       [:a {:href (urls/link-coverage coverage)} instrument-name]
       [:span {:class "wa-caption-s wa-color-text-quiet"} owner-name]]
      [:div {:class "wa-stack wa-gap-3xs wa-align-items-end"}
       [:dl {:class                        "wa-cluster wa-gap-2xs"
             :data-dashboard-coverage-cost true}
        [:dt {:class "wa-caption-s wa-color-text-quiet"}
         (tr [:instrument.coverage/cost])]
        [:dd (ui2/money (:instrument.coverage/cost coverage) currency)]]
       (insurance-ui/change-badge tr change)]]]))

(defn- recent-changes-section
  [{:keys [tr]} {:keys [policy recent-changes]}]
  (let [currency (:insurance.policy/currency policy)]
    (apply dashboard-card
           {:title    (tr [:insurance.dashboard/recent-changes])
            :subtitle (tr [:insurance.dashboard/recent-changes-subtitle])}
           (if (seq recent-changes)
             (divided-rows
              (map #(change-row tr currency %) (take 8 recent-changes)))
             [[:div {:class "wa-caption-s wa-color-text-quiet"}
               (tr [:insurance.dashboard/no-recent-changes])]]))))

(defn- policy-details-section
  [{:keys [tr] :as req} {:keys [insurance-team-member? policy]}]
  (let [{:insurance.policy/keys [effective-at effective-until premium-factor status]} policy]
    (apply dashboard-card
           {:title          (tr [:insurance.dashboard/policy-details])
            :header-actions (policy-card-action
                             {:enabled? insurance-team-member?
                              :href     (urls/link-policy-settings policy)
                              :icon     :gear
                              :label    (tr [:insurance/policy-settings])})}
           (divided-rows
            [(detail-row (tr [:insurance/name]) (:insurance.policy/name policy))
             (detail-row (tr [:insurance.dashboard/policy-status]) (policy-status-badge tr status))
             (detail-row (tr [:insurance/effective-at]) (ui2/date-display req :medium effective-at))
             (detail-row (tr [:insurance/effective-until]) (ui2/date-display req :medium effective-until))
             (detail-row (tr [:insurance/premium-base-factor]) premium-factor)]))))

(defn page
  [{:keys [db] :as req}]
  (let [dashboard (queries/policy-dashboard
                   db
                   (policy-id req)
                   {:current-member-id (get-in req [:session :session/member :member/member-id])})
        policy    (:policy dashboard)]
    (ui2/datastar-page*
     [:script {:type "module"}
      (html/raw "import 'wa/components/chart/chart.js';")]
     [page-surface/PageSurface
      {::page-surface/width :wide
       ::page-surface/toolbar (policy-toolbar req dashboard)}
      [:div {:class "wa-stack"}
       (page-header req policy)
       (overview-section req dashboard)
       [:div {:class "wa-flank:end wa-align-items-start" :style "--flank-size: 42ch;"}
        [:div {:class "leading-none wa-grid wa-align-items-start" :style "--min-column-size: 30ch;"}
         (policy-details-section req dashboard)
         (survey-progress-section req dashboard)
         (review-status-section req dashboard)
         (health-checklist-section req dashboard)
         (coverage-mix-section req dashboard)]
        [:aside {:class "leading-none wa-grid wa-align-items-start" :style "--min-column-size: 30ch;"}
         (recent-changes-section req dashboard)]]]])))

(d*/refresh-all!)
