(ns app.poll.index.views
  (:require
   [app.poll.queries :as queries]
   [app.poll.ui :as poll.ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]))

(defn- page-toolbar []
  [page-toolbar/PageToolbar
   {::page-toolbar/breadcrumb
    [breadcrumb/Breadcrumb
     {}
     [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-dashboard)}
      [:i18n/tr :home]]
     [breadcrumb/BreadcrumbItem [:i18n/tr :polls/title]]]
    ::page-toolbar/mobile-back
    [button/Button {:appearance "plain"
                    :href       (urls/link-dashboard)}
     [ico/Icon {::ico/library :phosphor
                ::ico/name    :arrow-left
                :slot         "start"}]
     [:i18n/tr :home]]
    ::page-toolbar/actions
    [[button/Button {:appearance "filled"
                     :variant    "brand"
                     :href       (urls/link-polls-create)}
      [:i18n/tr :polls/new-poll]]]
    :aria-label [:i18n/tr :polls/index-toolbar-label]}])

(defn page [{:keys [db tr] :as req}]
  (let [{:keys [running-polls past-polls]} (queries/index-page-data db)]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width   :standard
       ::page-surface/toolbar (page-toolbar)}
      [:div {:class "wa-stack wa-gap-l"}
       [page-header/PageHeader {:title [:i18n/tr :polls/title]}]
       [:div {:class "wa-grid wa-gap-m polls-index-columns"}
        (poll.ui/poll-section req {:title         (tr [:polls/running])
                                   :empty-message (tr [:polls/no-running])
                                   :polls         running-polls})
        (poll.ui/poll-section req {:title         (tr [:polls/past])
                                   :empty-message (tr [:polls/no-past])
                                   :polls         past-polls})]]])))
