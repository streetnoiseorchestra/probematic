(ns app.poll.edit.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.poll.domain :as domain]
   [app.poll.edit.actions :as actions]
   [app.poll.queries :as queries]
   [app.poll.ui :as poll.ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [medley.core :as m]))

(defn- request-poll-id [req]
  (or (http.util/path-param-uuid req :poll/poll-id)
      (http.util/path-param-uuid req :poll-id)))

(defn- option [value label selected-value]
  [:wa-option {:value    value
               :selected (= value selected-value)}
   label])

(defn- validate-field-action [req field]
  (str "$poll-edit.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-poll-field)
       "')"))

(defn- validate-field-attrs [req form-state field]
  (let [error (form/field-error form-state field)]
    {:hint                            error
     :data-invalid                    (when error "true")
     :data-bind                       (str "poll-edit." (name field))
     :data-on:blur                    (validate-field-action req field)
     :data-on:input__debounce.500ms (validate-field-action req field)}))

(defn- validate-select-attrs [req form-state field]
  (let [error (form/field-error form-state field)]
    {:hint           error
     :data-invalid   (when error "true")
     :data-bind      (str "poll-edit." (name field))
     :data-on:change (validate-field-action req field)}))

(defn- input [label name value attrs]
  [:wa-input (merge {:label      label
                     :name       name
                     :value      (form/text-value value)
                     :appearance "outlined"}
                    attrs)])

(defn- textarea [label name value attrs]
  (let [error         (:hint attrs)
        wrapper-attrs (:wrapper-attrs attrs)
        attrs         (dissoc attrs :hint :wrapper-attrs)]
    [:div (merge {:class "poll-edit-textarea-field"} wrapper-attrs)
     [:label {:for name} label]
     [:textarea (merge {:id             name
                        :name           name
                        :class          "poll-edit-textarea"
                        :rows           6
                        :data-auto-size "true"}
                       attrs)
      (form/text-value value)]
     (when error
       [:span {:class "wa-caption-s text-danger"}
        error])]))

(defn- poll-type-select [{:keys [tr] :as req} form-state attrs]
  (let [selected (:poll-type form-state)]
    (into
     [:wa-select (merge {:label      (tr [:polls/type-label])
                         :name       "poll-type"
                         :value      selected
                         :required   true
                         :appearance "outlined"}
                        attrs
                        (validate-select-attrs req form-state :poll-type))]
     (for [poll-type domain/poll-types]
       (option (name poll-type) (poll.ui/poll-type-label tr poll-type) selected)))))

(defn- option-field [{:keys [tr] :as req} form-state idx option-state read-only?]
  (let [error (form/field-error form-state :options)]
    [:div {:class "poll-option-row"}
     [:wa-input (merge {:label      (str (tr [:polls/options]) " " (inc idx))
                        :value      (form/text-value (:value option-state))
                        :appearance "outlined"
                        :disabled   read-only?
                        :data-bind  (str "poll-edit.options." idx ".value")}
                       (when error
                         {:hint         error
                          :data-invalid "true"})
                       {:data-on:blur                  (validate-field-action req :options)
                        :data-on:input__debounce.500ms (validate-field-action req :options)})]
     (when-not read-only?
       [button/Button (merge {:appearance "plain"
                              :variant    "danger"}
                             (poll.ui/action-button-attrs req ::actions/remove-option (str idx)))
        (tr [:action/remove])])]))

(defn- options-editor [{:keys [tr] :as req} form-state read-only?]
  [:div {:class "wa-stack wa-gap-s"}
   [:div {:class "poll-options-header"}
    [:h2 {:class "wa-heading-l"}
     (tr [:polls/choices-title])]
    (when-not read-only?
      [button/Button (merge {:appearance "outlined"}
                            (poll.ui/action-button-attrs req ::actions/add-option "poll-edit-add-option"))
       (tr [:polls/add-option])])]
   (when read-only?
     [:wa-callout {:appearance "outlined" :variant "neutral"}
      (tr [:polls/options-read-only-hint])])
   (into [:div {:class "poll-options-list"}]
         (map-indexed (fn [idx option-state]
                        (option-field req form-state idx option-state read-only?))
                      (:options form-state)))])

(defn- form-state [req poll]
  (m/deep-merge
   (if poll
     (poll.ui/poll->form poll)
     (poll.ui/create-form-state))
   (or (get-in req [:page-state :poll-edit]) {})))

(defn- delete-dialog-id [poll]
  (ui2/remove-dialog-id "poll" (:poll/poll-id poll)))

(defn- delete-dialog [req poll]
  (ui2/remove-dialog
   {:id            (delete-dialog-id poll)
    :label         [:i18n/tr :action/confirm-generic]
    :cancel-label  [:i18n/tr :action/cancel]
    :confirm-label [:i18n/tr :action/confirm-delete]
    :confirm-attrs (poll.ui/action-button-attrs req ::actions/delete-poll (str (:poll/poll-id poll)))}
   [:p [:i18n/tr :polls/confirm-delete-poll {:title (:poll/title poll)}]]))

(defn- save-button []
  [button/Button (merge {:appearance "filled"
                         :variant    "brand"
                         :type       "submit"
                         :form       "poll-edit-form"}
                        (poll.ui/loading-attrs "poll-edit"))
   [:i18n/tr :action/save]])

(defn- create-toolbar []
  [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                             [breadcrumb/Breadcrumb {}
                              [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-polls-home)}
                               [:i18n/tr :polls/title]]
                              [breadcrumb/BreadcrumbItem [:i18n/tr :polls/new-poll]]]
                             ::page-toolbar/actions
                             [[button/Button {:appearance "plain"
                                              :href       (urls/link-polls-home)}
                               [:i18n/tr :action/cancel]]
                              (save-button)]
                             :aria-label [:i18n/tr :polls/edit-toolbar-label]}])

(defn- edit-toolbar [poll]
  (let [poll-url (urls/link-poll poll)]
    [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                               [breadcrumb/Breadcrumb {::breadcrumb/max-items [2 3]}
                                [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-polls-home)}
                                 [:i18n/tr :polls/title]]
                                [breadcrumb/BreadcrumbItem {::breadcrumb/href poll-url}
                                 (:poll/title poll)]
                                [breadcrumb/BreadcrumbItem [:i18n/tr :action/edit]]]
                               ::page-toolbar/actions
                               [[button/Button {:appearance "plain"
                                                :href       poll-url}
                                 [:i18n/tr :action/cancel]]
                                (save-button)]
                               ::page-toolbar/overflow-items
                               [[:wa-dropdown-item {:variant     "danger"
                                                    :data-dialog (str "open " (delete-dialog-id poll))}
                                 [:i18n/tr :action/delete]]]
                               ::page-toolbar/overflow-label [:i18n/tr :action/more-actions]
                               :aria-label                    [:i18n/tr :polls/edit-toolbar-label]}]))

(defn- create-header []
  [page-header/PageHeader {:title [:i18n/tr :polls/new-poll]}])

(defn- edit-header [{:keys [tr]} poll]
  [page-header/PageHeader
   {:title    [:span {:class "wa-cluster wa-gap-xs wa-align-items-center"}
               (:poll/title poll)
               (poll.ui/status-badge tr (:poll/poll-status poll))]
    :subtitle (poll.ui/poll-type-label tr (:poll/poll-type poll))}])

(defn- main-fields [{:keys [tr] :as req} form-state closed? choice-read-only?]
  (let [multiple? (= "multiple" (:poll-type form-state))
        field     (fn [field disabled?]
                    (merge {:disabled disabled?}
                           (validate-field-attrs req form-state field)))]
    [:div {:class "poll-edit-form-grid"}
     (input (tr [:polls/poll-title-label]) "title" (:title form-state) (merge {:required true} (field :title closed?)))
     (textarea (tr [:polls/description-label]) "description" (:description form-state) (merge {:required true
                                                                                               :class    "poll-edit-wide"}
                                                                                              (field :description closed?)))
     (poll-type-select req form-state {:disabled choice-read-only?})
     (when multiple?
       (list
        (input (tr [:polls/min-choices-label]) "min-choice" (:min-choice form-state) (merge {:type     "number"
                                                                                             :min      1
                                                                                             :required true}
                                                                                            (field :min-choice choice-read-only?)))
        (input (tr [:polls/max-choices-label]) "max-choice" (:max-choice form-state) (merge {:type     "number"
                                                                                             :min      1
                                                                                             :required true}
                                                                                            (field :max-choice choice-read-only?)))))
     (input (tr [:polls/closes-at-label]) "closes-at" (:closes-at form-state) (merge {:type     "datetime-local"
                                                                                      :required true}
                                                                                     (field :closes-at closed?)))
     [:input {:type      "hidden"
              :value     (str (:autoremind? form-state))
              :data-bind "poll-edit.autoremind?"}]]))

(defn- poll-form [{:keys [tr] :as req} action form-state poll]
  (let [open?      (= :poll.status/open (:poll/poll-status poll))
        closed?    (= :poll.status/closed (:poll/poll-status poll))
        read-only? (or open? closed?)]
    [:form {:id             "poll-edit-form"
            :class          "wa-stack wa-gap-xl"
            :data-id        "poll-edit"
            :data-action    (d*/act req action)
            :data-on:submit "evt.preventDefault();"
            :data-signals   (d*/->signals {:poll-edit (dissoc form-state :_error)})}
     (when (:poll-id form-state)
       [:input {:type      "hidden"
                :value     (:poll-id form-state)
                :data-bind "poll-edit.poll-id"}])
     (when closed?
       [:wa-callout {:appearance "outlined" :variant "danger"}
        (tr [:polls/error-edit-closed])])
     (main-fields req form-state closed? read-only?)
     (options-editor req form-state read-only?)
     (when-let [top-error (form/field-error form-state :_top)]
       [:wa-callout {:appearance "outlined"
                     :variant    "danger"}
        top-error])]))

(defn- create-page [req]
  (let [form-state (form-state req nil)]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width   :standard
       ::page-surface/toolbar (create-toolbar)}
      [:div {:class "wa-stack wa-gap-2xl poll-edit-page"}
       (create-header)
       (poll-form req ::actions/create-poll form-state nil)]])))

(defn- edit-page [{:keys [db] :as req} poll-id]
  (if-let [poll (queries/retrieve-poll db poll-id)]
    (let [form-state (form-state req poll)]
      (ui2/datastar-page*
       [page-surface/PageSurface
        {::page-surface/width   :standard
         ::page-surface/toolbar (edit-toolbar poll)}
        [:div {:class "wa-stack wa-gap-2xl poll-edit-page"}
         (edit-header req poll)
         (poll-form req ::actions/update-poll form-state poll)]]
       (delete-dialog req poll)))
    (throw (ex-info "Poll not found" {:app/error-type :app.error.type/not-found
                                      :poll/poll-id   poll-id}))))

(defn page [req]
  (if-let [poll-id (request-poll-id req)]
    (edit-page req poll-id)
    (create-page req)))

(d*/refresh-all!)
