(ns app.members.index.views
  (:require
   [app.datastar :as d*]
   [app.members.domain :as members.domain]
   [app.members.index.actions :as actions]
   [app.members.queries :as queries]
   [app.members.ui :as members.ui]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn- member-name [{:member/keys [name nick]}]
  (if (str/blank? nick)
    name
    (str name " (" nick ")")))

(defn- member-section-name [{:member/keys [section]}]
  (or (:section/name section) "—"))

(defn- travel-discount-tags [member id-suffix]
  (let [discounts (:member/travel-discounts member)]
    (if (seq discounts)
      [:span {:class "members-index-discounts"}
       (for [discount discounts]
         (members.ui/travel-discount-badge discount (members.ui/travel-discount-name discount) id-suffix))]
      [:span {:class "members-index-muted"} "—"])))

(defn- sort-indicator [{:keys [sort-field sort-order]} field]
  (when (= sort-field field)
    (if (= sort-order "desc")
      " ↓"
      " ↑")))

(defn- sort-button [req page-state field label]
  [:a {:href          "#"
       :class         "wa-link-plain wa-font-weight-bold"
       :data-on:click (->expr
                       (evt.preventDefault)
                       (set! $members-index.sort-request-field ~field)
                       (@post ~(d*/act req ::actions/set-sort)))}
   label
   [:span {:aria-hidden true} (or (sort-indicator page-state field) "")]])

(defn- search-control [req {:keys [search]}]
  [:wa-input {:label                        [:i18n/tr :action/search]
              :placeholder                  [:i18n/tr :action/search]
              :appearance                   "outlined"
              :size                         "m"
              :value                        search
              :with-clear                   true
              :data-bind                    "members-index.search"
              :data-on:input__debounce.250ms
              (str "@post('" (d*/act req ::actions/set-search-phrase) "')")}])

(defn- filter-control [req {:keys [filter-preset]}]
  [:wa-select {:label          [:i18n/tr :action/filter]
               :appearance     "outlined"
               :size           "m"
               :value          filter-preset
               :data-bind      "members-index.filter-preset"
               :data-on:change (str "@post('" (d*/act req ::actions/set-filter-preset) "')")}
   [:wa-option {:value "active"} [:i18n/tr :members/filter-active]]
   [:wa-option {:value "inactive"} [:i18n/tr :members/filter-inactive]]
   [:wa-option {:value "all"} [:i18n/tr :members/filter-all]]])

(defn- invite-loading? [invite-code action]
  (format "$invite.inflight && $invite.code === %s && $invite.action === %s"
          (pr-str invite-code)
          (pr-str action)))

(defn- invite-action-button [req {:keys [invite-code action label variant action-key]}]
  [button/Button {:appearance         "outlined"
                  :variant            variant
                  :size               "s"
                  :data-attr:loading  (invite-loading? invite-code action)
                  :data-attr:disabled "$invite.inflight"
                  :data-on:click      (->expr
                                       (set! $invite.code ~invite-code)
                                       (set! $invite.action ~action)
                                       (set! $invite.inflight true)
                                       (@post ~(d*/act req action-key)))}
   label])

(defn- open-invitations-panel [req open-invitations]
  (when (seq open-invitations)
    [:section {:class "wa-stack wa-gap-s"}
     (ui2/title-block {:level    2
                       :title    [:i18n/tr :members/open-invitations]
                       :subtitle [:i18n/tr :members/open-invitations-subtitle]})
     [:div {:class "table-shell"}
      [:table {:class "members-index-table"}
       [:thead
        [:tr
         [:th [:i18n/tr (members.domain/member-attribute-label-key :member/name)]]
         [:th [:i18n/tr (members.domain/member-attribute-label-key :member/email)]]
         [:th {:class "members-index-actions-header"}]]]
       [:tbody
        (for [{:member/keys [name email invite-code]} open-invitations]
          [:tr
           [:td name]
           [:td email]
           [:td {:class "members-index-row-actions"}
            [:div {:class "wa-cluster wa-gap-2xs wa-justify-content-end"}
             (invite-action-button req {:invite-code invite-code
                                        :action      "resend"
                                        :label       [:i18n/tr :action/resend-invite]
                                        :variant     "brand"
                                        :action-key  ::actions/resend-invitation})
             (invite-action-button req {:invite-code invite-code
                                        :action      "delete"
                                        :label       [:i18n/tr :action/delete]
                                        :variant     "danger"
                                        :action-key  ::actions/delete-invitation})]]])]]]]))

(defn- member-row [member]
  (let [{:member/keys [email phone active?]} member
        section-name       (member-section-name member)
        mobile-discounts   (travel-discount-tags member "mobile")
        desktop-discounts  (travel-discount-tags member "desktop")]
    [:tr
     [:td
      [:div {:class "wa-stack wa-gap-3xs"}
       [:a {:href (urls/link-member member)}
        (member-name member)]
       [:div {:class "members-index-row-meta"}
        [:span {:class "members-index-row-meta__discounts"} mobile-discounts]
        [:span {:class "members-index-row-meta__email"} email]
        [:span {:class "members-index-row-meta__section"} section-name]
        (when (seq phone)
          [:span {:class "members-index-row-meta__phone"} phone])
        [:span {:class "members-index-row-meta__status"}
         (ui2/active-badge active?)]]]]
     [:td {:class "members-index-col members-index-col--discount"} desktop-discounts]
     [:td {:class "members-index-col members-index-col--md"} email]
     [:td {:class "members-index-col members-index-col--lg"} (or phone "—")]
     [:td {:class "members-index-col members-index-col--sm"} section-name]
     [:td {:class "members-index-col members-index-col--sm"}
      (ui2/active-badge active?)]]))

(defn- members-table [req page-state members]
  (if (seq members)
    [:div {:class "table-shell"}
     [:table {:class "members-index-table"}
      [:thead
       [:tr
        [:th (sort-button req page-state "name" [:i18n/tr (members.domain/member-attribute-label-key :member/name)])]
        [:th {:class "members-index-col members-index-col--discount"}
         (sort-button req page-state "travel-discount" [:i18n/tr :members/oebb-discount])]
        [:th {:class "members-index-col members-index-col--md"}
         (sort-button req page-state "email" [:i18n/tr (members.domain/member-attribute-label-key :member/email)])]
        [:th {:class "members-index-col members-index-col--lg"}
         (sort-button req page-state "phone" [:i18n/tr (members.domain/member-attribute-label-key :member/phone)])]
        [:th {:class "members-index-col members-index-col--sm"}
         (sort-button req page-state "section" [:i18n/tr (members.domain/member-attribute-label-key :member/section)])]
        [:th {:class "members-index-col members-index-col--sm"}
         (sort-button req page-state "active" [:i18n/tr :status-active])]]]
      [:tbody
       (for [member members]
         (member-row member))]]]
    (ui2/empty-state
     [:i18n/tr :members/browse-empty]
     [:i18n/tr :members/browse-empty-subtitle])))

(defn page [{:keys [db page-state] :as req}]
  (let [page-state       (queries/normalize-page-state (:members-index page-state))
        members          (queries/members db page-state)
        open-invitations (queries/members-with-open-invites req)]
    (ui2/datastar-page*
     [page-surface/PageSurface {::page-surface/toolbar
                                [page-toolbar/PageToolbar {::page-toolbar/breadcrumb
                                                           [breadcrumb/Breadcrumb {}
                                                            [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-dashboard)}
                                                             [:i18n/tr :home]]
                                                            [breadcrumb/BreadcrumbItem [:i18n/tr :members/title]]]
                                                           ::page-toolbar/actions
                                                           [[button/Button {:appearance "filled"
                                                                            :variant    "brand"
                                                                            :href       "/members/invite"}
                                                             [:i18n/tr :members/invite-member]]]
                                                           :aria-label [:i18n/tr :members/directory-toolbar-label]}]}
      [:div {:class        "wa-stack wa-gap-l"
             :data-signals (d*/->signals {:members-index page-state
                                          :invite        {:action nil
                                                          :code nil
                                                          :inflight false}})}
       [page-header/PageHeader
        {::page-header/title    [:i18n/tr :members/title]
         ::page-header/subtitle [:i18n/tr :members/member-count {:count (count members)}]}]
       [:div {:class "wa-grid wa-gap-s"
              :style "--min-column-size: min(100%, 16rem);"}
        (search-control req page-state)
        (filter-control req page-state)]
       (open-invitations-panel req open-invitations)
       (members-table req page-state members)]])))

(d*/refresh-all!)
