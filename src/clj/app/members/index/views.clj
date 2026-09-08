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
   [app.ui2.divider :as divider]
   [app.ui2.icon :as ico]
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

(defn- table-url [page-state overrides]
  (str "/members?" (urls/params->query-string (merge page-state overrides))))

(defn- sort-button [page-state field label]
  [:a {:href (table-url page-state
                        {:sort-field field
                         :sort-order (if (and (= field (:sort-field page-state))
                                              (= "asc" (:sort-order page-state)))
                                       "desc" "asc")
                         :page 1})
       :class "wa-link-plain wa-font-weight-bold"}
   label
   [:span {:aria-hidden true} (or (sort-indicator page-state field) "")]])

(defn- search-control [{:keys [search] :as page-state}]
  [:form {:method "get" :action "/members"}
   (for [[k v] (assoc (dissoc page-state :search) :page 1)]
     [:input {:type "hidden" :name (name k) :value v}])
   [:wa-input {:name "search"
               :label [:i18n/tr :action/search]
               :placeholder [:i18n/tr :action/search]
               :appearance "outlined"
               :size "m"
               :value search
               :with-clear true
               :data-on:input__debounce.250ms "evt.target.closest('form').requestSubmit()"}]])

(defn- filter-control [{:keys [filter-preset] :as page-state}]
  [:form {:method "get" :action "/members"}
   (for [[k v] (assoc (dissoc page-state :filter-preset) :page 1)]
     [:input {:type "hidden" :name (name k) :value v}])
   [:wa-select {:name "filter-preset"
                :label [:i18n/tr :action/filter]
                :appearance "outlined"
                :size "m"
                :value filter-preset
                :data-on:change "evt.target.closest('form').requestSubmit()"}
    [:wa-option {:value "active"} [:i18n/tr :members/filter-active]]
    [:wa-option {:value "inactive"} [:i18n/tr :members/filter-inactive]]
    [:wa-option {:value "all"} [:i18n/tr :members/filter-all]]]])

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
  [button/Button {:appearance         (if (= "resend" action) "plain" "outlined")
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

(defn- invitations-panel [req invitations]
  (when (seq invitations)
    [:section {:class "wa-stack wa-gap-s"}
     (ui2/title-block {:level    2
                       :title    [:i18n/tr :members/invitations-title]
                       :subtitle [:i18n/tr :members/invitations-subtitle]})
     [:div {:class "table-shell"}
      [:table {:class "members-index-table"}
       [:thead
        [:tr
         [:th [:i18n/tr (members.domain/member-attribute-label-key :member/name)]]
         [:th [:i18n/tr (members.domain/member-attribute-label-key :member/email)]]
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
               (when-not (or revoked? invite-expired?)
                 [:wa-copy-button {:value (urls/absolute-link-new-user-invite
                                           (get-in req [:system :env]) invite-code)
                                   :tooltip "copy"}
                  [button/Button {:appearance "plain" :variant "brand" :size "s"}
                   [:i18n/tr :members/copy-invite]]])
               (when-not revoked?
                 (invite-action-button req {:invite-code invite-code
                                            :action      "delete"
                                            :label       [:i18n/tr :action/delete]
                                            :variant     "danger"
                                            :action-key  ::actions/delete-invitation}))]]]))]]]]))

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

(defn- members-table [page-state members]
  (if (seq members)
    [:div {:class "table-shell"}
     [:table {:class "members-index-table"}
      [:thead
       [:tr
        [:th (sort-button page-state "name" [:i18n/tr (members.domain/member-attribute-label-key :member/name)])]
        [:th {:class "members-index-col members-index-col--discount"}
         (sort-button page-state "travel-discount" [:i18n/tr :members/oebb-discount])]
        [:th {:class "members-index-col members-index-col--md"}
         (sort-button page-state "email" [:i18n/tr (members.domain/member-attribute-label-key :member/email)])]
        [:th {:class "members-index-col members-index-col--lg"}
         (sort-button page-state "phone" [:i18n/tr (members.domain/member-attribute-label-key :member/phone)])]
        [:th {:class "members-index-col members-index-col--sm"}
         (sort-button page-state "section" [:i18n/tr (members.domain/member-attribute-label-key :member/section)])]
        [:th {:class "members-index-col members-index-col--sm"}
         (sort-button page-state "active" [:i18n/tr :status-active])]]]
      [:tbody
       (for [member members]
         (member-row member))]]]
    (ui2/empty-state
     [:i18n/tr :members/browse-empty]
     [:i18n/tr :members/browse-empty-subtitle])))

(defn- pagination-controls [page-state {:keys [page page-size has-prev? has-next?] :as pagination}]
  [:div {:class "wa-stack wa-gap-xs"}
   [divider/Divider]
   [:nav {:class "wa-cluster wa-gap-2xs wa-justify-content-end"
          :aria-label [:i18n/tr :pagination]}
    [button/Button (cond-> {:appearance "plain" :size "s"
                            :aria-label [:i18n/tr :action/previous]}
                     has-prev? (assoc :href (table-url page-state {:page (dec page)}))
                     (not has-prev?) (assoc :disabled true))
     [ico/Icon {::ico/library :phosphor ::ico/name :caret-left}]]
    [:wa-dropdown {:data-on:wa-select
                   (str "const urls = "
                        (d*/->signals (into {} (for [size queries/page-size-options]
                                                 [(str size) (table-url page-state {:page 1 :page-size size})])))
                        "; window.location.href = urls[evt.detail.item.value]")}
     [button/Button {:slot "trigger" :appearance "plain" :size "s"}
      [:i18n/tr :pagination-summary
       (select-keys pagination [:range-start :range-end :total-results])]]
     [:h3 [:i18n/tr :rows-per-page]]
     (for [size queries/page-size-options]
       [:wa-dropdown-item {:value (str size)}
        [ico/Icon (cond-> {::ico/library :phosphor ::ico/name :check :slot "icon"}
                    (not= size page-size) (assoc :style "visibility: hidden;"))]
        size])]
    [button/Button (cond-> {:appearance "plain" :size "s"
                            :aria-label [:i18n/tr :action/next]}
                     has-next? (assoc :href (table-url page-state {:page (inc page)}))
                     (not has-next?) (assoc :disabled true))
     [ico/Icon {::ico/library :phosphor ::ico/name :caret-right}]]]])

(defn page [{:keys [db] :as req}]
  (let [params           (or (get-in req [:parameters :query]) (:query-params req))
        page-state       (queries/normalize-page-state params)
        pagination       (queries/paginate-members page-state (queries/members db page-state))
        members          (:members pagination)
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
             :data-signals (d*/->signals {:invite {:action nil
                                                   :code nil
                                                   :member-id nil
                                                   :generation nil
                                                   :inflight false}})}
       [page-header/PageHeader
        {::page-header/title    [:i18n/tr :members/title]
         ::page-header/subtitle [:i18n/tr :members/member-count {:count (:total-results pagination)}]}]
       [:div {:class "wa-grid wa-gap-s"
              :style "--min-column-size: min(100%, 16rem);"}
        (search-control page-state)
        (filter-control page-state)]
       (members-table page-state members)
       (pagination-controls page-state pagination)
       (invitations-panel req invitations)]])))

(d*/refresh-all!)
