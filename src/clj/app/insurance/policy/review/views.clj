(ns app.insurance.policy.review.views
  (:require
   [app.datastar :as d*]
   [app.insurance.coverage.queries :as coverage.queries]
   [app.insurance.policy.review.actions :as actions]
   [app.insurance.policy.review.queries :as queries]
   [app.insurance.ui :as insurance-ui]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [clojure.string :as str]))

(def filter-label-keys
  {:needs-review       [:insurance.review/filter-needs-review]
   :missing-insurer-id [:insurance.review/filter-missing-insurer-id]})
(defn- policy-id
  [{:keys [parameters path-params]}]
  (let [value (or (get-in parameters [:path :policy-id])
                  (:policy-id path-params))]
    (cond
      (uuid? value) value
      (string? value) (parse-uuid value))))

(defn- query-params
  [req]
  (let [params (or (get-in req [:parameters :query])
                   (:query-params req)
                   {})]
    {:filter      (or (:filter params) (get params "filter"))
     :coverage-id (or (:coverage-id params) (get params "coverage-id"))}))

(defn- page-breadcrumb
  [{:keys [tr]} policy]
  [breadcrumb/Breadcrumb
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :shield-check-outline}]
    (tr [:nav/insurance])]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
    (:insurance.policy/name policy)]
   [breadcrumb/BreadcrumbItem (tr [:insurance.review/title])]])

(defn- coverage-review-link
  [policy filter coverage]
  (urls/link-policy-review policy {:filter      filter
                                   :coverage-id (:instrument.coverage/coverage-id coverage)}))

(defn- filter-panel-name
  [filter]
  (name filter))

(defn- filter-tab
  [tr policy selected-filter filter]
  (let [panel-name (filter-panel-name filter)]
    [:wa-tab (cond-> {:panel         panel-name
                      :data-on:click (str "window.location.href = '"
                                          (urls/link-policy-review policy {:filter filter})
                                          "'")}
               (= selected-filter filter) (assoc :active true))
     (tr (filter-label-keys filter))]))

(defn- filter-panel
  [selected-filter filter]
  (let [panel-name (filter-panel-name filter)]
    [:wa-tab-panel (cond-> {:name  panel-name
                            :style "--padding: 0;"}
                     (= selected-filter filter) (assoc :active true))]))

(defn filter-bar
  [{:keys [tr]} {:keys [filter filter-order policy]}]
  (let [selected-filter filter]
    (into [:wa-tab-group {:active (filter-panel-name selected-filter)}]
          (concat
           (for [filter filter-order]
             (filter-tab tr policy selected-filter filter))
           (for [filter filter-order]
             (filter-panel selected-filter filter))))))

(defn- action-attrs
  [req coverage-id action]
  (let [target (str coverage-id)]
    {:data-on:click       (str "$targetid = '" target "'; "
                               "$loading = '" target "'; "
                               "@post('" (d*/act req action) "')")
     :data-attr:disabled (str "!!$loading && $loading !== '" target "'")
     :data-attr:loading  (str "$loading === '" target "'")}))

(def action-button-style
  "white-space: nowrap; flex: 0 0 auto;")

(defn- approve-and-next-button
  [{:keys [tr] :as req} coverage]
  [button/Button (merge {:appearance "filled"
                         :variant    "brand"
                         :size       "s"
                         :style      action-button-style}
                        (action-attrs req
                                      (:instrument.coverage/coverage-id coverage)
                                      ::actions/mark-coverage-reviewed))
   [ico/Icon {::ico/library :snoico
              ::ico/name    :circle-check-outline
              :slot         "start"}]
   (tr [:insurance.review/approve-and-next])])

(defn- insurer-id-input-id
  [coverage]
  (str "insurance-review-insurer-id-" (:instrument.coverage/coverage-id coverage)))

(defn- save-insurer-id-attrs
  [req coverage]
  (let [coverage-id (:instrument.coverage/coverage-id coverage)
        target      (str coverage-id)
        input-id    (insurer-id-input-id coverage)]
    {:data-on:click       (str "$insuranceReview = {...$insuranceReview, insurerId: "
                               "document.getElementById('" input-id "').value}; "
                               "$targetid = '" target "'; "
                               "$loading = '" target "'; "
                               "@post('" (d*/act req ::actions/update-insurer-id) "')")
     :data-attr:disabled (str "!!$loading && $loading !== '" target "'")
     :data-attr:loading  (str "$loading === '" target "'")}))

(defn- insurer-id-action
  [{:keys [tr] :as req} coverage]
  [:div {:style (str "display: flex; flex-wrap: nowrap; gap: var(--wa-space-xs); "
                     "align-items: end; min-inline-size: min(100%, 24rem); "
                     "margin-inline-start: auto;")}
   [:wa-input {:id           (insurer-id-input-id coverage)
               :label        (tr [:instrument.coverage/insurer-id])
               :appearance   "outlined"
               :size         "s"
               :value        (or (:instrument.coverage/insurer-id coverage) "")
               :data-bind    "insuranceReview.insurerId"
               :style        "inline-size: 12rem; flex: 1 1 12rem; min-inline-size: 0;"}]
   [button/Button (merge {:appearance "filled"
                          :variant    "brand"
                          :size       "s"
                          :style      action-button-style}
                         (save-insurer-id-attrs req coverage))
    [ico/Icon {::ico/library :snoico
               ::ico/name    :circle-check-outline
               :slot         "start"}]
    (tr [:insurance.review/save-and-continue])]])

(defn- insurance-team-member?
  [{:keys [db] :as req}]
  (q/insurance-team-member? db (get-in req [:session :session/member])))

(defn- policy-editable?
  [policy]
  (coverage.queries/policy-editable? policy))

(defn- primary-review-action
  [req {:keys [filter policy selected-coverage]}]
  (cond
    (not (insurance-team-member? req))
    [:wa-callout {:appearance "outlined" :variant "neutral"}
     ((:tr req) [:insurance.review/not-insurance-team])]

    (not (policy-editable? policy))
    [:wa-callout {:appearance "outlined" :variant "warning"}
     ((:tr req) [:insurance.review/frozen-policy])]

    (= filter :missing-insurer-id)
    (insurer-id-action req selected-coverage)

    :else
    (approve-and-next-button req selected-coverage)))

(def workbench-filter-slugs
  {:needs-review       :todo
   :missing-insurer-id :missing-id})

(defn- workbench-link
  [policy filter]
  (urls/link-policy-workbench policy {:review-filter (get workbench-filter-slugs filter filter)}))

(defn workbench-summary
  [{:keys [tr]} {:keys [filter policy queue-count]}]
  [:wa-card {:appearance "plain"
             :style      "background: var(--wa-color-surface-default);"}
   [:div {:class "wa-split wa-gap-m"}
    [:span {:class "wa-font-weight-semibold"}
     (tr [:insurance.review/items-left] [queue-count])]
    [:a {:href  (workbench-link policy filter)
         :style "text-align: end;"}
     (tr [:insurance.review/see-all-in-workbench])]]])

(defn- queue-item
  [{:keys [tr]} policy filter selected coverage]
  (let [instrument (get coverage :instrument.coverage/instrument)
        active?    (= (:instrument.coverage/coverage-id selected)
                      (:instrument.coverage/coverage-id coverage))]
    [:a {:href  (coverage-review-link policy filter coverage)
         :style (str "display: block; color: inherit; text-decoration: none; "
                     "padding: var(--wa-space-xs); border-radius: var(--wa-border-radius-m); "
                     (when active?
                       "background: var(--wa-color-brand-fill-quiet);"))}
     [:div {:class "wa-stack wa-gap-3xs"}
      [:span {:class "wa-font-weight-semibold"} (:instrument/name instrument)]
      [:span {:class "wa-caption-s wa-color-text-quiet"}
       (get-in instrument [:instrument/owner :member/name])]
      [:span {:class "wa-cluster wa-gap-2xs"}
       (insurance-ui/status-badge tr (:instrument.coverage/status coverage))
       (insurance-ui/change-badge tr (:instrument.coverage/change coverage))]]]))

(defn- queue-card
  [req {:keys [filter policy queue selected-coverage]}]
  [:wa-card {:appearance "plain"
             :style      "background: var(--wa-color-surface-default);"}
   [:div {:class "wa-stack"}
    [:h2 {:class "wa-heading-l"} ((:tr req) [:insurance.review/queue])]
    (if (seq queue)
      [:wa-scroller {:orientation "vertical" :style "max-block-size: 65vh;"}
       (into [:div {:class "wa-stack wa-gap-2xs"}]
             (map #(queue-item req policy filter selected-coverage %) queue))]
      (ui2/empty-state ((:tr req) [:insurance.review/empty-title])
                       ((:tr req) [:insurance.review/empty-body])))]])

(defn review-aside
  [req _review]
  [:aside
   (insurance-ui/comments-card req insurance-ui/comments-dummy)
   #_(queue-card req _review)])

(defn- nav-link-button
  [label href]
  [button/Button (cond-> {:appearance "outlined"
                          :size       "s"
                          :style      action-button-style}
                   href (assoc :href href)
                   (nil? href) (assoc :disabled true))
   label])

(defn- previous-button
  [{:keys [tr]} {:keys [filter policy previous-coverage]}]
  (nav-link-button
   (tr [:action/previous])
   (when previous-coverage
     (coverage-review-link policy filter previous-coverage))))

(defn- skip-button
  [{:keys [tr]} {:keys [filter next-coverage policy]}]
  (nav-link-button
   (tr [:insurance.review/skip])
   (when next-coverage
     (coverage-review-link policy filter next-coverage))))

(defn- navigation-button-group
  [req review]
  [:div {:style (str "display: flex; flex-wrap: nowrap; gap: var(--wa-space-s); "
                     "align-items: end; flex: 0 0 auto;")}
   (previous-button req review)
   (skip-button req review)])

(defn- todo-action-row
  [req review]
  [:div {:style (str "display: flex; flex-wrap: nowrap; gap: var(--wa-space-s); "
                     "align-items: end; overflow-x: auto;")}
   (previous-button req review)
   [:div {:style (str "display: flex; flex-wrap: nowrap; gap: var(--wa-space-s); "
                      "align-items: end; margin-inline-start: auto;")}
    (skip-button req review)
    (primary-review-action req review)]])

(defn review-action-row
  [req {:keys [filter] :as review}]
  (if (= filter :missing-insurer-id)
    [:div {:style (str "display: flex; flex-wrap: wrap; gap: var(--wa-space-s); "
                       "align-items: end;")}
     (navigation-button-group req review)
     (primary-review-action req review)]
    (todo-action-row req review)))

(defn- review-card
  [{:keys [page-state tr] :as req} {:keys [policy selected-coverage] :as review}]
  (let [instrument (:instrument.coverage/instrument selected-coverage)]
    (insurance-ui/coverage-detail-card
     req
     {:actions       (review-action-row req review)
      :coverage      selected-coverage
      :error-message (get-in page-state [:insurance-review :error :error])
      :policy        policy
      :subtitle      (tr [:insurance.review/reviewing-owner]
                         [(get-in instrument [:instrument/owner :member/name])])})))

(defn page
  [{:keys [db tr] :as req}]
  (let [review   (queries/policy-review db (policy-id req) (query-params req))
        policy   (:policy review)
        selected (:selected-coverage review)]
    (ui2/datastar-page
     [:div {:class "wa-stack wa-gap-xl"}
      [page-header/PageHeader
       {:breadcrumb (page-breadcrumb req policy)
        :title      (tr [:insurance.review/title])
        :subtitle   (tr [:insurance.review/subtitle] [(str/trim (:insurance.policy/name policy))])
        :actions    [[button/BackButton {:href (urls/link-policy policy)}]]}]
      (filter-bar req review)
      (workbench-summary req review)
      (if selected
        (list
         [:div {:class "wa-flank:end wa-align-items-start" :style "--flank-size: 50ch;"}
          (review-card req review)
          (review-aside req review)]
         (insurance-ui/history-section req selected))
        (queue-card req review))])))

(d*/refresh-all!)
