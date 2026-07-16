(ns app.gigs.edit.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.gigs.domain :as domain]
   [app.gigs.edit.actions :as actions]
   [app.gigs.edit.queries :as edit.queries]
   [app.gigs.ui :as gigs.ui]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]))

(defn- option [value label selected-value]
  [:wa-option {:value    value
               :selected (= value selected-value)}
   label])

(defn- required-marker []
  [:span {:aria-hidden "true"} " *"])

(defn- field-label [label required?]
  [:span {:slot "label"}
   label
   (when required?
     (required-marker))])

(defn- validate-field-action [req field]
  (str "$gig-edit.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-gig-field)
       "')"))

(defn- validate-field-attrs [req form-state field]
  (let [error (form/field-error form-state field)]
    {:hint                            error
     :data-invalid                    (when error "true")
     :data-bind                       (str "gig-edit." (name field))
     :data-on:blur                    (validate-field-action req field)
     :data-on:keydown__debounce.500ms (validate-field-action req field)}))

(defn- validate-select-attrs [req form-state field]
  (let [error (form/field-error form-state field)]
    {:hint           error
     :data-invalid   (when error "true")
     :data-bind      (str "gig-edit." (name field))
     :data-on:change (validate-field-action req field)}))

(defn- status-select [req form-state statuses]
  (let [selected (:status form-state)]
    (into
     [:wa-select (merge {:name       "status"
                         :value      selected
                         :required   true
                         :appearance "outlined"}
                        (validate-select-attrs req form-state :status))
      (field-label [:i18n/tr :gigs/status-label] true)]
     (for [status statuses]
       (option (name status) [:i18n/tr (domain/gig-status-label-key status)] selected)))))

(defn- gig-type-select [req form-state include-blank?]
  (let [selected (:gig-type form-state)]
    (into
     [:wa-select (merge {:name       "gig-type"
                         :value      selected
                         :required   true
                         :appearance "outlined"}
                        (validate-select-attrs req form-state :gig-type))
      (field-label [:i18n/tr :gigs/type-label] true)]
     (concat
      (when include-blank?
        [[:wa-option {:value ""} " - "]])
      (for [gig-type domain/gig-types]
        (option (name gig-type) [:i18n/tr (domain/gig-type-label-key gig-type)] selected))))))

(defn- member-option [selected-member-id member]
  (let [member-id (:member/member-id member)
        value     (str member-id)]
    (option value (ui2/member-nick member) (some-> selected-member-id str))))

(defn- selected-member-id [selected-member]
  (if (map? selected-member)
    (:member/member-id selected-member)
    selected-member))

(defn- member-select [label name selected-member members attrs]
  (let [selected-member-id (selected-member-id selected-member)]
    (into
     [:wa-select (merge {:label      label
                         :name       name
                         :value      (some-> selected-member-id str)
                         :appearance "outlined"}
                        attrs)
      [:wa-option {:value ""} " - "]]
     (for [member members]
       (member-option selected-member-id member)))))

(defn- input [label name value attrs]
  [:wa-input (merge {:name       name
                     :value      (form/text-value value)
                     :appearance "outlined"}
                    attrs)
   (field-label label (:required attrs))])

(defn- textarea [label name value attrs]
  (let [error         (:hint attrs)
        wrapper-attrs (:wrapper-attrs attrs)
        attrs         (dissoc attrs :hint :wrapper-attrs)]
    [:div (merge {:class "gigs-edit-textarea-field"} wrapper-attrs)
     [:label {:for name} label]
     [:textarea (merge {:id             name
                        :name           name
                        :class          "gigs-edit-textarea"
                        :rows           6
                        :data-auto-size "true"}
                       attrs)
      (form/text-value value)]
     (when error
       [:span {:class "wa-caption-s text-danger"}
        error])]))

(defn- element-ref [name suffix]
  (str (str/replace name #"[^A-Za-z0-9_]" "_") "_" suffix))

(defn- markdown-textarea [label name value attrs]
  (let [ref (element-ref name "markdown_editor")]
    (textarea label name value (merge {:class          "gigs-edit-textarea markdown-editor hidden"
                                       :data-auto-size         "true"
                                       :data-ref               ref
                                       :data-init__delay.10ms  (str "MarkdownEditor($" ref ")")
                                       :wrapper-attrs          {:data-ignore-morph ""}}
                                      attrs))))

(defn- gig-remove-dialog-id [{:gig/keys [gig-id]}]
  (ui2/remove-dialog-id "gig" gig-id))

(defn- gig-remove-dialog [req {:gig/keys [title] :as gig}]
  (ui2/remove-dialog
   {:id            (gig-remove-dialog-id gig)
    :label         [:i18n/tr :action/confirm-generic]
    :cancel-label  [:i18n/tr :action/cancel]
    :confirm-label [:i18n/tr :action/confirm-delete]
    :confirm-attrs {:data-id     "gig-edit-delete"
                    :data-action (d*/act req ::actions/delete-gig)}}
   [:p [:i18n/tr :action/confirm-delete-gig {:title title}]]))

(defn- save-button []
  [button/Button {:appearance         "filled"
                  :variant            "brand"
                  :type               "submit"
                  :form               "gig-edit-form"
                  :data-attr:disabled "!!$loading && $loading !== 'gig-edit'"
                  :data-attr:loading  "$loading === 'gig-edit'"}
   [:i18n/tr :action/save]])

(defn- create-toolbar []
  [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                             [breadcrumb/Breadcrumb {}
                              [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-gigs-home)}
                               [:i18n/tr :gigs/title]]
                              [breadcrumb/BreadcrumbItem [:i18n/tr :gigs/new-gig]]]
                             ::page-toolbar/actions
                             [[button/Button {:appearance "plain"
                                              :href       (urls/link-gigs-home)}
                               [:i18n/tr :action/cancel]]
                              (save-button)]
                             :aria-label [:i18n/tr :gigs/edit-toolbar-label]}])

(defn- edit-toolbar [req gig]
  (let [gig-url (urls/link-gig gig)]
    [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                               [breadcrumb/Breadcrumb {::breadcrumb/max-items [2 3]}
                                [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-gigs-home)}
                                 [:i18n/tr :gigs/title]]
                                (gigs.ui/gig-breadcrumb req gig)
                                [breadcrumb/BreadcrumbItem [:i18n/tr :action/edit]]]
                               ::page-toolbar/actions
                               [[button/Button {:appearance "plain"
                                                :href       gig-url}
                                 [:i18n/tr :action/cancel]]
                                (save-button)]
                               ::page-toolbar/overflow-items
                               [[:wa-dropdown-item {:variant     "danger"
                                                    :data-dialog (str "open " (gig-remove-dialog-id gig))}
                                 [:i18n/tr :action/delete]]]
                               ::page-toolbar/overflow-label [:i18n/tr :action/more-actions]
                               :aria-label                    [:i18n/tr :gigs/edit-toolbar-label]}]))

(defn- edit-header [{:keys [tr]} {:gig/keys [title gig-type status]}]
  (let [title-equals-type? (edit.queries/title-equals-gig-type? tr title gig-type)]
    [page-header/PageHeader
     {:title    [:span {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-detail-title"}
                 title
                 (when status
                   (gigs.ui/gig-status-icon status {:class "gigs-detail-status-icon"}))]
      :subtitle (when-not title-equals-type?
                  [:i18n/tr (domain/gig-type-label-key gig-type)])}]))

(defn- create-header []
  [page-header/PageHeader {:title [:i18n/tr :gigs/new-gig]}])

(defn- form-state [{:keys [page-state]} gig]
  (merge (edit.queries/gig->form gig) (:gig-edit page-state)))

(defn- create-form-state [{:keys [page-state tr]}]
  (merge (edit.queries/create-form tr) (:gig-edit page-state)))

(defn- probe-form? [form-state]
  (#{"probe" "extra-probe"} (:gig-type form-state)))

(defn- checkbox-input [label name checked? signal]
  [:label {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-edit-wide"}
   [:input (cond-> {:type      "checkbox"
                    :name      name
                    :value     "true"
                    :data-bind signal}
             checked? (assoc :checked true))]
   [:span label]])

(defn- notification-fields [form-state create?]
  (list
   (checkbox-input [:i18n/tr (if create?
                               :gigs/email-about-new
                               :gigs/email-about-change)]
                   "notify?"
                   (:notify? form-state)
                   "gig-edit.notify?")
   (when create?
     (checkbox-input [:i18n/tr :gigs/create-forum-thread]
                     "thread?"
                     (:thread? form-state)
                     "gig-edit.thread?"))))

(defn- main-fields
  ([req form-state]
   (main-fields req form-state {:create? false}))
  ([{:keys [db] :as req} form-state {:keys [create?]}]
   (let [members      (q/members-for-select-active db)
         field        #(validate-field-attrs req form-state %)
         select-field #(validate-select-attrs req form-state %)]
     (ui2/section-card
      {:title    [:i18n/tr :gigs/gig-info]
       :divider? true}
      [:div {:class "gigs-edit-form-grid"}
       (input [:i18n/tr :gigs/title-label] "title" (:title form-state) (merge {:required true} (field :title)))
       (status-select req form-state (if create? domain/create-statuses domain/statuses))
       (gig-type-select req form-state create?)
       (input [:i18n/tr :gigs/date] "date" (:date form-state) (merge {:type "date" :required true} (field :date)))
       (input [:i18n/tr :gigs/end-date] "end-date" (:end-date form-state) (merge {:type "date"} (field :end-date)))
       (member-select [:i18n/tr :gigs/contact] "contact" (:contact form-state) members (select-field :contact))
       (input [:i18n/tr :gigs/call-time] "call-time" (:call-time form-state) (merge {:type "time" :required true} (field :call-time)))
       (input [:i18n/tr :gigs/set-time] "set-time" (:set-time form-state) (merge {:type "time"} (field :set-time)))
       (input [:i18n/tr :gigs/end-time] "end-time" (:end-time form-state) (merge {:type "time"} (field :end-time)))
       (input [:i18n/tr :gigs/location] "location" (:location form-state) (merge {:required true} (field :location)))
       (input [:i18n/tr :gigs/outfit] "outfit" (:outfit form-state) (field :outfit))
       (input [:i18n/tr :gigs/pay-deal] "pay-deal" (:pay-deal form-state) (field :pay-deal))
       (input [:i18n/tr :gigs/leader] "leader" (:leader form-state) (field :leader))
       (when (probe-form? form-state)
         (list
          (member-select [:i18n/tr :gigs/rehearsal-leader-1] "rehearsal-leader1" (:rehearsal-leader1 form-state) members (select-field :rehearsal-leader1))
          (member-select [:i18n/tr :gigs/rehearsal-leader-2] "rehearsal-leader2" (:rehearsal-leader2 form-state) members (select-field :rehearsal-leader2))))
       (input [:i18n/tr :gigs/post-gig-plans] "post-gig-plans" (:post-gig-plans form-state) (merge {:class "gigs-edit-wide"} (field :post-gig-plans)))
       (markdown-textarea [:i18n/tr :gigs/more-details] "more-details" (:more-details form-state) (merge {:placeholder [:i18n/tr :gigs/more-details-placeholder]
                                                                                                          :class       "gigs-edit-textarea markdown-editor hidden gigs-edit-wide"}
                                                                                                         (field :more-details)))
       (textarea [:i18n/tr :gigs/description] "description" (:description form-state) (merge {:class "gigs-edit-textarea gigs-edit-wide"}
                                                                                             (field :description)))
       (notification-fields form-state create?)]))))

(defn- forum-fields [req form-state create?]
  [:wa-details {:summary            "Advanced"
                :data-preserve-attr "open"}
   [:div {:class "wa-stack wa-gap-m"}
    [:p {:class "wa-caption-m wa-color-text-quiet"}
     "Forum topic controls."]
    [:div {:class "gigs-edit-form-grid"}
     (when-not create?
       (checkbox-input "Takeover Forum Topic"
                       "takeover-topic?"
                       (:takeover-topic? form-state)
                       "gig-edit.takeover-topic?"))
     (input "Forum Topic ID" "topic-id" (:topic-id form-state) (merge {:class "gigs-edit-wide"}
                                                                      (validate-field-attrs req form-state :topic-id)))]]])

(defn- gig-form [{:keys [action create? form-state req]} & children]
  (into
   [:form {:id             "gig-edit-form"
           :class          "wa-stack wa-gap-xl"
           :data-id        "gig-edit"
           :data-action    (d*/act req action)
           :data-on:submit "evt.preventDefault();"
           :data-signals   (d*/->signals {:gig-edit (dissoc form-state :_error)})}
    (when-not create?
      [:input {:type      "hidden"
               :name      "gig-id"
               :value     (:gig-id form-state)
               :data-bind "gig-edit.gig-id"}])]
   children))

(defn- edit-form [req gig]
  (let [form-state (form-state req gig)]
    (gig-form {:req        req
               :action     ::actions/update-gig
               :form-state form-state}
              (main-fields req form-state)
              (forum-fields req form-state false)
              (when-let [top-error (form/field-error form-state :_top)]
                [:wa-callout {:appearance "outlined"
                              :variant    "danger"}
                 top-error]))))

(defn- create-form [req]
  (let [form-state (create-form-state req)]
    (gig-form {:req        req
               :action     ::actions/create-gig
               :create?    true
               :form-state form-state}
              (main-fields req form-state {:create? true})
              (forum-fields req form-state true)
              (when-let [top-error (form/field-error form-state :_top)]
                [:wa-callout {:appearance "outlined"
                              :variant    "danger"}
                 top-error]))))

(defn- edit-page [{:keys [db] :as req}]
  (let [gig-id (http.util/path-param-uuid! req :gig/gig-id)
        gig    (q/retrieve-gig db gig-id)]
    (if gig
      (ui2/datastar-page*
       (ui2/markdown-editor-scripts)
       [page-surface/PageSurface {::page-surface/toolbar (edit-toolbar req gig)}
        [:div {:class "wa-stack wa-gap-2xl"}
         (edit-header req gig)
         (edit-form req gig)]]
       (gig-remove-dialog req gig))
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id})))))

(defn- create-page [req]
  (ui2/datastar-page*
   (ui2/markdown-editor-scripts)
   [page-surface/PageSurface {::page-surface/toolbar (create-toolbar)}
    [:div {:class "wa-stack wa-gap-2xl"}
     (create-header)
     (create-form req)]]))

(defn page [req]
  (if (http.util/path-param req :gig/gig-id)
    (edit-page req)
    (create-page req)))

(d*/refresh-all!)
