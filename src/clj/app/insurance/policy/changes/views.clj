(ns app.insurance.policy.changes.views
  (:require
   [app.config :as config]
   [app.datastar :as d*]
   [app.form :as form]
   [app.insurance.exporters :as exporters]
   [app.insurance.policy.changes.actions :as actions]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util :as util]
   [tick.core :as t]))

(defn- field
  [form-state field-name label attrs]
  (let [error (form/field-error form-state field-name)
        id    (str "insurance-policy-changes-" (name field-name))]
    [:label {:class "wa-stack wa-gap-2xs"
             :for   id}
     [:span {:class "wa-caption-s wa-font-weight-bold"} label]
     [:input (cond-> (merge {:id        id
                             :name      (name field-name)
                             :value     (form/text-value (field-name form-state))
                             :data-bind (str "insurance-policy-changes." (name field-name))}
                            attrs)
               error (assoc :aria-invalid     "true"
                            :aria-describedby (str id "-error")))]
     (when error
       [:small {:id    (str id "-error")
                :class "wa-caption-s text-danger"}
        error])]))

(defn- attachment
  [req form-state export-enabled? {:keys [field-name title type]}]
  [:div {:class "wa-flank wa-align-items-center"
         :style "--flank-size: 3rem;"}
   [ico/Icon {::ico/library :snoico
              ::ico/name    :file-excel-outline
              :aria-hidden  true}]
   [:div {:class "wa-stack wa-gap-xs"}
    [:h3 {:class "wa-heading-s" :style "margin: 0;"} title]
    (field form-state field-name (get-in form-state [:labels :attachment-filename])
           {:type "text" :required true})
    [button/Button (cond-> {:appearance    "outlined"
                            :type          "button"
                            :data-on:click (str "$insurance-policy-changes.preview-type = '"
                                                type
                                                "'; @post('"
                                                (d*/act req ::actions/preview-attachment)
                                                "')")}
                     (not export-enabled?) (assoc :disabled true))
     [:i18n/tr :insurance/preview]]]])

(defn- confirm-dialog
  [{:keys [tr] :as req} id title body action label disabled?]
  [:wa-dialog {:id    id
               :label title}
   [:p body]
   [button/Button {:slot        "footer"
                   :appearance  "outlined"
                   :data-dialog "close"}
    (tr [:action/cancel])]
   [button/Button (cond-> {:slot               "footer"
                           :appearance         "filled"
                           :variant            "brand"
                           :data-dialog        "close"
                           :data-id            id
                           :data-action        (d*/act req action)
                           :data-attr:disabled (str "!!$loading && $loading !== '" id "'")
                           :data-attr:loading  (str "$loading === '" id "'")}
                    disabled? (assoc :disabled true))
    label]])

(defn page
  [{:keys [db tr] :as req}]
  (let [policy-id   (util/ensure-uuid! (get-in req [:path-params :policy-id]))
        policy      (q/retrieve-policy db policy-id)
        policy-name (:insurance.policy/name policy)
        {:keys [policy-number recipient-email recipient-name recipient-title]}
        (config/external-insurance-policy (-> req :system :env))
        sender       (get-in req [:session :session/member :member/name])
        today        (t/format (t/formatter "yyyy-M-d") (t/today))
        defaults     {:policy-id                   (str policy-id)
                      :recipient                   (format "%s <%s>" recipient-name recipient-email)
                      :subject                     (tr [:insurance/changes-email-subject]
                                                       {:policy-number (or policy-number "")})
                      :body                        (tr [:insurance/changes-email-body]
                                                       {:recipient-title (or recipient-title "")
                                                        :recipient-name  (or recipient-name "")
                                                        :sender-name     (or sender "")})
                      :attachment-filename-new     (format "AnlageNeueInstrumente-%s.xls" today)
                      :attachment-filename-changes (format "AnlageÄnderungen-%s.xls" today)
                      :preview-type                ""
                      :labels                      {:attachment-filename (tr [:insurance/attachment-filename])}}
        form-state   (merge defaults (get-in req [:page-state actions/form-key]))
        top-error    (form/field-error form-state :_top)
        export-enabled? (exporters/configured? policy)
        exporter-guidance-key
        (exporters/configuration-guidance-key policy)]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :standard
       ::page-surface/toolbar
       [page-toolbar/PageToolbar
        {::page-toolbar/breadcrumb
         [breadcrumb/Breadcrumb
          {::breadcrumb/max-items [2 2]}
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
           [:i18n/tr :insurance/title]]
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
           policy-name]
          [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/send-changes]]]
         ::page-toolbar/mobile-back
         [button/BackButton {:href  (urls/link-policy policy)
                             :label policy-name}]
         ::page-toolbar/actions
         [[button/Button {:appearance "outlined"
                          :href       (urls/link-policy policy)}
           [:i18n/tr :action/cancel]]
          [button/Button (cond-> {:appearance  "filled"
                                  :variant     "brand"
                                  :data-dialog "open insurance-policy-send-changes-dialog"}
                           (not export-enabled?) (assoc :disabled true))
           [:i18n/tr :insurance/confirm-and-send]]]
         ::page-toolbar/overflow-label [:i18n/tr :action/more-actions]
         ::page-toolbar/overflow-items
         [[:wa-dropdown-item {:data-dialog "open insurance-policy-confirm-changes-dialog"}
           [:i18n/tr :insurance/confirm-skip-send]]]
         :aria-label [:i18n/tr :insurance/toolbar-label]}]}
      [:div {:class        "wa-stack wa-gap-xl"
             :data-signals (d*/->signals {actions/form-key
                                          (dissoc form-state :_error :labels)})}
       [page-header/PageHeader
        {:title    (tr [:insurance/changes-title])
         :subtitle (tr [:insurance/changes-subtitle])}]
       (when top-error
         [:wa-callout {:appearance "outlined"
                       :variant    "danger"}
          top-error])
       (when exporter-guidance-key
         [:wa-callout {:id         "insurance-policy-exporter-guidance"
                       :appearance "outlined"
                       :variant    "warning"}
          [:i18n/tr exporter-guidance-key]])
       (ui2/section-card
        {:title (tr [:insurance/message-details])}
        [:div {:class "wa-stack wa-gap-m"}
         (field form-state :recipient (tr [:insurance/recipient]) {:type "text" :required true})
         (field form-state :subject (tr [:insurance/subject]) {:type "text" :required true})
         [:label {:class "wa-stack wa-gap-2xs"
                  :for   "insurance-policy-changes-body"}
          [:span {:class "wa-caption-s wa-font-weight-bold"} (tr [:insurance/message])]
          [:textarea {:id             "insurance-policy-changes-body"
                      :name           "body"
                      :rows           12
                      :data-auto-size "true"
                      :data-bind      "insurance-policy-changes.body"}
           (:body form-state)]]])
       (ui2/section-card
        {:title    (tr [:insurance/attachments])
         :subtitle (tr [:insurance/attachments-subtitle])}
        [:div {:id    "insurance-policy-attachments"
               :class "wa-stack wa-gap-l"}
         (attachment req form-state export-enabled?
                     {:field-name :attachment-filename-new
                      :type  "new"
                      :title (tr [:insurance/new-instruments])})
         (attachment req form-state export-enabled?
                     {:field-name :attachment-filename-changes
                      :type  "changes"
                      :title (tr [:insurance/changed-and-removed-instruments])})])
       (confirm-dialog req
                       "insurance-policy-send-changes-dialog"
                       (tr [:insurance/confirm-send-title])
                       (tr [:insurance/confirm-send-body])
                       ::actions/send-and-confirm
                       (tr [:insurance/confirm-and-send])
                       (not export-enabled?))
       (confirm-dialog req
                       "insurance-policy-confirm-changes-dialog"
                       (tr [:insurance/confirm-without-sending-title])
                       (tr [:insurance/confirm-without-sending-body])
                       ::actions/confirm-changes
                       (tr [:insurance/confirm-skip-send])
                       false)]])))

(d*/refresh-all!)
