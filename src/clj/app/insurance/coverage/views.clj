(ns app.insurance.coverage.views
  (:require
   [app.datastar :as d*]
   [app.insurance.coverage.queries :as queries]
   [app.insurance.ui :as insurance-ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.urls :as urls]))

(defn- coverage-id [{:keys [parameters path-params]}]
  (let [value (or (get-in parameters [:path :coverage-id])
                  (:coverage-id path-params))]
    (cond
      (uuid? value) value
      (string? value) (parse-uuid value))))

(defn- breadcrumb [{:keys [tr]} policy instrument]
  [breadcrumb/Breadcrumb {:class "insurance-coverage-breadcrumb"}
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-insurance)}
    [ico/Icon {::ico/library :snoico
               ::ico/name    :shield-check-outline}]
    (tr [:nav/insurance])]
   [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-policy policy)}
    (:insurance.policy/name policy)]
   [breadcrumb/BreadcrumbItem
    (:instrument/name instrument)]])

(defn- page-actions [{:keys [tr]} coverage policy]
  (when (queries/policy-editable? policy)
    [[button/Button {:appearance "outlined"
                     :variant    "brand"
                     :href       (urls/link-coverage-edit coverage)}
      [ico/Icon {::ico/library :snoico
                 ::ico/name    :cog
                 :slot         "start"}]
      (tr [:action/edit])]]))

(defn page [{:keys [db] :as req}]
  (let [coverage   (queries/coverage db (coverage-id req))
        policy     (:insurance.policy/_covered-instruments coverage)
        instrument (:instrument.coverage/instrument coverage)]
    (ui2/plain-page
     [:div {:class "insurance-coverage-detail-page wa-stack wa-gap-xl"}
      (breadcrumb req policy instrument)
      (ui2/page-header {:class   "insurance-coverage-page-header"
                        :title   (:instrument/name instrument)
                        :actions (page-actions req coverage policy)})
      [:div {:class "wa-flank:end wa-align-items-start" :style "--flank-size: 50ch;"}
       (insurance-ui/coverage-detail-card req {:coverage coverage
                                               :policy   policy})
       (insurance-ui/comments-aside req)]
      (insurance-ui/history-section req coverage)])))

(d*/refresh-all!)
