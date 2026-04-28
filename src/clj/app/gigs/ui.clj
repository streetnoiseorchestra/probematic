(ns app.gigs.ui
  (:require
   [app.ui :as ui]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [clojure.string :as str]
   [tick.core :as t])
  (:import
   (java.util Locale)))

(defn- status-icon-name [status]
  (case status
    :gig.status/confirmed "circle-check"
    :gig.status/unconfirmed "circle-question"
    :gig.status/cancelled "circle-xmark"
    "circle-question"))

(defn- status-class [status]
  (case status
    :gig.status/confirmed "gigs-status-icon--confirmed"
    :gig.status/unconfirmed "gigs-status-icon--unconfirmed"
    :gig.status/cancelled "gigs-status-icon--cancelled"
    "gigs-status-icon--unknown"))

(defn gig-status-icon
  ([status]
   (gig-status-icon status nil))
  ([status attrs]
   [:wa-icon (merge attrs
                    {:library "snoico"
                     ;; todo plumb in tr for accessability label
                     #_:label    #_(tr [status])
                     :name    (status-icon-name status)
                     :class   (str "gigs-status-icon "
                                   (status-class status)
                                   (when-let [class (:class attrs)]
                                     (str " " class)))})]))

(defn gig-breadcrumb-label [{:gig/keys [title gig-type] :as gig}]
  (if (#{:gig.type/probe :gig.type/extra-probe} gig-type)
    (str title " " (ui/gig-date gig))
    title))

(defn- date-value [dt]
  (when dt
    (t/date (if (inst? dt)
              (t/date-time dt)
              dt))))

(defn- formatted-date [dt]
  (when-let [date (date-value dt)]
    (t/format (t/formatter "E dd MMM yyyy" Locale/GERMAN) date)))

(defn- gig-date [dt]
  (if dt
    [:time {:datetime (str dt)} (formatted-date dt)]
    "—"))

(defn- gig-date-range [start end]
  (if end
    [:span
     (gig-date start)
     [:span {:aria-hidden true} " – "]
     (gig-date end)]
    (gig-date start)))

(defn- gig-location [location]
  (if (seq location)
    location
    "—"))

(defn gig-row [{:gig/keys [title status location date end-date] :as gig}]
  [:a {:href  (urls/link-gig gig)
       :class "gigs-row"}
   [:div {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-row-title"}
    (gig-status-icon status)
    [:span {:class "gigs-row-title-text"} title]]
   [:div {:class "gigs-row-meta"}
    [:span {:class "wa-cluster wa-gap-3xs wa-align-items-center gigs-row-meta-item gigs-row-location"}
     [:wa-icon {:library "snoico"
                :name    "location-dot"
                :class   "gigs-row-meta-icon"}]
     [:span (gig-location location)]]
    [:span {:class "wa-cluster wa-gap-3xs wa-align-items-center gigs-row-meta-item gigs-row-date"}
     [:wa-icon {:library "snoico"
                :name    "calendar"
                :class   "gigs-row-meta-icon"}]
     [:span (gig-date-range date end-date)]]]])

(defn section-heading [title]
  [:div {:class "gigs-section-heading"}
   [:div {:class "wa-flank:end"}
    [:h2 title]]
   [:wa-divider {:class "gigs-section-divider"}]])

(defn gig-section [{:keys [empty-message footer gigs id title]}]
  [:section {:id    id
             :class "wa-stack wa-gap-xs"}
   (section-heading title)
   [:wa-card {:class "gigs-list-card"}
    (if (seq gigs)
      (for [gig gigs]
        (gig-row gig))
      [:div {:class "gigs-empty"} empty-message])
    footer]])

(defn song-link [{:song/keys [title] :as song}]
  [:a {:href  (urls/link-song song)
       :class "gigs-song-link"}
   title])

(defn setlist-list [songs]
  (if (seq songs)
    [:ol {:class "gigs-setlist-list"}
     (for [{:song/keys [solo-info] :as song} songs]
       [:li
        (song-link song)
        (when-not (str/blank? solo-info)
          [:span {:class "gigs-song-note"} (str " (" solo-info ")")])])]
    [:div {:class "gigs-empty"} "—"]))

(defn probeplan-list [_tr songs]
  (if (seq songs)
    [:ol {:class "gigs-probeplan-list"}
     (for [{:song/keys [song-id title] :keys [emphasis]} songs]
       (let [intensive? (= emphasis :probeplan.emphasis/intensive)]
         [:li {:id             (str "gig-detail-probeplan-" (ui2/safe-dom-id song-id))
               :data-intensive (if intensive? "true" "false")}
          [:span {:class "gigs-probeplan-song-title"} title]
          (when intensive?
            [:wa-icon {:library "snoico"
                       :name    "fist-punch"
                       :class   "gigs-probeplan-intensive-icon"}])]))]
    [:div {:class "gigs-empty"} "—"]))
