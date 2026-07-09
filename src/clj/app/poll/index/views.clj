(ns app.poll.index.views
  (:require
   [app.poll.queries :as queries]
   [app.poll.ui :as poll.ui]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.button :as button]
   [app.urls :as urls]))

(defn- page-header [{:keys [tr]}]
  [page-header/PageHeader
   {:title   (tr [:polls/index-title])
    :actions [[button/Button {:appearance "filled"
                              :variant    "brand"
                              :href       (urls/link-polls-create)}
               (tr [:polls/create-title])]]}])

(defn page [{:keys [db tr] :as req}]
  (let [{:keys [running-polls past-polls]} (queries/index-page-data db)]
    (ui2/plain-page
     [:div {:class "wa-stack wa-gap-l"}
      (page-header req)
      [:div {:class "wa-grid wa-gap-m polls-index-columns"}
       (poll.ui/poll-section req {:title         (tr [:polls/running])
                                  :empty-message (tr [:polls/no-running])
                                  :polls         running-polls})
       (poll.ui/poll-section req {:title         (tr [:polls/past])
                                  :empty-message (tr [:polls/no-past])
                                  :polls         past-polls})]])))
