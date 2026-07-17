(ns app.members.index.views
  (:require
   [app.datastar :as d*]
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

(defn- status-badge [{:keys [tr]} active?]
  [:wa-badge (cond-> {:appearance "outlined"
                      :pill       true}
               active?       (assoc :variant "success")
               (not active?) (assoc :variant "neutral"))
   (if active?
     (tr [:Active])
     (tr [:Inactive]))])

(defn- travel-discount-tags [{:keys [tr]} member id-suffix]
  (let [discounts (:member/travel-discounts member)]
    (if (seq discounts)
      [:span {:class "members-index-discounts"}
       (for [discount discounts]
         (members.ui/travel-discount-badge tr discount (members.ui/travel-discount-name discount) id-suffix))]
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

(defn- search-control [{:keys [tr] :as req} {:keys [search]}]
  [:wa-input {:label                        (tr [:action/search])
              :placeholder                  (tr [:action/search])
              :appearance                   "outlined"
              :size                         "m"
              :value                        search
              :with-clear                   true
              :data-bind                    "members-index.search"
              :data-on:input__debounce.250ms
              (str "@post('" (d*/act req ::actions/set-search-phrase) "')")}])

(defn- filter-control [{:keys [tr] :as req} {:keys [filter-preset]}]
  [:wa-select {:label          (tr [:action/filter])
               :appearance     "outlined"
               :size           "m"
               :value          filter-preset
               :data-bind      "members-index.filter-preset"
               :data-on:change (str "@post('" (d*/act req ::actions/set-filter-preset) "')")}
   [:wa-option {:value "active"} (tr [:member/filter-active])]
   [:wa-option {:value "inactive"} (tr [:member/filter-inactive])]
   [:wa-option {:value "all"} (tr [:member/filter-all])]])

(defn- invite-loading?
  [{:keys [invite-code member-id generation]} action]
  (if invite-code
    (format "$invite.inflight && $invite.code === %s && $invite.action === %s"
            (pr-str invite-code)
            (pr-str action))
    (format (str "$invite.inflight && $invite.member-id === %s "
                 "&& $invite.generation === %s && $invite.action === %s")
            (pr-str (str member-id))
            generation
            (pr-str action))))

(defn- invite-action-button
  [req {:keys [invite-code member-id generation action label variant action-key]
        :as action-options}]
  [button/Button {:appearance         "outlined"
                  :variant            variant
                  :size               "s"
                  :data-attr:loading  (invite-loading? action-options action)
                  :data-attr:disabled "$invite.inflight"
                  :data-on:click      (if invite-code
                                        (->expr
                                         (set! $invite.code ~invite-code)
                                         (set! $invite.action ~action)
                                         (set! $invite.inflight true)
                                         (@post ~(d*/act req action-key)))
                                        (->expr
                                         (set! $invite.member-id ~(str member-id))
                                         (set! $invite.generation ~generation)
                                         (set! $invite.action ~action)
                                         (set! $invite.inflight true)
                                         (@post ~(d*/act req action-key))))}
   label])

(defn- invitations-panel [{:keys [tr] :as req} invitations]
  (when (seq invitations)
    [:section {:class "wa-stack wa-gap-s"}
     (ui2/title-block {:level    2
                       :title    [:i18n/tr :members/invitations-title]
                       :subtitle [:i18n/tr :members/invitations-subtitle]})
     [:div {:class "table-shell"}
      [:table {:class "members-index-table"}
       [:thead
        [:tr
         [:th (tr [:member/name])]
         [:th (tr [:Email])]
         [:th {:class "members-index-actions-header"}]]]
       [:tbody
        (for [{:member/keys [member-id name email invite-code
                             invite-status invite-generation]
               :keys        [invite-expired?]} invitations]
          (let [revoked? (= :member.invite.status/revoked invite-status)]
            [:tr
             [:td name]
             [:td email]
             [:td {:class "members-index-row-actions"}
              [:div {:class "wa-cluster wa-gap-2xs wa-justify-content-end"}
               (cond
                 revoked?
                 (invite-action-button
                  req
                  {:member-id  member-id
                   :generation invite-generation
                   :action     "reissue-revoked"
                   :label      [:i18n/tr :action/reissue-invitation]
                   :variant    "brand"
                   :action-key ::actions/reissue-revoked-invitation})

                 invite-expired?
                 (invite-action-button req {:invite-code invite-code
                                            :action      "reissue"
                                            :label       [:i18n/tr :action/reissue-invitation]
                                            :variant     "brand"
                                            :action-key  ::actions/reissue-invitation})

                 :else
                 (invite-action-button req {:invite-code invite-code
                                            :action      "resend"
                                            :label       [:i18n/tr :action/resend-invitation]
                                            :variant     "brand"
                                            :action-key  ::actions/resend-invitation}))
               (when-not revoked?
                 (invite-action-button req {:invite-code invite-code
                                            :action      "delete"
                                            :label       [:i18n/tr :action/delete]
                                            :variant     "danger"
                                            :action-key  ::actions/delete-invitation}))]]]))]]]]))

(defn- member-row [req member]
  (let [{:member/keys [email phone active?]} member
        section-name       (member-section-name member)
        mobile-discounts   (travel-discount-tags req member "mobile")
        desktop-discounts  (travel-discount-tags req member "desktop")]
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
         (status-badge req active?)]]]]
     [:td {:class "members-index-col members-index-col--discount"} desktop-discounts]
     [:td {:class "members-index-col members-index-col--md"} email]
     [:td {:class "members-index-col members-index-col--lg"} (or phone "—")]
     [:td {:class "members-index-col members-index-col--sm"} section-name]
     [:td {:class "members-index-col members-index-col--sm"}
      (status-badge req active?)]]))

(defn- members-table [{:keys [tr] :as req} page-state members]
  (if (seq members)
    [:div {:class "table-shell"}
     [:table {:class "members-index-table"}
      [:thead
       [:tr
        [:th (sort-button req page-state "name" (tr [:member/name]))]
        [:th {:class "members-index-col members-index-col--discount"}
         (sort-button req page-state "travel-discount" (tr [:oebb-discount]))]
        [:th {:class "members-index-col members-index-col--md"}
         (sort-button req page-state "email" (tr [:Email]))]
        [:th {:class "members-index-col members-index-col--lg"}
         (sort-button req page-state "phone" (tr [:Phone]))]
        [:th {:class "members-index-col members-index-col--sm"}
         (sort-button req page-state "section" (tr [:section]))]
        [:th {:class "members-index-col members-index-col--sm"}
         (sort-button req page-state "active" (tr [:Active]))]]]
      [:tbody
       (for [member members]
         (member-row req member))]]]
    (ui2/empty-state
     (tr [:member/browse-empty])
     (tr [:member/browse-empty-subtitle]))))

(defn page [{:keys [db page-state] :as req}]
  (let [page-state       (queries/normalize-page-state (:members-index page-state))
        members          (queries/members db page-state)
        invitations      (->> (concat (queries/members-with-pending-invites db)
                                      (queries/members-with-revoked-invites db))
                              (sort-by :member/name)
                              vec)]
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
                                                          :member-id nil
                                                          :generation nil
                                                          :inflight false}})}
       [page-header/PageHeader
        {::page-header/title    [:i18n/tr :members/title]
         ::page-header/subtitle [:i18n/tr :members/member-count {:count (count members)}]}]
       [:div {:class "wa-grid wa-gap-s"
              :style "--min-column-size: min(100%, 16rem);"}
        (search-control req page-state)
        (filter-control req page-state)]
       (invitations-panel req invitations)
       (members-table req page-state members)]])))

(d*/refresh-all!)
