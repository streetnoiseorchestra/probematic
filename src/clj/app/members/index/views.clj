(ns app.members.index.views
  (:require
   [app.datastar :as d*]
   [app.members.index.actions :as actions]
   [app.members.index.queries :as queries]
   [app.members.ui :as members.ui]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
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

(defn- invite-button [{:keys [tr]}]
  [button/Button {:appearance "filled"
                  :variant    "brand"
                  :href       "/members/invite"}
   (tr [:member/invite-member])])

(defn- members-toolbar [{:keys [tr] :as req} page-state total]
  [:div {:class "members-index-toolbar"}
   (ui2/title-block {:title    (tr [:nav/members])
                     :subtitle (str (tr [:total]) ": " total)})
   [:div {:class "members-index-toolbar-controls"}
    (search-control req page-state)
    (filter-control req page-state)
    (invite-button req)]])

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

(defn- open-invitations-panel [{:keys [tr] :as req} open-invitations]
  (when (seq open-invitations)
    [:section {:class "wa-stack wa-gap-s"}
     (ui2/title-block {:level    2
                       :title    (tr [:member/open-invitations])
                       :subtitle (tr [:member/open-invitations-subtitle])})
     [:div {:class "table-shell"}
      [:table {:class "members-index-table"}
       [:thead
        [:tr
         [:th (tr [:member/name])]
         [:th (tr [:Email])]
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
                                        :label       (tr [:action/resend-invite])
                                        :variant     "brand"
                                        :action-key  ::actions/resend-invitation})
             (invite-action-button req {:invite-code invite-code
                                        :action      "delete"
                                        :label       (tr [:action/delete])
                                        :variant     "danger"
                                        :action-key  ::actions/delete-invitation})]]])]]]]))

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
        open-invitations (queries/open-invitations req)]
    (ui2/datastar-page
     [:div {:class        "wa-stack wa-gap-l"
            :data-signals (d*/->signals {:members-index page-state
                                         :invite        {:action nil
                                                         :code nil
                                                         :inflight false}})}
      (members-toolbar req page-state (count members))
      (open-invitations-panel req open-invitations)
      (members-table req page-state members)])))

(d*/refresh-all!)
