(ns app.gigs.index.views
  (:require
   [app.datastar :as d*]
   [app.gigs.index.queries :as queries]
   [app.ui2 :as ui2]
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
    :gig.status/confirmed "gigs-index-status-icon--confirmed"
    :gig.status/unconfirmed "gigs-index-status-icon--unconfirmed"
    :gig.status/cancelled "gigs-index-status-icon--cancelled"
    "gigs-index-status-icon--unknown"))

(defn- status-icon [status]
  [:wa-icon {:library "snoico"
             :name    (status-icon-name status)
             :class   (str "gigs-index-status-icon " (status-class status))}])

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

(defn- gig-row [{:gig/keys [title status location date end-date] :as gig}]
  [:a {:href  (urls/link-gig gig)
       :class "gigs-index-row"}
   [:div {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-index-row-title"}
    (status-icon status)
    [:span {:class "gigs-index-row-title-text"} title]]
   [:div {:class "gigs-index-row-meta"}
    [:span {:class "wa-cluster wa-gap-3xs wa-align-items-center gigs-index-row-meta-item gigs-index-row-location"}
     [:wa-icon {:library "snoico"
                :name    "location-dot"
                :class   "gigs-index-row-meta-icon"}]
     [:span (gig-location location)]]
    [:span {:class "wa-cluster wa-gap-3xs wa-align-items-center gigs-index-row-meta-item gigs-index-row-date"}
     [:wa-icon {:library "snoico"
                :name    "calendar"
                :class   "gigs-index-row-meta-icon"}]
     [:span (gig-date-range date end-date)]]]])

(defn- section-heading [title]
  [:div {:class "gigs-index-section-heading"}
   [:div {:class "wa-flank:end"}
    [:h2 title]]
   [:wa-divider {:class "gigs-index-section-divider"}]])

(defn- gig-section [{:keys [empty-message gigs footer title]}]
  [:section {:class "wa-stack wa-gap-xs"}
   (section-heading title)
   [:wa-card {:class "gigs-index-list-card"}
    (if (seq gigs)
      (for [gig gigs]
        (gig-row gig))
      [:div {:class "gigs-index-empty"} empty-message])
    footer]])

(defn page [{:keys [db tr]}]
  (let [{:keys [future-gigs past-gigs]} (queries/page-data db)]
    (ui2/plain-page
     [:div {:class "wa-stack wa-gap-l gigs-index-page"}
      [:div {:class "wa-flank:end wa-align-items-end wa-gap-s gigs-index-toolbar"}
       [:div {:class "wa-stack wa-gap-2xs"}
        [:h1 (tr [:gigs/title])]]
       [:wa-button {:appearance "filled"
                    :variant    "brand"
                    :href       (urls/link-gig-create)}
        (tr [:action/create])]]
      [:div {:class "wa-grid wa-gap-m gigs-index-columns"}
       (gig-section {:title         (tr [:gigs/upcoming])
                     :empty-message (tr [:gigs/no-future])
                     :gigs          future-gigs})
       (gig-section {:title         (tr [:gigs/past])
                     :empty-message (tr [:gigs/no-past])
                     :gigs          past-gigs
                     :footer        [:div {:class "gigs-index-list-footer"}
                                     [:wa-button {:appearance "plain"
                                                  :href       (urls/link-gig-archive)}
                                      (tr [:gigs/view-archive])]]})]])))

(d*/refresh-all!)
