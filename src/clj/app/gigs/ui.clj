(ns app.gigs.ui
  (:require
   [app.urls :as urls]
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

(defn- status-icon [status]
  [:wa-icon {:library "snoico"
             :name    (status-icon-name status)
             :class   (str "gigs-status-icon " (status-class status))}])

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
    (status-icon status)
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
