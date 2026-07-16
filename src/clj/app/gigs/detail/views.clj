(ns app.gigs.detail.views
  (:require
   [app.config :as config]
   [app.datastar :as d*]
   [app.gigs.attendance.ui :as attendance.ui]
   [app.gigs.detail.actions :as actions]
   [app.gigs.domain :as domain]
   [app.gigs.queries :as gigs.queries]
   [app.gigs.ui :as gigs.ui]
   [app.html :as html]
   [app.markdown :as markdown]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.ui2.button :as button]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.icon :as ico]
   [app.urls :as urls]
   [app.util :as util]
   [app.util.http :as http.util]
   [clojure.string :as str]
   [tick.core :as t]))

(defn- muted [value]
  (if (str/blank? (str value))
    [:span {:class "wa-color-text-quiet"} "—"]
    value))

(defn- detail-item
  ([label value]
   (detail-item label value nil))
  ([label value attrs]
   (into [:div (or attrs {})]
         [[:dt label]
          [:dd value]])))

(defn- member-name [member]
  (muted (some-> member ui2/member-nick)))

(defn- blankish? [value]
  (str/blank? (str value)))

(defn- optional-item
  ([label value]
   (optional-item label value nil))
  ([label value attrs]
   (when-not (blankish? value)
     (detail-item label value attrs))))

(defn- optional-markdown-item [label markdown-text]
  (when-not (str/blank? markdown-text)
    (detail-item label
                 (markdown/render markdown-text)
                 {:class "gigs-detail-wide"})))

(defn- optional-lines-item [label text]
  (when-not (str/blank? text)
    (detail-item label
                 (interpose [:br] (str/split-lines text)))))

(defn- gig-date [req {:gig/keys [date end-date]}]
  (if date
    (ui2/date-range-display req :compact-with-weekday date end-date)
    (muted nil)))

(defn- gig-summary [{:gig/keys [title gig-type status]}]
  [page-header/PageHeader
   {:title [:span {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-detail-title"}
            (when status
              (gigs.ui/gig-status-icon status {:class "gigs-detail-status-icon"}))
            title
            [:wa-badge {:appearance "outlined" :class "wa-font-size-xs"}
             [:i18n/tr (domain/gig-type-label-key gig-type)]]]}])

(defn- gig-info-section
  [req
   {:gig/keys [call-time contact end-time leader location more-details outfit pay-deal post-gig-plans rehearsal-leader1 rehearsal-leader2 set-time setlist]
    :as       gig}]
  (ui2/section-card
   {:title [:i18n/tr :gigs/gig-info]}
   [:dl {:class "particulars gigs-detail-info-list"}
    (detail-item [:i18n/tr :gigs/date] (gig-date req gig))
    (detail-item [:i18n/tr :gigs/location] (if (str/blank? location)
                                             (muted nil)
                                             (markdown/render-one-line location)))
    (detail-item [:i18n/tr :gigs/contact] (member-name contact))
    (detail-item [:i18n/tr :gigs/call-time] (muted (ui2/format-time req :short call-time)))
    (optional-item [:i18n/tr :gigs/set-time] (ui2/format-time req :short set-time))
    (optional-item [:i18n/tr :gigs/end-time] (ui2/format-time req :short end-time))
    (optional-item [:i18n/tr :gigs/leader] leader)
    (when (domain/probe? gig)
      (list
       (optional-item [:i18n/tr :gigs/rehearsal-leader-1] (some-> rehearsal-leader1 ui2/member-nick))
       (optional-item [:i18n/tr :gigs/rehearsal-leader-2] (some-> rehearsal-leader2 ui2/member-nick))))
    (optional-item [:i18n/tr :gigs/pay-deal] pay-deal)
    (optional-item [:i18n/tr :gigs/outfit] outfit)
    (optional-markdown-item [:i18n/tr :gigs/more-details] more-details)
    (optional-lines-item [:i18n/tr :gigs/setlist] setlist)
    (optional-item [:i18n/tr :gigs/post-gig-plans] post-gig-plans)]))

(defn- setlist-section [{:keys [db]} gig-id]
  (let [songs (q/setlist-songs-for-gig db gig-id)]
    (ui2/section-card
     {:title    [:i18n/tr :gigs/setlist]
      :divider? true
      :actions  [[button/Button {:appearance "outlined"
                                 :variant    "brand"
                                 :href       (urls/link-gig-setlist gig-id)}
                  (if (seq songs)
                    [:i18n/tr :action/edit]
                    [:i18n/tr :gigs/create-setlist])]]}
     (gigs.ui/setlist-list songs))))

(defn- probeplan-section [{:keys [db]} gig-id]
  (let [songs (q/probeplan-songs-for-gig db gig-id)]
    (ui2/section-card
     {:title    [:i18n/tr :gigs/probeplan]
      :divider? true
      :actions  [[button/Button {:appearance "outlined"
                                 :variant    "brand"
                                 :href       (urls/link-gig-probeplan gig-id)}
                  (if (seq songs)
                    [:i18n/tr :action/edit]
                    [:i18n/tr :gigs/create-probeplan])]]}
     (gigs.ui/probeplan-list songs))))

(defn- planned-songs-section [req {:gig/keys [gig-id] :as gig}]
  (cond
    (domain/probe? gig) (probeplan-section req gig-id)
    (domain/gig? gig)   (setlist-section req gig-id)
    :else               nil))

(defn- section-name-wrappable [{:section/keys [name]}]
  (interpose [:span "/" [:wbr]]
             (str/split name #"/")))

(defn- attendance-section-view [req archived? idx section]
  [:div {:class (str "gigs-attendance-section"
                     (when archived? " gigs-attendance-section--archived")
                     (when (and archived? (even? idx)) " gigs-attendance-section--alt"))}
   [:div {:class "gigs-attendance-section-name"}
    (section-name-wrappable section)]
   [:div {:class "gigs-attendance-section-members"}
    (for [attendance (:members section)]
      (if archived?
        (attendance.ui/archived-attendance-row req attendance)
        (attendance.ui/editable-attendance-row req attendance)))]])

(defn- recent-reminder? [sent-at]
  (when sent-at
    (< (- (System/currentTimeMillis) (inst-ms sent-at))
       (* 24 60 60 1000))))

(defn- remind-all-menu-item [{:keys [page-state] :as req}]
  (let [sent-at       (get-in page-state actions/remind-all-sent-at-path)
        recent?       (recent-reminder? sent-at)
        dialog-id     "gig-detail-remind-all-dialog"]
    [:wa-dropdown-item {:data-dialog (str "open " dialog-id)}
     (when recent?
       [ico/Icon {::ico/library :snoico
                  ::ico/name    :circle-check
                  :slot         "icon"}])
     [:i18n/tr :gigs/remind-all]
     (when recent?
       [:span {:slot "details"}
        [:i18n/tr :gigs/reminded-all-at
         {:time (ui2/format-date-time req :medium sent-at)}]])]))

(defn- toolbar-action [href label]
  [button/Button {:appearance "outlined"
                  :variant    "brand"
                  :href       href}
   label])

(defn- toolbar-menu-item [href icon label]
  [:wa-dropdown-item {:value   href
                      :onclick "window.location = this.value"}
   [ico/Icon (assoc icon :slot "icon")]
   label])

(defn- gig-toolbar [req {:gig/keys [gig-id] :as gig}]
  (let [archived?     (domain/gig-archived? gig)
        today         (t/date (util/local-time-austria!))
        future?       (t/> (:gig/date gig) today)
        edit-url      (urls/link-gig-edit gig-id)
        log-plays-url (urls/link-gig-log-plays gig-id)
        edit-label    [:i18n/tr :action/edit]
        log-label     [:i18n/tr :gigs/log-plays]]
    [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                               [breadcrumb/Breadcrumb {}
                                [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-gigs-home)}
                                 [:i18n/tr :gigs/navigation-label]]
                                [breadcrumb/BreadcrumbItem (gigs.ui/gig-breadcrumb-label req gig)]]
                               ::page-toolbar/actions
                               [(if future?
                                  (toolbar-action edit-url edit-label)
                                  (toolbar-action log-plays-url log-label))]
                               ::page-toolbar/overflow-label [:i18n/tr :action/more-actions]
                               ::page-toolbar/overflow-items
                               (cond->
                                [(if future?
                                   (toolbar-menu-item log-plays-url
                                                      {::ico/library :snoico
                                                       ::ico/name    :music-note-outline}
                                                      log-label)
                                   (toolbar-menu-item edit-url
                                                      {::ico/library :phosphor
                                                       ::ico/name    :pencil-simple}
                                                      edit-label))]
                                 (not archived?) (conj (remind-all-menu-item req)))
                               :aria-label [:i18n/tr :gigs/detail-toolbar-label]}]))

(defn- remind-all-dialog [req gig-id]
  [:wa-dialog {:id    "gig-detail-remind-all-dialog"
               :label [:i18n/tr :gigs/remind-all-dialog-title]}
   [:p [:i18n/tr :gigs/remind-all-dialog-body]]
   [button/Button {:slot        "footer"
                   :appearance  "outlined"
                   :data-dialog "close"}
    [:i18n/tr :action/cancel]]
   [button/Button {:slot          "footer"
                   :appearance    "filled"
                   :variant       "brand"
                   :data-dialog   "close"
                   :data-on:click (attendance.ui/action-js req
                                                           ::actions/send-reminder-to-all
                                                           {:gig-id gig-id})}
    [:i18n/tr :gigs/remind-all-dialog-confirm]]])

(defn- attendance-actions [req archived? show-committed?]
  (when-not archived?
    [[button/Button (merge {:appearance "filled"
                            :variant    "brand"
                            :size       "s"}
                           (attendance.ui/action-attrs req
                                                       ::actions/toggle-attendance-committed
                                                       {:show-committed (not show-committed?)}))
      (if show-committed?
        [:i18n/tr :gigs/show-all-attendance]
        [:i18n/tr :gigs/show-committed-attendance])]]))

(defn- attendance-section [{:keys [db page-state] :as req} {:gig/keys [gig-id] :as gig}]
  (let [show-committed? (boolean (get-in page-state [:gig-detail :attendance :show-committed?]))
        {:keys [archived? sections summary]} (gigs.queries/attendance-data db gig show-committed?)]
    (ui2/section-card
     {:id       "gig-attendance"
      :title    [:i18n/tr :gigs/attendance]
      :class    "gigs-attendance-card"
      :divider? true
      :actions  (attendance-actions req archived? show-committed?)}
     (attendance.ui/summary-counts summary)
     [:div {:class "gigs-attendance-sections"}
      (map-indexed (fn [idx section]
                     (attendance-section-view (assoc req :gig-id gig-id) archived? idx section))
                   sections)])))

(defn- discourse-url [forum-url]
  (cond-> forum-url
    (not (str/ends-with? forum-url "/")) (str "/")))

(defn- discourse-embed-script [forum-url topic-id]
  (html/raw
   (format
    "
window.DiscourseEmbed = %s;

(function() {
  var d = document.createElement('script');
  d.type = 'text/javascript';
  d.async = true;
  d.src = window.DiscourseEmbed.discourseUrl + 'javascripts/embed.js';
  (document.getElementsByTagName('head')[0] || document.getElementsByTagName('body')[0]).appendChild(d);
})();
"
    (d*/->signals {"discourseUrl" (discourse-url forum-url)
                   "topicId"      topic-id}))))

(defn- discourse-comments-section [{:keys [system]} {:forum.topic/keys [topic-id]}]
  (when-let [forum-url (and topic-id (config/discourse-forum-url (:env system)))]
    (ui2/section-card
     {:id       "gig-forum-comments"
      :title    "Comments"
      :divider? true}
     [:div {:id                "discourse-comments"
            :data-ignore-morph ""}]
     [:script {:type "text/javascript"}
      (discourse-embed-script forum-url topic-id)])))

(defn page [{:keys [db] :as req}]
  (let [gig-id (http.util/path-param-uuid! req :gig/gig-id)
        gig    (q/retrieve-gig db gig-id)]
    (if gig
      (ui2/datastar-page*
       [page-surface/PageSurface {::page-surface/toolbar (gig-toolbar req gig)}
        [:div {:class        "wa-stack wa-gap-2xl"
               :data-signals (d*/->signals (attendance.ui/attendance-signals req))}
         (gig-summary gig)
         (gig-info-section req gig)
         (planned-songs-section req gig)
         (attendance-section req gig)
         (discourse-comments-section req gig)]]
       (when-not (domain/gig-archived? gig)
         (remind-all-dialog req gig-id)))
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id})))))

(d*/refresh-all!)
