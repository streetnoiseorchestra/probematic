(ns app.gigs.detail.views
  (:require
   [app.datastar :as d*]
   [app.gigs.domain :as domain]
   [app.gigs.ui :as gigs.ui]
   [app.markdown :as markdown]
   [app.queries :as q]
   [app.ui :as ui]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]))

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
  (muted (some-> member ui/member-nick)))

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
                 (interpose [:br] (str/split-lines text))
                 {:class "gigs-detail-wide"})))

(defn- gig-date [{:gig/keys [date end-date]}]
  (cond
    (and date end-date) (ui/daterange date end-date)
    date                (ui/datetime date)
    :else               (muted nil)))

(defn- header-actions [{:keys [tr]}]
  [:div {:class "wa-cluster wa-gap-xs"}
   [:wa-button {:appearance "outlined"
                :disabled   true}
    (tr [:action/edit])]
   [:wa-button {:appearance "outlined"
                :variant    "brand"
                :disabled   true}
    "Log Plays"]])

(defn- gig-summary [{:keys [tr] :as req} {:gig/keys [title gig-type status]}]
  [:header {:class "gigs-detail-header wa-stack wa-gap-m"}
   [:wa-breadcrumb
    [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
    [:wa-breadcrumb-item {:href (urls/link-gigs-home)}
     (tr [:nav/gigs])]
    [:wa-breadcrumb-item title]]
   [:section {:class "wa-stack wa-gap-l"}
    [:div {:class "wa-flank:end wa-align-items-start"}
     [:div {:class "wa-stack wa-gap-2xs"}
      [:div {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-detail-title"}
       [:h1 title]
       (when status
         (gigs.ui/gig-status-icon status {:class "gigs-detail-status-icon"}))]
      [:span {:class "wa-caption-s"}
       (tr [gig-type])]]
     (header-actions req)]]])

(defn- gig-info-section [{:keys [tr]} {:gig/keys [call-time contact end-time leader location more-details outfit pay-deal post-gig-plans rehearsal-leader1 rehearsal-leader2 set-time setlist] :as gig}]
  (ui2/section-card
   {:title (tr [:gig/gig-info])}
   [:dl {:class "particulars"}
    (detail-item (tr [:gig/date]) (gig-date gig))
    (detail-item (tr [:gig/location]) (if (str/blank? location)
                                        (muted nil)
                                        (markdown/render-one-line location)))
    (detail-item (tr [:gig/contact]) (member-name contact))
    (detail-item (tr [:gig/call-time]) (muted (ui/time call-time)))
    (optional-item (tr [:gig/set-time]) (ui/time set-time))
    (optional-item (tr [:gig/end-time]) (ui/time end-time))
    (optional-item (tr [:gig/leader]) leader)
    (when (domain/probe? gig)
      (list
       (optional-item (tr [:gig/rehearsal-leader1]) (some-> rehearsal-leader1 ui/member-nick))
       (optional-item (tr [:gig/rehearsal-leader2]) (some-> rehearsal-leader2 ui/member-nick))))
    (optional-item (tr [:gig/pay-deal]) pay-deal)
    (optional-item (tr [:gig/outfit]) outfit)
    (optional-markdown-item (tr [:gig/more-details]) more-details)
    (optional-lines-item (tr [:gig/setlist]) setlist)
    (optional-item (tr [:gig/post-gig-plans]) post-gig-plans {:class "gigs-detail-wide"})]))

(defn- disabled-create-button [label]
  [:wa-button {:appearance "outlined"
               :variant    "brand"
               :disabled   true}
   label])

(defn- setlist-section [{:keys [db tr]} gig-id]
  (let [songs (q/setlist-songs-for-gig db gig-id)]
    (ui2/section-card
     {:title    (tr [:gig/setlist])
      :divider? true
      :actions  (when-not (seq songs)
                  [(disabled-create-button (tr [:gig/create-setlist]))])}
     (gigs.ui/setlist-list songs))))

(defn- probeplan-section [{:keys [db tr]} gig-id]
  (let [songs (q/probeplan-songs-for-gig db gig-id)]
    (ui2/section-card
     {:title    (tr [:gig/probeplan])
      :divider? true
      :actions  (when-not (seq songs)
                  [(disabled-create-button (tr [:gig/create-probeplan]))])}
     (gigs.ui/probeplan-list tr songs))))

(defn- planned-songs-section [req {:gig/keys [gig-id] :as gig}]
  (cond
    (domain/probe? gig) (probeplan-section req gig-id)
    (domain/gig? gig)   (setlist-section req gig-id)
    :else               nil))

(defn page [{:keys [db] :as req}]
  (let [gig-id (http.util/path-param-uuid! req :gig/gig-id)
        gig    (q/retrieve-gig db gig-id)]
    (if gig
      (ui2/plain-page
       [:div {:class "wa-stack wa-gap-2xl gigs-detail-page"}
        (gig-summary req gig)
        (gig-info-section req gig)
        (planned-songs-section req gig)])
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id})))))

(d*/refresh-all!)
