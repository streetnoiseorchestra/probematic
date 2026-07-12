(ns app.gigs.ui
  (:require
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
   [app.ui2.page-toolbar :as toolbar]
   [app.urls :as urls]
   [clojure.string :as str]))

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
   [ico/Icon (merge attrs
                    {::ico/library :snoico
                     ;; todo plumb in tr for accessability label
                     #_::ico/label #_(tr [status])
                     ::ico/name    (status-icon-name status)
                     :class        (str "gigs-status-icon "
                                        (status-class status)
                                        (when-let [class (:class attrs)]
                                          (str " " class)))})]))

(defn gig-breadcrumb-label [req {:gig/keys [date title gig-type] :as _gig}]
  (if (#{:gig.type/probe :gig.type/extra-probe} gig-type)
    (str title " " (ui2/format-date req :short date))
    title))

(defn breadcrumb-link [href label]
  [breadcrumb/BreadcrumbItem {::breadcrumb/href href}
   label])

(defn breadcrumb-current [label]
  [breadcrumb/BreadcrumbItem label])

(defn breadcrumb-trail [& items]
  (into [breadcrumb/Breadcrumb {}] items))

(defn gig-breadcrumb [req gig]
  (breadcrumb-link (urls/link-gig gig)
                   (gig-breadcrumb-label req gig)))

(defn- mobile-back [href label]
  [button/Button {:appearance "plain"
                  :href       href}
   [ico/Icon {::ico/library :phosphor
              ::ico/name    :arrow-left
              :slot         "start"}]
   label])

(defn page-toolbar
  [{:keys [actions aria-label breadcrumb mobile-href mobile-label
           overflow-items overflow-label]}]
  [toolbar/PageToolbar
   (cond-> {::toolbar/breadcrumb  breadcrumb
            ::toolbar/mobile-back (mobile-back mobile-href mobile-label)
            :aria-label            aria-label}
     (seq actions)
     (assoc ::toolbar/actions actions)

     (seq overflow-items)
     (assoc ::toolbar/overflow-items overflow-items
            ::toolbar/overflow-label overflow-label))])

(defn- present-location [location]
  (some-> location str/trim not-empty))

(defn gig-row [req {:gig/keys [title status location date end-date] :as gig}]
  (let [location (present-location location)]
    [:div {:class "gigs-row"}
     [:div {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-row-title"}
      (gig-status-icon status)
      [:a {:href  (urls/link-gig gig)
           :class "gigs-row-title-text"}
       [:span title]]]
     [:div {:class "gigs-row-meta"}
      (when location
        [:span {:class "wa-cluster wa-gap-3xs wa-align-items-center gigs-row-meta-item gigs-row-location"}
         [ico/Icon {::ico/library :snoico
                    ::ico/name    :location-dot
                    :class        "gigs-row-meta-icon"}]
         [:span location]])
      [:span {:class "wa-cluster wa-gap-3xs wa-align-items-center gigs-row-meta-item gigs-row-date"}
       [ico/Icon {::ico/library :snoico
                  ::ico/name    :calendar
                  :class        "gigs-row-meta-icon"}]
       [:span (ui2/date-range-display req :compact-with-weekday date end-date)]]]
     [:a {:class       "wa-link-plain"
          :href        (urls/link-gig gig)
          :aria-hidden "true"
          :tabindex    "-1"}]]))

(defn section-heading [title]
  (ui2/section-divider title))

(defn gig-section [req {:keys [empty-message footer gigs id title]}]
  [:section {:id    id
             :class "wa-stack wa-gap-xs"}
   (section-heading title)
   [card/Card {:class "gigs-list-card"}
    (if (seq gigs)
      (for [gig gigs]
        (gig-row req gig))
      [:div {:class "gigs-empty"} empty-message])
    footer]])

(defn song-link [{:song/keys [title] :as song}]
  [:a {:href (urls/link-song song)}
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
     (for [{:song/keys [song-id title] :keys [emphasis] :as song} songs]
       (let [intensive? (= emphasis :probeplan.emphasis/intensive)]
         [:li {:id             (str "gig-detail-probeplan-" (ui2/safe-dom-id song-id))
               :data-intensive (if intensive? "true" "false")}
          [:a {:href  (urls/link-song song)
               :class "wa-link-plain gigs-probeplan-link"}
           [:span {:class "gigs-probeplan-song-title"} title]
           (when intensive?
             [ico/Icon {::ico/library :snoico
                        ::ico/name    :fist-punch
                        :class        "gigs-probeplan-intensive-icon"}])]]))]
    [:div {:class "gigs-empty"} "—"]))
