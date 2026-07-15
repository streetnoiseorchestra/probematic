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
   [app.urls :as urls]
   [tick.core :as t]))

(defn- field
  [form-state field-name label attrs]
  (let [error (form/field-error form-state field-name)
        id    (str "insurance-policy-create-" (name field-name))]
    [:label {:class "wa-stack wa-gap-2xs"
             :for   id}
     [:span {:class "wa-caption-s wa-font-weight-bold"} label]
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
  [{:keys [tr] :as req}]
  (let [this-year  (-> (t/year (t/now)) str parse-long)
        next-year  (inc this-year)
        defaults   {:name            (tr [:insurance/default-policy-name]
                                         {:start-year (str this-year)
                                          :end-year   (str next-year)})
                    :effective-at    (str this-year "-05-01")
                    :effective-until (str next-year "-04-30")
                    :base-factor     "0.0047829"}
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
        {:title    (tr [:insurance/create-title])
         :subtitle (tr [:insurance/create-subtitle])}]
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
         {:title    (tr [:insurance/policy-details])
          :subtitle (tr [:insurance/create-subtitle])}
         [:div {:class "wa-stack wa-gap-m"}
          (field form-state :name (tr [:insurance/name]) {:type "text" :required true})
          [:div {:class "wa-grid wa-gap-m" :style "--min-column-size: 14rem;"}
           (field form-state :effective-at (tr [:insurance/effective-at]) {:type "date" :required true})
           (field form-state :effective-until (tr [:insurance/effective-until]) {:type "date" :required true})]
          (field form-state :base-factor (tr [:insurance/premium-base-factor])
                 {:type "number" :min "0" :step "any" :required true})])]]])))

(d*/refresh-all!)
