(ns app.ui2.jump-menu
  (:require
   [app.ui2.button :as button]
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]))

(def ^:private shortcuts
  [{:label "Activity" :icon :comments}
   {:label "Calendar" :icon :calendar}
   {:label "Reports" :icon :chart-bar-square}
   {:label "Everything" :icon :folder-open}])

(def ^:private recent-items
  [{:label "Dashboard" :icon :home :selected? true}
   {:label "Band settings" :icon :cog}
   {:label "Probeplan" :icon :calendar}
   {:label "Members" :icon :users-outline}])

(def ^:private gigs
  [{:title "Sommerfest at Kulturhof"
    :meta "Sat 18 Jul · Kulturhof"
    :icon :trumpet}
   {:title "Streetnoise at Hafenklang"
    :meta "Fri 24 Jul · Hafenklang"
    :icon :trumpet}])

(defn- menu-icon [name]
  [ico/Icon {::ico/library :snoico
             ::ico/name name}])

(defn- shortcut-button [{:keys [icon label]}]
  [:li
   [button/Button {:class "shortcut wa-stack wa-gap-0 wa-align-items-center"
                   :appearance "plain"}
    (menu-icon icon)
    label]])

(defn- shortcuts-menu []
  (into
   [:menu {:class "shortcuts"
           :aria-label "Jump menu shortcuts"}]
   (map shortcut-button)
   shortcuts))

(defn- search-field []
  [:div {:class "search"}
   [:label {:class "wa-visually-hidden"
            :for "jump-menu-search"}
    "Search or jump"]
   [:input {:id "jump-menu-search"
            :type "search"
            :name "jump-menu-search"
            :aria-label "Search or jump"
            :placeholder "Search or jump to anything"
            :autofocus true}]])

(defn- item-icon [icon class]
  [:span {:class (str "item-icon " class " wa-cluster wa-justify-content-center")
          :slot "start"}
   (menu-icon icon)])

(defn- recent-button [{:keys [icon label selected?]}]
  [:li
   [button/Button (cond-> {:class "recent-item"
                           :appearance "plain"}
                    selected? (assoc :appearance "filled"
                                     :aria-current "page"))
    (item-icon icon "recent-icon")
    label]])

(defn- recent-section []
  [:section {:class "section recent wa-stack wa-gap-2xs"}
   [:h2 "Recently visited"]
   (into
    [:menu {:class "recent-list"}]
    (map recent-button)
    recent-items)])

(defn- gig-button [{:keys [icon meta title]}]
  [:li
   [button/Button {:class "gig"
                   :href "#"
                   :appearance "plain"}
    (item-icon icon "gig-icon")
    [:span {:class "copy"}
     [:span {:class "title"} title]
     [:span {:class "meta"} (str " – " meta)]]]])

(defn- gigs-section []
  [:section {:class "section gigs wa-stack wa-gap-2xs"}
   [:header {:class "section-header wa-cluster wa-gap-2xs"}
    [:h2 "Gigs"]
    [:span {:class "separator" :aria-hidden "true"} "–"]
    [button/Button {:class "see-all"
                    :href "/gigs"
                    :appearance "plain"}
     "See all"]]
   (into
    [:menu {:class "gig-list"}]
    (map gig-button)
    gigs)])

(defn JumpMenu [{::keys [logotype]}]
  [:div {:class "jump-menu"}
   [button/Button {:id "jump-menu-trigger"
                   :class "trigger"
                   :appearance "plain"
                   :with-caret true
                   :aria-controls "jump-menu-popover"
                   :aria-expanded "false"
                   :aria-haspopup "dialog"
                   :aria-label "Open jump menu"}
    [:span {:class "brand"} logotype]]
   [:wa-popover {:id "jump-menu-popover"
                 :class "popover"
                 :for "jump-menu-trigger"
                 :placement "bottom"
                 :without-arrow true
                 :data-on:wa-show "document.getElementById('jump-menu-trigger').setAttribute('aria-expanded', 'true')"
                 :data-on:wa-hide "document.getElementById('jump-menu-trigger').setAttribute('aria-expanded', 'false')"}
    [:div {:class "panel"}
     (shortcuts-menu)
     (search-field)
     (recent-section)
     [divider/Divider]
     (gigs-section)]]])
