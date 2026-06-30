(ns app.insurance.policy.review.views
  (:require
   [clojure.string :as str]
   [app.datastar :as d*]
   [app.insurance.coverage.queries :as coverage.queries]
   [app.insurance.policy.review.actions :as actions]
   [app.insurance.policy.review.queries :as queries]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
   [app.urls :as urls]))

(def workflow-status-data
  {:instrument.coverage.status/needs-review    {:icon    :circle-question-outline
                                                :variant "warning"}
   :instrument.coverage.status/reviewed        {:icon    :circle-dot-outline
                                                :variant "neutral"}
   :instrument.coverage.status/coverage-active {:icon    :circle-check-outline
                                                :variant "success"}})

(def change-status-data
  {:instrument.coverage.change/changed {:icon    :circle-exclamation
                                        :variant "warning"}
   :instrument.coverage.change/new     {:icon    :circle-plus-solid
                                        :variant "success"}
   :instrument.coverage.change/removed {:icon    :circle-xmark-outline
                                        :variant "danger"}
   :instrument.coverage.change/none    {:icon    :minus
                                        :variant "neutral"}})

(def filter-label-keys
  {:needs-review       [:insurance.review/filter-needs-review]
   :missing-insurer-id [:insurance.review/filter-missing-insurer-id]})

(def comments-dummy
  [{:comment/comment-id #uuid "11111111-1111-4111-8111-111111111111"
    :comment/body       "I checked the source photo and the instrument details look consistent. The Harmonia ID still needs to be copied over before this can be reviewed."
    :comment/author     {:member/name "Robert Fox"
                         :member/nick "RF"}
    :comment/created-at #inst "2026-06-18T09:17:00.000-00:00"
    :comment/replies    [{:comment/comment-id #uuid "22222222-2222-4222-8222-222222222222"
                          :comment/body       "I found the matching policy line in Harmonia. The value matches, but the owner name has an old spelling."
                          :comment/author     {:member/name "Virginia Woolf"
                                               :member/nick "VW"}
                          :comment/created-at #inst "2026-06-18T12:32:00.000-00:00"}
                         {:comment/comment-id #uuid "33333333-3333-4333-8333-333333333333"
                          :comment/body       "Leave it in Missing ID until the name is corrected in the source system."
                          :comment/author     {:member/name "Clarissa Vaughan"
                                               :member/nick "CV"}
                          :comment/created-at #inst "2026-06-19T07:48:00.000-00:00"}]}])

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

(defn- status-badge
  [tr status]
  (let [{:keys [icon variant]} (workflow-status-data status)]
    [:wa-badge {:appearance "outlined"
                :variant    variant
                :pill       true}
     [ico/Icon {::ico/library :snoico
                ::ico/name    icon
                :aria-hidden  true}]
     (tr [status])]))

(defn- change-badge
  [tr change]
  (let [{:keys [icon variant]} (change-status-data change)]
    [:wa-badge {:appearance "outlined"
                :variant    variant
                :pill       true}
     [ico/Icon {::ico/library :snoico
                ::ico/name    icon
                :aria-hidden  true}]
     (tr [change])]))

(defn- kind-badge
  [tr private?]
  [:wa-badge {:appearance "outlined"
              :variant    (if private? "warning" "success")
              :pill       true}
   (if private?
     (tr [:private-instrument])
     (tr [:band-instrument]))])

(defn- owner-link
  [owner]
  (if-let [member-id (:member/member-id owner)]
    [:a {:href (urls/link-member member-id)} (:member/name owner)]
    (:member/name owner)))

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

(defn- filter-bar
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

(defn- detail-row
  [label value]
  [:div {:class "wa-split wa-gap-m"}
   [:span {:class "wa-caption-s wa-color-text-quiet"} label]
   [:span {:style "min-inline-size: 0; overflow-wrap: anywhere; text-align: end;"}
    (ui2/muted value)]])

(defn- divided-rows
  [rows]
  (mapcat (fn [row]
            [row [divider/Divider]])
          rows))

(def workbench-filter-slugs
  {:needs-review       "todo"
   :missing-insurer-id "missing-id"})

(defn- workbench-link
  [policy filter]
  (str "/insurance-policy/"
       (:insurance.policy/policy-id policy)
       "/workbench?review-filter="
       (get workbench-filter-slugs filter (name filter))))

(defn- workbench-summary
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
       (status-badge tr (:instrument.coverage/status coverage))
       (change-badge tr (:instrument.coverage/change coverage))]]]))

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

(defn- instant-value
  [value]
  (cond
    (instance? java.time.Instant value)
    (str value)

    (instance? java.util.Date value)
    (str (.toInstant ^java.util.Date value))

    :else
    (str value)))

(defn- author-name
  [comment]
  (or (get-in comment [:comment/author :member/name])
      (get-in comment [:comment/author :member/nick])
      ""))

(defn- author-initials
  [comment]
  (or (not-empty (get-in comment [:comment/author :member/nick]))
      (->> (str/split (str/trim (author-name comment)) #"\\s+")
           (keep first)
           (take 2)
           (apply str)
           str/upper-case)))

(defn- comment-item
  [{:keys [tr]} comment]
  [:li {:class "wa-stack wa-gap-2xs"}
   [:div {:class "wa-flank"}
    [:wa-avatar {:initials (author-initials comment)
                 :label    (author-name comment)}]
    [:div {:class "wa-cluster"}
     [:strong (author-name comment)]
     [:span {:class "wa-caption-s"}
      (tr [:insurance.review/commented])
      " "
      [:wa-relative-time {:date (instant-value (:comment/created-at comment))}]]]]
   [:p (:comment/body comment)]])

(defn- reply-link
  [{:keys [tr]}]
  [:li {:class "wa-cluster"}
   [ico/Icon {::ico/library :snoico
              ::ico/name    :comment-outline
              :aria-hidden  true}]
   [:a {:href          "#"
        :data-on:click "evt.preventDefault()"}
    (tr [:insurance.review/leave-reply])]])

(defn- comments-thread
  [req replies]
  (when (seq replies)
    [:div {:class "wa-flank"}
     [divider/Divider {::divider/orientation :vertical
                       :style                 "height: auto; align-self: stretch;"}]
     (into [:ul {:class "wa-stack"
                 :style "list-style: none; padding-inline-start: 0; margin: 0;"}]
           (concat (map #(comment-item req %) replies)
                   [(reply-link req)]))]))

(defn- comment-with-thread
  [req comment]
  [:div {:class "wa-stack"}
   (comment-item req comment)
   (comments-thread req (:comment/replies comment))])

(defn- comments-card
  [{:keys [tr] :as req} comments]
  [:wa-card {:appearance "plain"
             :style      "background: var(--wa-color-surface-default);"}
   [:div {:class "wa-stack"}
    [:h2 {:class "wa-heading-l"} (tr [:insurance.review/comments])]
    [:wa-textarea {:aria-label  (tr [:insurance.review/comments])
                   :placeholder (tr [:insurance.review/comment-placeholder])}]
    [button/Button {:appearance "filled"
                    :variant    "brand"
                    :disabled   true}
     (tr [:insurance.review/add-comment])]
    [divider/Divider]
    (into [:ul {:class "wa-stack"
                :style "list-style: none; padding-inline-start: 0; margin: 0;"}]
          (map #(comment-with-thread req %) comments))]])

(defn- review-aside
  [req _review]
  [:aside
   (comments-card req comments-dummy)
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

(defn- review-action-row
  [req {:keys [filter] :as review}]
  (if (= filter :missing-insurer-id)
    [:div {:style (str "display: flex; flex-wrap: wrap; gap: var(--wa-space-s); "
                       "align-items: end;")}
     (navigation-button-group req review)
     (primary-review-action req review)]
    (todo-action-row req review)))

(defn- photo-card
  [_req {:keys [full thumbnail]}]
  [:div {:class "wa-frame:portrait wa-border-radius-s" :style "aspect-ratio: 4 / 3; max-width: 300px;"}
   [:a {:href   full
        :target "_blank"
        :style  "display: block;"}
    [:img {:src     thumbnail
           :loading "lazy"
           :alt     ""}]]])

(defn- photo-section
  [{:keys [tr] :as req} instrument]
  (let [photo-uris (coverage.queries/image-uris req instrument)]
    [:section {:class "wa-stack"}
     [:h3 {:class "wa-heading-m"} (tr [:instrument/images])]
     (if (seq photo-uris)
       (into [:div {:class "wa-grid wa-gap-s" :style "--min-column-size: 10rem;"}]
             (map #(photo-card req %) photo-uris))
       (ui2/empty-state (tr [:instrument/images]) (tr [:insurance/no-photos])))]))

(defn- instrument-details
  [{:keys [tr]} coverage]
  (let [instrument (:instrument.coverage/instrument coverage)]
    [:section {:class "wa-stack"}
     [:h3 {:class "wa-heading-m"} (tr [:instrument/instrument])]
     (into [:div {:class "wa-stack wa-gap-xs"}]
           (divided-rows
            [(detail-row (tr [:instrument/owner]) (owner-link (:instrument/owner instrument)))
             (detail-row (tr [:instrument/category]) (get-in instrument [:instrument/category :instrument.category/name]))
             (detail-row (tr [:instrument/make]) (:instrument/make instrument))
             (detail-row (tr [:instrument/model]) (:instrument/model instrument))
             (detail-row (tr [:instrument/serial-number]) (:instrument/serial-number instrument))
             (detail-row (tr [:instrument/build-year]) (:instrument/build-year instrument))
             (detail-row (tr [:instrument/description]) (:instrument/description instrument))
             (when-let [share-url (not-empty (:instrument/images-share-url instrument))]
               (detail-row (tr [:instrument/images-share-url]) (ui2/link-copy share-url)))]))]))

(defn- coverage-type-row
  [currency {:insurance.coverage.type/keys [cost name premium-factor]}]
  [:tr
   [:th {:scope "row"} name]
   [:td premium-factor]
   [:td (ui2/money cost currency)]])

(defn- coverage-details
  [{:keys [tr]} coverage policy]
  (let [currency (:insurance.policy/currency policy)]
    [:section {:class "wa-stack"}
     [:h3 {:class "wa-heading-m"} (tr [:insurance/instrument-coverage])]
     (into [:div {:class "wa-stack wa-gap-xs"}]
           (divided-rows
            [(detail-row (tr [:insurance/item-count]) (or (:instrument.coverage/item-count coverage) 1))
             (detail-row (tr [:insurance/value]) (ui2/money (:instrument.coverage/value coverage) currency))
             (detail-row (tr [:instrument.coverage/insurer-id]) (:instrument.coverage/insurer-id coverage))
             (detail-row (tr [:band-private]) (kind-badge tr (:instrument.coverage/private? coverage)))
             (detail-row (tr [:instrument.coverage/cost]) (ui2/money (:instrument.coverage/cost coverage) currency))]))
     (ui2/table-shell
      [:table
       [:thead
        [:tr
         [:th {:scope "col"} (tr [:insurance/coverage-types])]
         [:th {:scope "col"} (tr [:insurance/premium-factor])]
         [:th {:scope "col"} (tr [:instrument.coverage/cost])]]]
       (into [:tbody]
             (map #(coverage-type-row currency %) (:instrument.coverage/types coverage)))])]))

(defn- review-card
  [{:keys [page-state tr] :as req} {:keys [policy selected-coverage] :as review}]
  (let [instrument (:instrument.coverage/instrument selected-coverage)]
    [:wa-card {:appearance "plain"
               :style      "background: var(--wa-color-surface-default);"}
     [:div {:class "wa-stack wa-gap-l"}
      [:div {:class "wa-stack wa-gap-xs"}
       [:div {:class "wa-cluster wa-gap-xs"}
        (status-badge tr (:instrument.coverage/status selected-coverage))
        (change-badge tr (:instrument.coverage/change selected-coverage))
        (kind-badge tr (:instrument.coverage/private? selected-coverage))]
       [:h2 {:class "wa-heading-xl"} (:instrument/name instrument)]
       [:div {:class "wa-caption-s wa-color-text-quiet"}
        (tr [:insurance.review/reviewing-owner]
            [(get-in instrument [:instrument/owner :member/name])])]]
      (when-let [message (get-in page-state [:insurance-review :error :error])]
        [:wa-callout {:appearance "outlined" :variant "danger"}
         message])
      (review-action-row req review)
      [divider/Divider]
      [:div {:class "wa-grid wa-align-items-start" :style "--min-column-size: 24rem;"}
       (instrument-details req selected-coverage)
       (coverage-details req selected-coverage policy)]
      (photo-section req instrument)]]))

(defn page
  [{:keys [db tr] :as req}]
  (let [review   (queries/policy-review db (policy-id req) (query-params req))
        policy   (:policy review)
        selected (:selected-coverage review)]
    (ui2/datastar-page
     [:div {:class "wa-stack wa-gap-xl"}
      (ui2/page-header
       {:breadcrumb (page-breadcrumb req policy)
        :title      (tr [:insurance.review/title])
        :subtitle   (tr [:insurance.review/subtitle] [(str/trim (:insurance.policy/name policy))])
        :actions    [[button/Button {:appearance "outlined"
                                     :href       (urls/link-policy policy)}
                      (tr [:insurance.review/back-to-dashboard])]]})
      (filter-bar req review)
      (workbench-summary req review)
      (if selected
        [:div {:class "wa-flank:end wa-align-items-start" :style "--flank-size: 50ch;"}
         (review-card req review)
         (review-aside req review)]
        (queue-card req review))])))

(d*/refresh-all!)
