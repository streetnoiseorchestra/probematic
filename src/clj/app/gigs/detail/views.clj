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

(defn- gig-summary [{:keys [tr]} {:gig/keys [title gig-type status]}]
  [page-header/PageHeader
   {:title [:span {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-detail-title"}
            (when status
              (gigs.ui/gig-status-icon status {:class "gigs-detail-status-icon"}))
            title
            [:wa-badge {:appearance "outlined" :class "wa-font-size-xs"} (tr [gig-type])]]}])

(defn- gig-info-section
  [{:keys [tr] :as req}
   {:gig/keys [call-time contact end-time leader location more-details outfit pay-deal post-gig-plans rehearsal-leader1 rehearsal-leader2 set-time setlist]
    :as       gig}]
  (ui2/section-card
   {:title (tr [:gig/gig-info])}
   [:dl {:class "particulars gigs-detail-info-list"}
    (detail-item (tr [:gig/date]) (gig-date req gig))
    (detail-item (tr [:gig/location]) (if (str/blank? location)
                                        (muted nil)
                                        (markdown/render-one-line location)))
    (detail-item (tr [:gig/contact]) (member-name contact))
    (detail-item (tr [:gig/call-time]) (muted (ui2/format-time req :short call-time)))
    (optional-item (tr [:gig/set-time]) (ui2/format-time req :short set-time))
    (optional-item (tr [:gig/end-time]) (ui2/format-time req :short end-time))
    (optional-item (tr [:gig/leader]) leader)
    (when (domain/probe? gig)
      (list
       (optional-item (tr [:gig/rehearsal-leader1]) (some-> rehearsal-leader1 ui2/member-nick))
       (optional-item (tr [:gig/rehearsal-leader2]) (some-> rehearsal-leader2 ui2/member-nick))))
    (optional-item (tr [:gig/pay-deal]) pay-deal)
    (optional-item (tr [:gig/outfit]) outfit)
    (optional-markdown-item (tr [:gig/more-details]) more-details)
    (optional-lines-item (tr [:gig/setlist]) setlist)
    (optional-item (tr [:gig/post-gig-plans]) post-gig-plans)]))

(defn- setlist-section [{:keys [db tr]} gig-id]
  (let [songs (q/setlist-songs-for-gig db gig-id)]
    (ui2/section-card
     {:title    (tr [:gig/setlist])
      :divider? true
      :actions  [[button/Button {:appearance "outlined"
                                 :variant    "brand"
                                 :href       (urls/link-gig-setlist gig-id)}
                  (if (seq songs)
                    (tr [:action/edit])
                    (tr [:gig/create-setlist]))]]}
     (gigs.ui/setlist-list songs))))

(defn- probeplan-section [{:keys [db tr]} gig-id]
  (let [songs (q/probeplan-songs-for-gig db gig-id)]
    (ui2/section-card
     {:title    (tr [:gig/probeplan])
      :divider? true
      :actions  [[button/Button {:appearance "outlined"
                                 :variant    "brand"
                                 :href       (urls/link-gig-probeplan gig-id)}
                  (if (seq songs)
                    (tr [:action/edit])
                    (tr [:gig/create-probeplan]))]]}
     (gigs.ui/probeplan-list tr songs))))

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
    [page-toolbar/PageToolbar
     {::page-toolbar/breadcrumb
      [breadcrumb/Breadcrumb
       {}
       [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-gigs-home)}
        [:i18n/tr :gigs/navigation-label]]
       [breadcrumb/BreadcrumbItem (gigs.ui/gig-breadcrumb-label req gig)]]
      ::page-toolbar/mobile-back
      [button/Button {:appearance "plain"
                      :href       (urls/link-gigs-home)}
       [ico/Icon {::ico/library :phosphor
                  ::ico/name    :arrow-left
                  :slot         "start"}]
       [:i18n/tr :gigs/navigation-label]]
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

(defn- remind-all-dialog [{:keys [tr] :as req} gig-id]
  [:wa-dialog {:id    "gig-detail-remind-all-dialog"
               :label (tr [:reminders/confirm-remind-all-title])}
   [:p (tr [:reminders/confirm-remind-all])]
   [button/Button {:slot        "footer"
                   :appearance  "outlined"
                   :data-dialog "close"}
    (tr [:action/cancel])]
   [button/Button {:slot          "footer"
                   :appearance    "filled"
                   :variant       "brand"
                   :data-dialog   "close"
                   :data-on:click (attendance.ui/action-js req
                                                           ::actions/send-reminder-to-all
                                                           {:gig-id gig-id})}
    (tr [:reminders/confirm])]])

(defn- attendance-actions [req archived? show-committed?]
  (when-not archived?
    [[button/Button (merge {:appearance "filled"
                            :variant    "brand"
                            :size       "s"}
                           (attendance.ui/action-attrs req
                                                       ::actions/toggle-attendance-committed
                                                       {:show-committed (not show-committed?)}))
      (if show-committed?
        ((:tr req) [:gig/show-all])
        ((:tr req) [:gig/show-committed]))]]))

(defn- attendance-section [{:keys [db page-state tr] :as req} {:gig/keys [gig-id] :as gig}]
  (let [show-committed? (boolean (get-in page-state [:gig-detail :attendance :show-committed?]))
        {:keys [archived? sections summary]} (gigs.queries/attendance-data db gig show-committed?)]
    (ui2/section-card
     {:id       "gig-attendance"
      :title    (tr [:gig/attendance])
      :class    "gigs-attendance-card"
      :divider? true
      :actions  (attendance-actions req archived? show-committed?)}
     (attendance.ui/summary-counts tr summary)
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
       [page-surface/PageSurface
        {::page-surface/width :wide
         ::page-surface/toolbar (gig-toolbar req gig)}
        [:div {:class        "wa-stack wa-gap-2xl"
               :data-signals (d*/->signals (attendance.ui/attendance-signals req))}
         (gig-summary req gig)
         (gig-info-section req gig)
         (planned-songs-section req gig)
         (attendance-section req gig)
         (discourse-comments-section req gig)]]
       (when-not (domain/gig-archived? gig)
         (remind-all-dialog req gig-id)))
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id})))))

(d*/refresh-all!)
