(ns app.gigs.edit.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.gigs.domain :as domain]
   [app.gigs.edit.actions :as actions]
   [app.gigs.ui :as gigs.ui]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]))

(defn- option [value label selected-value]
  [:wa-option {:value    value
               :selected (= value selected-value)}
   label])

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

(defn- status-select [{:keys [tr] :as req} form-state statuses]
  (let [selected (:status form-state)]
    (into
     [:wa-select (merge {:label      (tr [:gig/status])
                         :name       "status"
                         :value      selected
                         :required   true
                         :appearance "outlined"}
                        (validate-select-attrs req form-state :status))]
     (for [status statuses]
       (option (name status) (tr [status]) selected)))))

(defn- gig-type-select [{:keys [tr] :as req} form-state include-blank?]
  (let [selected (:gig-type form-state)]
    (into
     [:wa-select (merge {:label      (tr [:gig/gig-type])
                         :name       "gig-type"
                         :value      selected
                         :required   true
                         :appearance "outlined"}
                        (validate-select-attrs req form-state :gig-type))]
     (concat
      (when include-blank?
        [[:wa-option {:value ""} " - "]])
      (for [gig-type domain/gig-types]
        (option (name gig-type) (tr [gig-type]) selected))))))

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
  [:wa-input (merge {:label      label
                     :name       name
                     :value      (form/text-value value)
                     :appearance "outlined"}
                    attrs)])

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

(defn- gig-remove-dialog [{:keys [tr] :as req} {:gig/keys [title] :as gig}]
  (ui2/remove-dialog
   {:id            (gig-remove-dialog-id gig)
    :label         (tr [:action/confirm-generic])
    :cancel-label  (tr [:action/cancel])
    :confirm-label (tr [:action/confirm-delete])
    :confirm-attrs {:data-id     "gig-edit-delete"
                    :data-action (d*/act req ::actions/delete-gig)}}
   [:p (tr [:action/confirm-delete-gig] [title])]))

(defn- save-button [tr]
  [button/Button {:appearance         "filled"
                  :variant            "brand"
                  :type               "submit"
                  :form               "gig-edit-form"
                  :data-attr:disabled "!!$loading && $loading !== 'gig-edit'"
                  :data-attr:loading  "$loading === 'gig-edit'"}
   (tr [:action/save])])

(defn- edit-form-actions [{:keys [tr]} gig]
  (ui2/action-bar
   {}
   [[button/Button {:appearance "outlined"
                    :href       (urls/link-gig gig)}
     (tr [:action/cancel])]
    [button/Button {:appearance  "outlined"
                    :variant     "danger"
                    :data-dialog (str "open " (gig-remove-dialog-id gig))}
     (tr [:action/delete])]
    (save-button tr)]))

(defn- create-form-actions [{:keys [tr]}]
  (ui2/action-bar
   {}
   [[button/Button {:appearance "outlined"
                    :href       (urls/link-gigs-home)}
     (tr [:action/cancel])]
    (save-button tr)]))

(defn- page-header [{:keys [tr]} title subtitle & breadcrumb-items]
  (ui2/page-header
   {:breadcrumb (into [breadcrumb/Breadcrumb
                       {}
                       [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-gigs-home)}
                        (tr [:nav/gigs])]]
                      breadcrumb-items)
    :title      title
    :subtitle   subtitle}))

(defn- edit-header [{:keys [tr] :as req} {:gig/keys [title gig-type status] :as gig}]
  (ui2/page-header
   {:breadcrumb [breadcrumb/Breadcrumb
                 {}
                 [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-gigs-home)}
                  (tr [:nav/gigs])]
                 [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-gig gig)}
                  (gigs.ui/gig-breadcrumb-label req gig)]
                 [breadcrumb/BreadcrumbItem (tr [:action/edit])]]
    :heading    [:div {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-detail-title"}
                 [:h1 (tr [:action/edit])]
                 (when status
                   (gigs.ui/gig-status-icon status {:class "gigs-detail-status-icon"}))]
    :subtitle   (str title " · " (tr [gig-type]))}))

(defn- create-header [{:keys [tr] :as req}]
  (page-header req
               (tr [:gig/create-title])
               nil
               [breadcrumb/BreadcrumbItem (tr [:gig/create-title])]))

(defn- gig->form [{:gig/keys [call-time contact date description end-date end-time gig-id gig-type leader location more-details outfit pay-deal post-gig-plans rehearsal-leader1 rehearsal-leader2 set-time status title]
                   :forum.topic/keys [topic-id]}]
  {:gig-id            (str gig-id)
   :title             (form/text-value title)
   :status            (some-> status name)
   :gig-type          (some-> gig-type name)
   :date              (form/date-value date)
   :end-date          (form/date-value end-date)
   :contact           (form/text-value (some-> contact :member/member-id str))
   :call-time         (form/time-value call-time)
   :set-time          (form/time-value set-time)
   :end-time          (form/time-value end-time)
   :location          (form/text-value location)
   :outfit            (form/text-value outfit)
   :pay-deal          (form/text-value pay-deal)
   :leader            (form/text-value leader)
   :rehearsal-leader1 (form/text-value (some-> rehearsal-leader1 :member/member-id str))
   :rehearsal-leader2 (form/text-value (some-> rehearsal-leader2 :member/member-id str))
   :post-gig-plans    (form/text-value post-gig-plans)
   :more-details      (form/text-value more-details)
   :description       (form/text-value description)
   :notify?           false
   :takeover-topic?   false
   :topic-id          (form/text-value topic-id)
   :_error            {}})

(defn- create->form [{:keys [tr]}]
  {:gig-id            ""
   :title             ""
   :status            "unconfirmed"
   :gig-type          ""
   :date              ""
   :end-date          ""
   :contact           ""
   :call-time         ""
   :set-time          ""
   :end-time          ""
   :location          ""
   :outfit            (tr [:orange-and-green])
   :pay-deal          ""
   :leader            ""
   :rehearsal-leader1 ""
   :rehearsal-leader2 ""
   :post-gig-plans    ""
   :more-details      ""
   :description       ""
   :notify?           false
   :thread?           true
   :topic-id          ""
   :_error            {}})

(defn- form-state [{:keys [page-state]} gig]
  (merge (gig->form gig) (:gig-edit page-state)))

(defn- create-form-state [{:keys [page-state] :as req}]
  (merge (create->form req) (:gig-edit page-state)))

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

(defn- notification-fields [tr form-state create?]
  (list
   (checkbox-input (tr [(if create?
                          :gig/email-about-new?
                          :gig/email-about-change?)])
                   "notify?"
                   (:notify? form-state)
                   "gig-edit.notify?")
   (when create?
     (checkbox-input (tr [:gig/create-a-forum-thread?])
                     "thread?"
                     (:thread? form-state)
                     "gig-edit.thread?"))))

(defn- main-fields
  ([req form-state]
   (main-fields req form-state {:create? false}))
  ([{:keys [db tr] :as req} form-state {:keys [create?]}]
   (let [members      (q/members-for-select-active db)
         field        #(validate-field-attrs req form-state %)
         select-field #(validate-select-attrs req form-state %)]
     (ui2/section-card
      {:title    (tr [:gig/gig-info])
       :divider? true}
      [:div {:class "gigs-edit-form-grid"}
       (input (tr [:gig/title]) "title" (:title form-state) (merge {:required true} (field :title)))
       (status-select req form-state (if create? domain/create-statuses domain/statuses))
       (gig-type-select req form-state create?)
       (input (tr [:gig/date]) "date" (:date form-state) (merge {:type "date" :required true} (field :date)))
       (input (tr [:gig/end-date]) "end-date" (:end-date form-state) (merge {:type "date"} (field :end-date)))
       (member-select (tr [:gig/contact]) "contact" (:contact form-state) members (select-field :contact))
       (input (tr [:gig/call-time]) "call-time" (:call-time form-state) (merge {:type "time" :required true} (field :call-time)))
       (input (tr [:gig/set-time]) "set-time" (:set-time form-state) (merge {:type "time"} (field :set-time)))
       (input (tr [:gig/end-time]) "end-time" (:end-time form-state) (merge {:type "time"} (field :end-time)))
       (input (tr [:gig/location]) "location" (:location form-state) (merge {:required true} (field :location)))
       (input (tr [:gig/outfit]) "outfit" (or (:outfit form-state) (tr [:orange-and-green])) (field :outfit))
       (input (tr [:gig/pay-deal]) "pay-deal" (:pay-deal form-state) (field :pay-deal))
       (input (tr [:gig/leader]) "leader" (:leader form-state) (field :leader))
       (when (probe-form? form-state)
         (list
          (member-select (tr [:gig/rehearsal-leader1]) "rehearsal-leader1" (:rehearsal-leader1 form-state) members (select-field :rehearsal-leader1))
          (member-select (tr [:gig/rehearsal-leader2]) "rehearsal-leader2" (:rehearsal-leader2 form-state) members (select-field :rehearsal-leader2))))
       (input (tr [:gig/post-gig-plans]) "post-gig-plans" (:post-gig-plans form-state) (merge {:class "gigs-edit-wide"} (field :post-gig-plans)))
       (markdown-textarea (tr [:gig/more-details]) "more-details" (:more-details form-state) (merge {:placeholder (tr [:gig/more-details-placeholder])
                                                                                                     :class       "gigs-edit-textarea markdown-editor hidden gigs-edit-wide"}
                                                                                                    (field :more-details)))
       (textarea (tr [:gig/description]) "description" (:description form-state) (merge {:class "gigs-edit-textarea gigs-edit-wide"}
                                                                                        (field :description)))
       (notification-fields tr form-state create?)]))))

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
                 top-error])
              (edit-form-actions req gig))))

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
                 top-error])
              (create-form-actions req))))

(defn- edit-page [{:keys [db] :as req}]
  (let [gig-id (http.util/path-param-uuid! req :gig/gig-id)
        gig    (q/retrieve-gig db gig-id)]
    (if gig
      (ui2/datastar-page
       [:div {:class "wa-stack wa-gap-2xl gigs-edit-page"}
        (edit-header req gig)
        (edit-form req gig)
        (gig-remove-dialog req gig)]
       (ui2/markdown-editor-scripts))
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id})))))

(defn- create-page [req]
  (ui2/datastar-page
   [:div {:class "wa-stack wa-gap-2xl gigs-edit-page"}
    (create-header req)
    (create-form req)]
   (ui2/markdown-editor-scripts)))

(defn page [req]
  (if (http.util/path-param req :gig/gig-id)
    (edit-page req)
    (create-page req)))

(d*/refresh-all!)
