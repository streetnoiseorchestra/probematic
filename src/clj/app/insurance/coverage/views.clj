(ns app.insurance.coverage.views
  (:require
   [app.datastar :as d*]
   [app.insurance.queries :as queries]
   [app.insurance.ui :as insurance-ui]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]))

(defn- coverage-id [{:keys [parameters path-params]}]
  (let [value (or (get-in parameters [:path :coverage-id])
                  (:coverage-id path-params))]
    (cond
      (uuid? value) value
      (string? value) (parse-uuid value))))

(defn- page-actions [coverage policy]
  (when (queries/policy-editable? policy)
    [[button/Button {:appearance "outlined"
                     :variant    "brand"
                     :href       (urls/link-coverage-edit coverage)}
      [ico/Icon {::ico/library :snoico
                 ::ico/name    :cog
                 :slot         "start"}]
      [:i18n/tr :action/edit]]]))

(defn page [{:keys [db] :as req}]
  (let [coverage   (queries/coverage db (coverage-id req))
        policy     (:insurance.policy/_covered-instruments coverage)
        instrument (:instrument.coverage/instrument coverage)]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :wide
       ::page-surface/toolbar
       [page-toolbar/PageToolbar
        {::page-toolbar/breadcrumb
         [breadcrumb/Breadcrumb
          {::breadcrumb/max-items [2 2]}
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
           [:i18n/tr :insurance/title]]
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
           (:insurance.policy/name policy)]
          [breadcrumb/BreadcrumbItem (:instrument/name instrument)]]
         ::page-toolbar/mobile-back
         [button/BackButton {:href  (urls/link-policy policy)
                             :label (:insurance.policy/name policy)}]
         ::page-toolbar/actions (page-actions coverage policy)
         :aria-label [:i18n/tr :insurance/toolbar-label]}]}
      [:div {:class "insurance-coverage-detail-page wa-stack wa-gap-xl"}
       [page-header/PageHeader {:class "insurance-coverage-page-header"
                                :title (:instrument/name instrument)}]
       [:div {:class "wa-flank:end wa-align-items-start" :style "--flank-size: 50ch;"}
        (insurance-ui/coverage-detail-card req {:coverage coverage
                                                :policy   policy})
        (insurance-ui/comments-aside req)]
       (insurance-ui/history-section req coverage)]])))

(d*/refresh-all!)
