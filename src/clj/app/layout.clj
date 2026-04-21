(ns app.layout
  (:require
   [app.auth :as auth]
   [app.i18n :as i18n]
   [app.icons :as icon]
   [app.render :as render]
   [app.ui :as ui]
   [app.urls :as url]
   [app.config :as config]))

(defn navigation
  [tr]
  [{:label (tr [:nav/home]) :icon icon/home :href "/" :route-name :app/dashboard}
   {:label (tr [:nav/gigs]) :icon icon/trumpet :href (url/link-gigs-home) :route-name :app/gigs}
   ;; {:label (tr [:nav/calendar]) :icon icon/calendar :href (url/link-calendar) :route-name :app/calendar}
   {:label (tr [:nav/songs]) :icon icon/music-note-outline :href "/songs" :route-name :app/songs}
   {:label (tr [:nav/probeplan]) :icon icon/calendar :href "/probeplan" :route-name :app/probeplan}
   {:label (tr [:nav/polls]) :icon icon/question :href "/polls" :route-name :app/polls}
   {:label (tr [:nav/stats]) :icon icon/chart-bar-square :href "/stats" :route-name :app/stats}
   {:label (tr [:nav/forum]) :icon icon/snomegaphone :href "https://forum.streetnoise.at" :route-name :app/forum}
   {:label (tr [:nav/nextcloud]) :icon icon/folder-open :href "https://data.streetnoise.at/apps/files/" :route-name :app/nextcloud}
   {:label (tr [:nav/chat]) :icon icon/comments :href "https://chat.streetnoise.at" :route-name :app/chat}
   {:label (tr [:nav/members]) :icon icon/users-outline :href "/members" :route-name :app/members}
   {:label (tr [:nav/insurance]) :icon icon/shield-check-outline :href "/insurance" :route-name :app/insurance}
   {:label (tr [:nav/band-settings]) :icon icon/cog :href "/band-settings" :route-name :app/band-settings}])

(defn user-menu-sections
  [{:keys [tr]} member]
  [{:items [;; {:label (tr [:nav/profile]) :href "#"}
            ;; {:label (tr [:nav/settings]) :href "#"}
            ;; {:label (tr [:nav/notifications]) :href "#"}
            ]}
   {:items [{:label (tr [:my-profile]) :href (url/link-member member)}]}
   {:items [{:label (tr [:nav/logout]) :href (url/link-logout)}]}])

(defn user-menu-item
  [idx {:keys [label href]}]
  [:a {:href href :class "text-gray-700 hover:bg-gray-100 hover:text-gray-900 block px-4 py-2 text-sm" :role "menuitem" :tabindex "-1" :id (str "user-menu-item-" idx)}
   label])

(defn user-menu-section
  [section]
  [:div {:class "py-1" :role "none"}
   (map-indexed user-menu-item (:items section))])

(defn app-container
  [req member body]
  [:div {:id                            "app-container"
         :data-class:app_container_wide "!$_sidebar.expanded"
         :class                         "flex flex-col lg:pl-64 transition-all"}
   [:div {:class "sticky top-0 z-10 flex h-16 shrink-0 border-b border-gray-200 bg-white lg:hidden"}
    [:button {:type                "button" :class "border-r border-gray-200 px-4 text-gray-500 focus:outline-hidden focus:ring-2 focus:ring-inset focus:ring-purple-500 lg:hidden"
              :data-flyout-trigger "#mobile-flyout-menu"}
     [:span {:class "sr-only"} "Open sidebar"]
     ;; "<!-- Heroicon name: outline/bars-3-center-left -->"
     [:svg {:class "h-6 w-6" :xmlns "http://www.w3.org/2000/svg" :fill "none" :viewbox "0 0 24 24" :stroke-width "1.5" :stroke "currentColor" :aria-hidden "true"}
      [:path {:stroke-linecap "round" :stroke-linejoin "round" :d "M3.75 6.75h16.5M3.75 12H12m-8.25 5.25h16.5"}]]]
    [:div {:class "flex flex-1 justify-between px-4 sm:px-6 lg:px-8"}
     [:div {:class "flex flex-1"}]
     [:div {:class "flex items-center"}
      [:div {:class "relative ml-3"}
       [:div
        [:button {:type                     "button"
                  :data-action-menu-trigger "#user-menu"
                  :class                    "flex max-w-xs items-center rounded-full bg-white text-sm focus:outline-hidden focus:ring-2 focus:ring-purple-500 focus:ring-offset-2" :id "user-menu-button" :aria-expanded "false" :aria-haspopup "true"}
         [:span {:class "sr-only"} "Open user menu"]
         (ui/avatar-img member :class "h-8 w-8 rounded-full")]]
       [:div {:id    "user-menu"                                                                                                                                               :data-action-menu true
              :class "hidden absolute right-0 z-10 mt-2 w-48 origin-top-right divide-y divide-gray-200 rounded-md bg-white shadow-lg ring-1 ring-black/5 focus:outline-hidden" :role             "menu" :aria-orientation "vertical" :aria-labelledby "user-menu-button" :tabindex "-1"}
        (map user-menu-section (user-menu-sections req member))]]]]]
   (if (= :main (first body))
     body
     [:main {:class "flex-1" :id "main"}
      body])])

(defn user-account-actions
  [req member]
  [:div {:class "relative inline-block px-2 text-left"}
   (ui/avatar-img member :class "h-8 w-8 mt-2 rounded-full bg-gray-300 only-collapsed")
   [:div {:class "only-expanded duration-100"}
    [:button {:type                     "button"
              :data-action-menu-trigger "#desktop-user-menu"
              :class                    (ui/cs
                                         "group w-full rounded-md bg-gray-100 px-3.5 py-2 text-left text-sm font-medium text-gray-700 hover:bg-gray-200 focus:outline-hidden focus:ring-2 focus:ring-purple-500 focus:ring-offset-2 focus:ring-offset-gray-100")
              :id                       "options-menu-button" :aria-expanded "false" :aria-haspopup "true"}
     [:span {:class "flex w-full items-center justify-between"}
      [:span {:class "flex min-w-0 items-center justify-between space-x-3"}
       (ui/avatar-img member :class "h-10 w-10 shrink-0 rounded-full bg-gray-300")
       [:span {:class "flex min-w-0 flex-1 flex-col"}
        [:span {:class "truncate text-sm font-medium text-gray-900"}
         (ui/member-nick member)]
        [:span {:class "truncate text-sm text-gray-500"}
         (ui/member-section member)]]]
      (icon/chevron-up-down {:class "h-5 w-5 shrink-0 text-gray-400 group-hover:text-gray-500"})]]]
   [:div {:id    "desktop-user-menu" :data-action-menu true
          :class "hidden absolute right-0 left-0 z-10 mx-3 mt-1 origin-top divide-y divide-gray-200 rounded-md bg-white shadow-lg ring-1 ring-black/5 focus:outline-hidden"
          ;; :class "hidden absolute right-0 left-0 z-10 mx-3 mt-1 origin-top divide-y divide-gray-200 rounded-md bg-white shadow-lg ring-1 ring-black/5 focus:outline-hidden"
          :role  "menu"              :aria-orientation "vertical" :aria-labelledby "options-menu-button" :tabindex "-1"}
    (map user-menu-section (user-menu-sections req member))]])

(defn secondary-navigation
  []
  [:div {:class "mt-8"}
   [:h3 {:class "px-3 text-sm font-medium text-gray-500" :id "mobile-teams-headline"} "Teams"]
   [:div {:class "mt-1 space-y-1" :role "group" :aria-labelledby "mobile-teams-headline"}
    [:a {:href "#" :class "group flex items-center rounded-md px-3 py-2 text-base font-medium leading-5 text-gray-600 hover:bg-gray-50 hover:text-gray-900"}
     [:span {:class "w-2.5 h-2.5 mr-4 bg-sno-orange-500 rounded-full" :aria-hidden "true"}]
     [:span {:class "truncate"} "Engineering"]]
    [:a {:href "#" :class "group flex items-center rounded-md px-3 py-2 text-base font-medium leading-5 text-gray-600 hover:bg-gray-50 hover:text-gray-900"}
     [:span {:class "w-2.5 h-2.5 mr-4 bg-green-500 rounded-full" :aria-hidden "true"}]
     [:span {:class "truncate"} "Human Resources"]]
    [:a {:href "#" :class "group flex items-center rounded-md px-3 py-2 text-base font-medium leading-5 text-gray-600 hover:bg-gray-50 hover:text-gray-900"}
     [:span {:class "w-2.5 h-2.5 mr-4 bg-yellow-500 rounded-full" :aria-hidden "true"}]
     [:span {:class "truncate"} "Customer Success"]]]])

(defn nav-item
  [req {:keys [label icon href route-name]}]
  (let [active? (= route-name (-> req :reitit.core/match :data  :app.route/name))]
    [:a {:href href
         :class
         (ui/cs "flex items-center px-2 py-2 text-sm"
                #_"-center px-2 py-2 text-sm font-medium rounded-md group cursor-pointer"
                (if active?
                  "bg-gray-200 text-gray-900"
                  "text-gray-700 hover:text-gray-900 hover:bg-gray-50"))}

     (icon {:class
            (ui/cs "mr-3 shrink-0 h-6 w-6"
                   (if active?
                     "text-gray-500"
                     "text-gray-400 group-hover:text-gray-500"))})
     [:span {:class "only-expanded"} label]]))

(defn desktop-menu
  [req member]
  [:div {:data-signals:_sidebar.expanded "true"}
   [:div {:id                   "desktop-sidebar-menu"
          :data-class:collapsed "!$_sidebar.expanded"
          :class
          (ui/cs
           "hidden lg:fixed lg:inset-y-0 lg:flex lg:w-64 lg:flex-col lg:border-r lg:border-gray-200 lg:bg-gray-100 lg:pt-5 lg:pb-4"
           "lg:translate-x-0"
           "no-scrollbar"
           "shrink-0 border-r border-gray-200 sm:translate-x-0 transition-all duration-200")}

    [:div {:class "flex shrink-0 items-center px-6 sidebar-logo-container"}
     [:a {:href "/" :class ""}
      (icon/snoman {:data-class "{'lg:hidden': $_sidebar.expanded, 'lg:cloak': false}"
                    :class      "h-8 w-auto text-green-500 logotype-dark lg:cloak"})
      (icon/logotype {:data-class "{'lg:hidden': !$_sidebar.expanded}"
                      :class      "h-8 w-auto text-green-500 logotype-dark"})]]

    [:div {:class "mt-5 flex h-0 flex-1 flex-col overflow-y-auto overflow-x-hidden pt-1 sidebar-scroll-container"}
     (user-account-actions req member)
     [:nav
      [:div {:class "space-y-1"}
       (map (partial nav-item req) (navigation (i18n/tr-from-req req)))]
      ;; (secondary-navigation)
      ]
     [:div {:class "flex items-end justify-end"}
      [:button {:class         "px-3 py-2 text-gray-700 hover:text-gray-900 hover:bg-gray-50 rounded-md sidebar-open-close-button rotate-0 "
                :data-on:click "$_sidebar.expanded = !$_sidebar.expanded"}
       (icon/arrow-small-left {:class "w-6 h-6 hidden lg:block"})]]]]])

(defn mobile-menu
  [req]
  [:div {:id "mobile-flyout-menu" :class "hidden relative z-40 lg:hidden" :role "dialog" :aria-modal "true"}
   [:div {:class "fixed inset-0 bg-gray-600 bg-opacity-75" :data-flyout-backdrop true}]
   [:div {:class "fixed inset-0 z-40 flex"}
    [:div {:data-flyout-menu true :class "relative flex w-full max-w-xs flex-1 flex-col bg-white pt-5 pb-4"}
     [:div {:data-flyout-close-button true :class "absolute top-0 right-0 -mr-12 pt-2"}
      [:button {:type  "button"
                :class "ml-1 flex h-10 w-10 items-center justify-center rounded-full focus:outline-hidden focus:ring-2 focus:ring-inset focus:ring-white"}
       [:span {:class "sr-only"} "Close sidebar"]
       (icon/xmark {:class "h-6 w-6 text-white"})]]
     [:div {:class "flex shrink-0 items-center px-4"}
      [:a {:href "/"}
       (icon/logotype {:class "h-8 w-auto text-green-500 logotype-dark"})]]
     [:div {:class "mt-5 h-0 flex-1 overflow-y-auto"}
      [:nav {:class "px-2"}
       [:div {:class "space-y-1"}
        (map (partial nav-item req) (navigation (i18n/tr-from-req req)))]
       ;; (secondary-navigation)
       ]]]
    ;; Dummy element to force sidebar to shrink to fit close icon
    [:div {:class "w-14 shrink-0" :aria-hidden "true"}]]])

(defn from-tw-shell
  []
  [:div
   ;; "<!-- Pinned projects -->"
   [:div {:class "mt-6 px-4 sm:px-6 lg:px-8"}
    [:h2 {:class "text-sm font-medium text-gray-900"} "Pinned Projects"]
    [:ul {:role "list" :class "mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2 sm:gap-6 xl:grid-cols-4"}
     [:li {:class "relative col-span-1 flex rounded-md shadow-xs"}
      [:div {:class "shrink-0 flex items-center justify-center w-16 bg-pink-600 text-white text-sm font-medium rounded-l-md"} "GA"]
      [:div {:class "flex flex-1 items-center justify-between truncate rounded-r-md border-t border-r border-b border-gray-200 bg-white"}
       [:div {:class "flex-1 truncate px-4 py-2 text-sm"}
        [:a {:href "#" :class "font-medium text-gray-900 hover:text-gray-600"} "GraphQL API"]
        [:p {:class "text-gray-500"} "12 Members"]]
       [:div {:class "shrink-0 pr-2"}
        [:button {:type                     "button"
                  :data-action-menu-trigger "#test-thing"
                  :class                    "inline-flex h-8 w-8 items-center justify-center rounded-full bg-white text-gray-400 hover:text-gray-500 focus:outline-hidden focus:ring-2 focus:ring-purple-500 focus:ring-offset-2" :id "pinned-project-options-menu-0-button" :aria-expanded "false" :aria-haspopup "true"}
         [:span {:class "sr-only"} "Open options"]
         ;; "<!-- Heroicon name: mini/ellipsis-vertical -->"
         [:svg {:class "h-5 w-5" :xmlns "http://www.w3.org/2000/svg" :viewbox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
          [:path {:d "M10 3a1.5 1.5 0 110 3 1.5 1.5 0 010-3zM10 8.5a1.5 1.5 0 110 3 1.5 1.5 0 010-3zM11.5 15.5a1.5 1.5 0 10-3 0 1.5 1.5 0 003 0z"}]]]
        ;; "<!--\n                  Dropdown menu, show/hide based on menu state.\n\n                  Entering: \"transition ease-out duration-100\"From: \"transform opacity-0 scale-95\"To: \"transform opacity-100 scale-100\"Leaving: \"transition ease-in duration-75\"From: \"transform opacity-100 scale-100\"To: \"transform opacity-0 scale-95\"-->"
        [:div {:id               "test-thing"
               :data-action-menu true
               :class            "hidden absolute right-10 top-3 z-10 mx-3 mt-1 w-48 origin-top-right divide-y divide-gray-200 rounded-md bg-white shadow-lg ring-1 ring-black/5 focus:outline-hidden" :role "menu" :aria-orientation "vertical" :aria-labelledby "pinned-project-options-menu-0-button" :tabindex "-1"}
         [:div {:class "py-1" :role "none"}
          ;; "<!-- Active: \"bg-gray-100 text-gray-900\" Not Active: \"text-gray-700\" -->"
          [:a {:href "#" :class "text-gray-700 block px-4 py-2 text-sm" :role "menuitem" :tabindex "-1" :id "pinned-project-options-menu-0-item-0"} "View"]]
         [:div {:class "py-1" :role "none"}
          [:a {:href "#" :class "text-gray-700 block px-4 py-2 text-sm" :role "menuitem" :tabindex "-1" :id "pinned-project-options-menu-0-item-1"} "Removed from pinned"]
          [:a {:href "#" :class "text-gray-700 block px-4 py-2 text-sm" :role "menuitem" :tabindex "-1" :id "pinned-project-options-menu-0-item-2"} "Share"]]]]]]
     ;; "<!-- More items... -->"
     ]]
   ;; "<!-- Projects list (only on smallest breakpoint) -->"
   [:div {:class "mt-10 sm:hidden"}
    [:div {:class "px-4 sm:px-6"}
     [:h2 {:class "text-sm font-medium text-gray-900"} "Projects"]]
    [:ul {:role "list" :class "mt-3 divide-y divide-gray-100 border-t border-gray-200"}
     [:li
      [:a {:href "#" :class "group flex items-center justify-between px-4 py-4 hover:bg-gray-50 sm:px-6"}
       [:span {:class "flex items-center space-x-3 truncate"}
        [:span {:class "w-2.5 h-2.5 shrink-0 rounded-full bg-pink-600" :aria-hidden "true"}]
        [:span {:class "truncate text-sm font-medium leading-6"} "GraphQL API"
         [:span {:class "truncate font-normal text-gray-500"} "in Engineering"]]]
       ;; "<!-- Heroicon name: mini/chevron-right -->"
       [:svg {:class "ml-4 h-5 w-5 text-gray-400 group-hover:text-gray-500" :xmlns "http://www.w3.org/2000/svg" :viewbox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
        [:path {:fill-rule "evenodd" :d "M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.29a.75.75 0 111.04-1.08l4.5 4.25a.75.75 0 010 1.08l-4.5 4.25a.75.75 0 01-1.06-.02z" :clip-rule "evenodd"}]]]]
     ;; "<!-- More projects... -->"
     ]]
   ;; "<!-- Projects table (small breakpoint and up) -->"
   [:div {:class "mt-8 hidden sm:block"}
    [:div {:class "inline-block min-w-full border-b border-gray-200 align-middle"}
     [:table {:class "min-w-full"}
      [:thead
       [:tr {:class "border-t border-gray-200"}
        [:th {:class "border-b border-gray-200 bg-gray-50 px-6 py-3 text-left text-sm font-semibold text-gray-900" :scope "col"}
         [:span {:class "lg:pl-2"} "Project"]]
        [:th {:class "border-b border-gray-200 bg-gray-50 px-6 py-3 text-left text-sm font-semibold text-gray-900" :scope "col"} "Members"]
        [:th {:class "hidden border-b border-gray-200 bg-gray-50 px-6 py-3 text-right text-sm font-semibold text-gray-900 md:table-cell" :scope "col"} "Last updated"]
        [:th {:class "border-b border-gray-200 bg-gray-50 py-3 pr-6 text-right text-sm font-semibold text-gray-900" :scope "col"}]]]
      [:tbody {:class "divide-y divide-gray-100 bg-white"}
       [:tr
        [:td {:class "w-full max-w-0 whitespace-nowrap px-6 py-3 text-sm font-medium text-gray-900"}
         [:div {:class "flex items-center space-x-3 lg:pl-2"}
          [:div {:class "shrink-0 w-2.5 h-2.5 rounded-full bg-pink-600" :aria-hidden "true"}]
          [:a {:href "#" :class "truncate hover:text-gray-600"}
           [:span "GraphQL API"
            [:span {:class "font-normal text-gray-500"} "in Engineering"]]]]]
        [:td {:class "px-6 py-3 text-sm font-medium text-gray-500"}
         [:div {:class "flex items-center space-x-2"}
          [:div {:class "flex shrink-0 -space-x-1"}
           [:img {:class "h-6 w-6 max-w-none rounded-full ring-2 ring-white" :src "https://images.unsplash.com/photo-1506794778202-cad84cf45f1d?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=facearea&facepad=2&w=256&h=256&q=80"}]
           [:img {:class "h-6 w-6 max-w-none rounded-full ring-2 ring-white" :src "https://images.unsplash.com/photo-1517841905240-472988babdf9?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=facearea&facepad=2&w=256&h=256&q=80"}]
           [:img {:class "h-6 w-6 max-w-none rounded-full ring-2 ring-white" :src "https://images.unsplash.com/photo-1438761681033-6461ffad8d80?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=facearea&facepad=2&w=256&h=256&q=80"}]
           [:img {:class "h-6 w-6 max-w-none rounded-full ring-2 ring-white" :src "https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&auto=format&fit=facearea&facepad=2&w=256&h=256&q=80"}]]
          [:span {:class "shrink-0 text-xs font-medium leading-5"} "+8"]]]
        [:td {:class "hidden whitespace-nowrap px-6 py-3 text-right text-sm text-gray-500 md:table-cell"} "March 17, 2020"]
        [:td {:class "whitespace-nowrap px-6 py-3 text-right text-sm font-medium"}
         [:a {:href "#" :class "text-sno-orange-600 hover:text-sno-orange-900"} "Edit"]]]
       ;; "<!-- More projects... -->"
       ]]]]])

(defn app-shell
  ([req body]
   (app-shell req body nil))
  ([req body opts]
   (let [member (auth/get-current-member req)]
     (render/html5-response req (merge {:title "SNOrga"} opts)
                            [:div
                             [:div {:data-init "@post(window.location.pathname + window.location.search)" :id "long-lived-sse"}]
                             [:div {:class "min-h-full"}
                              (mobile-menu req)
                              (desktop-menu req member)
                              (app-container req member body)]]))))

(defn maybe-app-shell
  "Will wrap the response in the app shell if it is not an HTMX request"
  [req body]
  (if (:htmx? req)
    body
    (app-shell req body)))

(defn centered-content
  [req body]
  (let [uri-prefix (config/app-base-url (-> req :system :env))]
    (render/html5-response-absolute req {:title "SNOrga"
                                         :uri-prefix uri-prefix}
                                    [:div {:class "h-full bg-gray-50"}
                                     [:div {:class "flex min-h-full flex-col justify-center py-12 sm:px-6 lg:px-8"}
                                      [:div {:class "sm:mx-auto sm:w-full sm:max-w-md"}
                                       (icon/logotype {:class "h-8 mx-auto h-12 w-auto text-green-500 logotype-dark"})]
                                      [:div {:class "mt-8 sm:mx-auto sm:w-full sm:max-w-md"}
                                       [:div {:class "bg-white py-8 px-4 shadow-sm sm:rounded-lg sm:px-10"}
                                        body]]]])))
(defn centered-content-lg
  [req body]
  (let [uri-prefix (config/app-base-url (-> req :system :env))]
    (render/html5-response-absolute req {:title "SNOrga"
                                         :uri-prefix uri-prefix}
                                    [:div {:class "h-full bg-gray-50"}
                                     [:div {:class "flex min-h-full flex-col justify-center py-12 sm:px-6 lg:px-8"}
                                      [:div {:class "sm:mx-auto sm:w-full sm:max-w-5xl"}
                                       (icon/logotype {:class "h-8 mx-auto h-12 w-auto text-green-500 logotype-dark"})]
                                      [:div {:class "mt-8 sm:mx-auto sm:w-full sm:max-w-5xl"}
                                       [:div {:class "bg-white py-8 px-4 shadow-sm sm:rounded-lg sm:px-10"}
                                        body]]]])))

(defn sidebar-search
  []
  ;; "<!-- Sidebar Search -->"
  [:div {:class "mt-5 px-3"}
   [:label {:for "search" :class "sr-only"} "Search"]
   [:div {:class "relative mt-1 rounded-md shadow-xs"}
    [:div {:class "pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3" :aria-hidden "true"}
     ;; "<!-- Heroicon name: mini/magnifying-glass -->"
     [:svg {:class "mr-3 h-4 w-4 text-gray-400" :xmlns "http://www.w3.org/2000/svg" :viewbox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
      [:path {:fill-rule "evenodd" :d "M9 3.5a5.5 5.5 0 100 11 5.5 5.5 0 000-11zM2 9a7 7 0 1112.452 4.391l3.328 3.329a.75.75 0 11-1.06 1.06l-3.329-3.328A7 7 0 012 9z" :clip-rule "evenodd"}]]]
    [:input {:type "text" :name "search" :id "search" :class "block w-full rounded-md border-gray-300 pl-9 focus:border-sno-orange-500 focus:ring-sno-orange-500 sm:text-sm" :placeholder "Search"}]]])
