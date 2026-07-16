(ns app.insurance.policy.create.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.insurance.policy.create.actions :as actions]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]))

(defn- required-marker []
  [:span {:aria-hidden "true"} " *"])

(defn- field
  [form-state field-name label attrs]
  (let [error (form/field-error form-state field-name)
        id    (str "insurance-policy-create-" (name field-name))]
    [:label {:class "wa-stack wa-gap-2xs"
             :for   id}
     [:span {:class "wa-caption-s wa-font-weight-bold"}
      label
      (when (:required attrs)
        (required-marker))]
     [:input (cond-> (merge {:id        id
                             :name      (name field-name)
                             :value     (form/text-value (field-name form-state))
                             :data-bind (str "insurance-policy-create." (name field-name))}
                            attrs)
               error (assoc :aria-invalid     "true"
                            :aria-describedby (str id "-error")))]
     (when error
       [:small {:id    (str id "-error")
                :class "wa-caption-s text-danger"}
        error])]))

(defn page
  [req]
  (let [defaults   (actions/default-form (:tr req))
        form-state (merge defaults (get-in req [:page-state actions/form-key]))]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :standard
       ::page-surface/toolbar
       [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                  [breadcrumb/Breadcrumb {}
                                   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
                                    [:i18n/tr :insurance/title]]
                                   [breadcrumb/BreadcrumbItem [:i18n/tr :insurance/create-title]]]
                                  ::page-toolbar/actions
                                  [[button/Button {:appearance "outlined"
                                                   :href       (urls/link-insurance)}
                                    [:i18n/tr :action/cancel]]
                                   [button/Button {:appearance         "filled"
                                                   :variant            "brand"
                                                   :type               "submit"
                                                   :form               "insurance-policy-create-form"
                                                   :data-attr:disabled "!!$loading && $loading !== 'insurance-policy-create'"
                                                   :data-attr:loading  "$loading === 'insurance-policy-create'"}
                                    [:i18n/tr :action/create]]]
                                  :aria-label [:i18n/tr :insurance/toolbar-label]}]}
      [:div {:class        "wa-stack wa-gap-xl"
             :data-signals (d*/->signals {actions/form-key (dissoc form-state :_error)})}
       [page-header/PageHeader
        {:title    [:i18n/tr :insurance/create-title]
         :subtitle [:i18n/tr :insurance/create-subtitle]}]
       [:form {:id             "insurance-policy-create-form"
               :class          "wa-stack wa-gap-l"
               :data-id        "insurance-policy-create"
               :data-action    (d*/act req ::actions/create-policy)
               :data-on:submit "evt.preventDefault()"}
        (when-let [error (form/field-error form-state :_top)]
          [:wa-callout {:appearance "outlined"
                        :variant    "danger"}
           error])
        (ui2/section-card
         {:title    [:i18n/tr :insurance/policy-details]
          :subtitle [:i18n/tr :insurance/create-subtitle]}
         [:div {:class "wa-stack wa-gap-m"}
          (field form-state :name [:i18n/tr :insurance/name] {:type "text" :required true})
          [:div {:class "wa-grid wa-gap-m" :style "--min-column-size: 14rem;"}
           (field form-state :effective-at [:i18n/tr :insurance/effective-at] {:type "date" :required true})
           (field form-state :effective-until [:i18n/tr :insurance/effective-until] {:type "date" :required true})]
          (field form-state :base-factor [:i18n/tr :insurance/premium-base-factor]
                 {:type "number" :min "0" :step "any" :required true})])]]])))

(d*/refresh-all!)
