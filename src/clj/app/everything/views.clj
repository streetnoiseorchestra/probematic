(ns app.everything.views
  (:require
   [app.datastar :as d*]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]))

(def ^:private groups
  [{:label :everything/gig-work
    :destinations
    [{:label       :everything/gigs-dashboard
      :description :everything/gigs-dashboard-description
      :href        (urls/link-dashboard)
      :icon        :home}
     {:label       :everything/all-gigs
      :description :everything/all-gigs-description
      :href        (urls/link-gigs-home)
      :icon        :trumpet}
     {:label       :everything/calendar
      :description :everything/calendar-description
      :href        (urls/link-calendar)
      :icon        :calendar}
     {:label       :everything/probeplan
      :description :everything/probeplan-description
      :href        (urls/link-probeplan-home)
      :icon        :dots-six-vertical}
     {:label       :everything/repertoire
      :description :everything/repertoire-description
      :href        (urls/link-songs-home)
      :icon        :music-note-outline}]}
   {:label :everything/band
    :destinations
    [{:label       :everything/activity
      :description :everything/activity-description
      :icon        :comments
      :disabled?   true}
     {:label       :everything/members
      :description :everything/members-description
      :href        "/members"
      :icon        :users-outline}
     {:label       :everything/polls
      :description :everything/polls-description
      :href        (urls/link-polls-home)
      :icon        :question}
     {:label       :everything/insurance
      :description :everything/insurance-description
      :href        (urls/link-insurance)
      :icon        :shield-check-outline}
     {:label       :everything/statistics
      :description :everything/statistics-description
      :href        "/stats"
      :icon        :chart-bar-square}]}
   {:label :everything/collaboration
    :destinations
    [{:label       :everything/forum
      :description :everything/forum-description
      :href        "https://forum.streetnoise.at"
      :icon        :snomegaphone
      :external?   true}
     {:label       :everything/files
      :description :everything/files-description
      :href        "https://data.streetnoise.at/apps/files/"
      :icon        :folder-open
      :external?   true}
     {:label       :everything/chat
      :description :everything/chat-description
      :href        "https://chat.streetnoise.at"
      :icon        :comments
      :external?   true}]}
   {:label :everything/administration
    :destinations
    [{:label       :everything/band-settings
      :description :everything/band-settings-description
      :href        "/band-settings"
      :icon        :cog}]}])

(defn- page-toolbar []
  [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                             [breadcrumb/Breadcrumb {}
                              [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-dashboard)}
                               [:i18n/tr :home]]
                              [breadcrumb/BreadcrumbItem [:i18n/tr :everything/title]]]
                             :aria-label [:i18n/tr :everything/toolbar-label]}])

(defn- destination-copy [{:keys [description external? label disabled?]}]
  [:span {:class "copy wa-stack wa-gap-2xs"}
   [:span {:class "label wa-cluster wa-gap-2xs"}
    [:span [:i18n/tr label]]
    (when external?
      [ico/Icon {::ico/library :phosphor
                 ::ico/name    :arrow-square-out
                 :class        "external"}])
    (when disabled?
      [:span {:class "coming-soon"}
       [:i18n/tr :everything/coming-soon]])]
   [:span {:class "description"}
    [:i18n/tr description]]])

(defn- destination-icon [icon]
  [:span {:class "icon wa-cluster wa-justify-content-center"}
   [ico/Icon {::ico/library :snoico
              ::ico/name    icon}]])

(defn- destination [{:keys [disabled? external? href icon] :as destination}]
  (let [attrs (cond-> {:class "destination"}
                disabled? (assoc :type "button" :disabled true)
                href      (assoc :href href)
                external? (assoc :target "_blank"
                                 :rel "noopener noreferrer"))]
    [(if disabled? :button :a)
     attrs
     (destination-icon icon)
     (destination-copy destination)]))

(defn- group [{:keys [destinations label]}]
  [:section {:class "group wa-stack wa-gap-s"}
   [:h2 [:i18n/tr label]]
   (into
    [:div {:class "destinations wa-grid wa-gap-s"}]
    (map destination)
    destinations)])

(defn page [_req]
  (ui2/datastar-page*
   [page-surface/PageSurface
    {::page-surface/width   :standard
     ::page-surface/toolbar (page-toolbar)}
    [:div {:class "everything-directory wa-stack wa-gap-2xl"}
     [page-header/PageHeader
      {:class    "heading wa-text-center"
       :title    [:i18n/tr :everything/title]
       :subtitle [:i18n/tr :everything/subtitle]}]
     (into
      [:div {:class "groups wa-stack wa-gap-xl"}]
      (map group)
      groups)]]))

(d*/refresh-all!)
