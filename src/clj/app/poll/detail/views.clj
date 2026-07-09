(ns app.poll.detail.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.poll.detail.actions :as actions]
   [app.poll.queries :as queries]
   [app.poll.ui :as poll.ui]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.urls :as urls]
   [app.util.http :as http.util]))

(defn- request-poll-id [req]
  (or (http.util/path-param-uuid req :poll/poll-id)
      (http.util/path-param-uuid! req :poll-id)))

(defn- dialog-id [prefix poll]
  (str prefix "-" (ui2/safe-dom-id (:poll/poll-id poll))))

(defn- breadcrumb [{:keys [tr]} poll]
  [breadcrumb/Breadcrumb
   {}
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-polls-home)}
    (tr [:nav/polls])]
   [breadcrumb/BreadcrumbItem (:poll/title poll)]])

(defn- open-dialog [{:keys [tr] :as req} poll]
  (let [id      (dialog-id "poll-open" poll)
        poll-id (str (:poll/poll-id poll))]
    [:wa-dialog {:id                 id
                 :label              (tr [:action/open-poll])
                 :data-preserve-attr "open"}
     [:p (tr [:poll/open-hint])]
     [button/Button {:slot        "footer"
                     :appearance  "outlined"
                     :data-dialog "close"}
      (tr [:action/cancel])]
     [button/Button (merge {:slot        "footer"
                            :appearance  "filled"
                            :variant     "brand"
                            :data-dialog "close"}
                           (poll.ui/action-button-attrs req ::actions/open-poll poll-id))
      (tr [:action/confirm-open-poll])]]))

(defn- close-dialog [{:keys [tr] :as req} poll]
  (let [id      (dialog-id "poll-close" poll)
        poll-id (str (:poll/poll-id poll))]
    [:wa-dialog {:id                 id
                 :label              (tr [:poll/close-early])
                 :data-preserve-attr "open"}
     [:p (tr [:action/confirm-close-poll] [(:poll/title poll)])]
     [button/Button {:slot        "footer"
                     :appearance  "outlined"
                     :data-dialog "close"}
      (tr [:action/cancel])]
     [button/Button (merge {:slot        "footer"
                            :appearance  "filled"
                            :variant     "danger"
                            :data-dialog "close"}
                           (poll.ui/action-button-attrs req ::actions/close-poll poll-id))
      (tr [:action/confirm-close])]]))

(defn- header-actions [{:keys [tr]} poll]
  (let [status (:poll/poll-status poll)]
    (cond-> []
      (#{:poll.status/draft :poll.status/open} status)
      (conj [button/Button {:appearance "outlined"
                            :href       (urls/link-poll-edit poll)}
             (tr [:action/edit])])
      (= :poll.status/draft status)
      (conj [button/Button {:appearance  "filled"
                            :variant     "brand"
                            :data-dialog (str "open " (dialog-id "poll-open" poll))}
             (tr [:action/open-poll])])
      (= :poll.status/open status)
      (conj [button/Button {:appearance  "outlined"
                            :variant     "danger"
                            :data-dialog (str "open " (dialog-id "poll-close" poll))}
             (tr [:poll/close-early])]))))

(defn- page-header [{:keys [tr] :as req} poll]
  [page-header/PageHeader
   {:breadcrumb (breadcrumb req poll)
    :title      [:span {:class "wa-cluster wa-gap-xs wa-align-items-center"}
                 (:poll/title poll)
                 (poll.ui/status-badge tr (:poll/poll-status poll))]
    :subtitle   (tr [(:poll/poll-type poll)])
    :actions    (header-actions req poll)}])

(defn- metadata-section [{:keys [tr] :as req} poll]
  (ui2/section-card
   {:title (tr [:poll/poll])}
   [:dl {:class "poll-detail-meta"}
    [:div
     [:dt (tr [:poll/poll-type])]
     [:dd (tr [(:poll/poll-type poll)])]]
    (when (= :poll.type/multiple (:poll/poll-type poll))
      [:div
       [:dt (tr [:poll/select-num-choices] [(:poll/min-choice poll) (:poll/max-choice poll)])]
       [:dd (str (:poll/min-choice poll) " / " (:poll/max-choice poll))]])
    [:div
     [:dt (tr [:poll/closes-at])]
     [:dd (ui2/format-date-time req :medium (:poll/closes-at poll))]]]
   (poll.ui/description-markdown (:poll/description poll))))

(defn- selected-option-ids [member-votes]
  (set (keep #(get-in % [:poll.vote/poll-option :poll.option/poll-option-id]) member-votes)))

(defn- vote-form-state [req poll member-votes]
  (let [selected (selected-option-ids member-votes)
        initial  {:poll-id          (str (:poll/poll-id poll))
                  :selected-option  (some-> selected first str)
                  :selected-options (into {} (map (fn [option-id] [(str option-id) true]) selected))}]
    (merge initial (get-in req [:page-state :poll-vote]))))

(defn- choice-input [poll-type form-state option]
  (let [option-id     (:poll.option/poll-option-id option)
        option-id-str (str option-id)
        multiple?     (= :poll.type/multiple poll-type)]
    [:label {:class "poll-choice"}
     [:input (cond-> {:type  (if multiple? "checkbox" "radio")
                      :value option-id-str}
               multiple?
               (assoc :checked   (boolean (get-in form-state [:selected-options option-id-str]))
                      :data-bind (str "poll-vote.selected-options." option-id-str))
               (not multiple?)
               (assoc :name      "poll-option"
                      :checked   (= option-id-str (:selected-option form-state))
                      :data-bind "poll-vote.selected-option"))]
     [:span (:poll.option/value option)]]))

(defn- vote-form [{:keys [tr] :as req} poll member-votes]
  (let [form-state (vote-form-state req poll member-votes)
        multiple?  (= :poll.type/multiple (:poll/poll-type poll))]
    [:form {:id             "poll-vote-form"
            :class          "wa-stack wa-gap-m"
            :data-id        "poll-vote"
            :data-action    (d*/act req ::actions/cast-vote)
            :data-on:submit "evt.preventDefault();"
            :data-signals   (d*/->signals {:poll-vote form-state})}
     (if multiple?
       [:p {:class "wa-caption-m wa-color-text-quiet"}
        (tr [:poll/select-num-choices] [(:poll/min-choice poll) (:poll/max-choice poll)])]
       [:p {:class "wa-caption-m wa-color-text-quiet"}
        (tr [:poll.type/single-hint])])
     (into [:div {:class "poll-choice-list"}]
           (map (partial choice-input (:poll/poll-type poll) form-state)
                (sort-by :poll.option/position (:poll/options poll))))
     (when-let [top-error (form/field-error form-state :_top)]
       [:wa-callout {:appearance "outlined" :variant "danger"}
        top-error])
     [button/Button (merge {:appearance "filled"
                            :variant    "brand"
                            :type       "submit"
                            :form       "poll-vote-form"}
                           (poll.ui/loading-attrs "poll-vote"))
      (if (seq member-votes)
        (tr [:action/change-vote])
        (tr [:action/vote]))]]))

(defn- voting-section [{:keys [tr] :as req} poll member-votes]
  (ui2/section-card
   {:title (tr [:poll/vote-now])}
   (case (:poll/poll-status poll)
     :poll.status/draft
     [:wa-callout {:appearance "outlined" :variant "neutral"}
      (tr [:poll/open-hint])]

     :poll.status/open
     (vote-form req poll member-votes)

     :poll.status/closed
     [:wa-callout {:appearance "outlined" :variant "neutral"}
      (tr [:poll/error-not-open])]

     nil)))

(defn- result-stat [label value]
  [:div {:class "poll-result-stat"}
   [:dt label]
   [:dd value]])

(defn- results-section [{:keys [tr] :as req} poll]
  (ui2/section-card
   {:title (tr [:poll/results])}
   [:dl {:class "poll-result-stats"}
    (result-stat (tr [:poll/total-voters]) (poll.ui/total-voters poll))
    (result-stat (tr [:poll/total-votes]) (poll.ui/total-votes poll))
    (result-stat (tr [:poll/closes-at]) (ui2/format-date-time req :medium (:poll/closes-at poll)))]
   (poll.ui/result-bars poll)))

(defn page [{:keys [db current-member-id] :as req}]
  (let [poll-id (request-poll-id req)]
    (if-let [{:keys [poll member-votes has-voted?]} (queries/detail-page-data db poll-id current-member-id)]
      (ui2/datastar-page
       [:div {:class "wa-stack wa-gap-2xl poll-detail-page"}
        (page-header req poll)
        (metadata-section req poll)
        (voting-section req poll member-votes)
        (when (or has-voted? (= :poll.status/closed (:poll/poll-status poll)))
          (results-section req poll))
        (open-dialog req poll)
        (close-dialog req poll)])
      (throw (ex-info "Poll not found" {:app/error-type :app.error.type/not-found
                                        :poll/poll-id   poll-id})))))

(d*/refresh-all!)
