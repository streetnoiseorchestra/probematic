(ns app.settings.teams.views
  (:require
   [app.datastar :as d*]
   [app.queries :as q]
   [app.settings.domain :as domain]
   [app.settings.teams.actions :as actions]
   [app.ui2 :as ui2]
   [app.ui2.page-header :as page-header]
   [app.ui2.button :as button]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.urls :as urls]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn team-type-label [team-type]
  (case team-type
    :team.type/insurance [:i18n/tr :band-settings/team-type-insurance]
    nil                  "—"))

(defn team-type-options []
  (into [{:value "" :label " - "}]
        (map (fn [team-type]
               {:label (team-type-label team-type)
                :value (name team-type)})
             domain/team-types)))

(defn team-member-label [{:member/keys [name nick]}]
  (if (seq nick)
    (str name " (" nick ")")
    name))

(defn team-create-form [{:keys [page-state] :as req}]
  (let [{:keys [error]} (:team-create page-state)
        team-name-error (-> error :team-name :error)]
    (when (get-in page-state [:team-create :open])
      [:wa-dialog {:id                    "team-create-dialog"
                   :label                 [:i18n/tr :band-settings/team-create]
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (->expr
                                           (evt.preventDefault)
                                           (@post ~(d*/act req ::actions/close-team-create)))}
       [:form {:id             "team-create-form"
               :data-id        "team-create"
               :data-action    (d*/act req ::actions/create-team)
               :data-on:submit "evt.preventDefault();"}
        [:wa-input {:placeholder  [:i18n/tr :band-settings/team-name]
                    :type         :text
                    :required     true
                    :label        [:i18n/tr :band-settings/team-name]
                    :autofocus    true
                    :hint         team-name-error
                    :data-invalid (if team-name-error "true" nil)
                    :data-bind    "team-create.team-name"
                    :name         :team-name}]]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        [:i18n/tr :action/cancel]]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :form               "team-create-form"
                       :data-attr:disabled "!!$loading && $loading !== 'team-create'"
                       :data-attr:loading  "$loading === 'team-create'"}
        [:i18n/tr :action/create]]])))

(defn team-edit-form [{:keys [db page-state] :as req}]
  (let [{:keys [error team-id member-id team-type]} (:team page-state)
        team            (when team-id (q/retrieve-team db team-id))
        all-members     (q/members-for-select db)
        team-name-error (-> error :team-name :error)]
    (when team-id
      [:wa-dialog {:id                    "team-edit-dialog"
                   :label                 [:i18n/tr :action/update]
                   :data-init__delay.10ms "el.open = true"
                   :data-preserve-attr    "open"
                   :data-on:wa-hide       (str "if (evt.target !== el) return; evt.preventDefault(); @post('"
                                               (d*/act req ::actions/close-team-edit)
                                               "')")}
       [:form {:id             "team-edit-form"
               :data-id        "team"
               :data-action    (d*/act req ::actions/update-team)
               :data-on:submit "evt.preventDefault();"}
        [:input {:type :hidden :name "team.team-id" :value nil}]
        [:div {:class "wa-stack wa-gap-m"}
         [:wa-input {:placeholder  [:i18n/tr :band-settings/team-name]
                     :type         :text
                     :required     true
                     :label        [:i18n/tr :band-settings/team-name]
                     :autofocus    true
                     :hint         team-name-error
                     :data-invalid (if team-name-error "true" nil)
                     :data-bind    "team.team-name"
                     :name         :team-name}]
         (into
          [:wa-select {:label     [:i18n/tr :band-settings/team-type]
                       :name      :team-type
                       :value     (or team-type "")
                       :data-bind "team.team-type"}]
          (for [{:keys [label value]} (team-type-options)]
            [:wa-option {:value value} label]))
         [:div {:class "wa-stack wa-gap-s"}
          [:div {:class "wa-stack wa-gap-2xs"}
           [:span {:class "wa-caption-s"} [:i18n/tr :band-settings/team-members]]
           (if (seq (:team/members team))
             (into
              [:div {:class "wa-stack wa-gap-2xs"}]
              (for [{:member/keys [member-id name] :as member} (:team/members team)]
                (let [loading-id (pr-str (str member-id))]
                  [:div {:class "settings-team-member-row wa-flank:end wa-align-items-center wa-gap-xs"}
                   [:a {:href (urls/link-member member)} name]
                   [button/Button {:appearance         "plain"
                                   :variant            "danger"
                                   :size               "s"
                                   :data-id            (str member-id)
                                   :data-action        (d*/act req ::actions/remove-team-member)
                                   :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                                   :data-attr:loading  (str "$loading === " loading-id)
                                   :data-on:mousedown  (->expr (set! $team.remove-member-id ~(str member-id)))}
                    [:i18n/tr :action/remove]]])))
             [:span {:class "wa-caption-s wa-color-text-quiet italic"}
              [:i18n/tr :band-settings/team-no-members]])]
          [:div {:class "wa-cluster wa-align-items-end"}
           (into
            [:wa-select {:label     [:i18n/tr :band-settings/team-member-add]
                         :name      :member-id
                         :value     (or member-id "")
                         :data-bind "team.member-id"}
             [:wa-option {:value ""} " - "]]
            (for [member all-members]
              [:wa-option {:value (:member/member-id member)}
               (team-member-label member)]))
           [button/Button {:appearance         "outlined"
                           :variant            "brand"
                           :size               "m"
                           :data-id            "team-add-member"
                           :data-action        (d*/act req ::actions/add-team-member)
                           :data-attr:disabled "$team.member-id == null || $team.member-id === '' || (!!$loading && $loading !== 'team-add-member')"
                           :data-attr:loading  "$loading === 'team-add-member'"}
            [:i18n/tr :action/add]]]]]]
       [button/Button {:slot        "footer"
                       :appearance  "outlined"
                       :data-dialog "close"}
        [:i18n/tr :action/cancel]]
       [button/Button {:slot               "footer"
                       :appearance         "filled"
                       :variant            "brand"
                       :type               "submit"
                       :form               "team-edit-form"
                       :data-attr:disabled "!!$loading && $loading !== 'team'"
                       :data-attr:loading  "$loading === 'team'"}
        [:i18n/tr :action/save]]])))

(defn team-remove-dialog [req {team-name :team/name :team/keys [team-id]}]
  (let [loading-id (pr-str (str team-id))]
    [:wa-dialog {:id    (ui2/remove-dialog-id "team" team-id)
                 :label [:i18n/tr :action/confirm-generic]}
     [:p [:i18n/tr :band-settings/team-delete-confirm {:team-name team-name}]]
     [button/Button {:slot        "footer"
                     :appearance  "outlined"
                     :data-dialog "close"}
      [:i18n/tr :action/cancel]]
     [button/Button {:slot               "footer"
                     :appearance         "filled"
                     :variant            "danger"
                     :data-dialog        "close"
                     :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                     :data-attr:loading  (str "$loading === " loading-id)
                     :data-id            team-id
                     :data-action        (d*/act req ::actions/delete-team)}
      [:i18n/tr :action/confirm-delete]]]))

(defn team-table-row [req {team-name :team/name :team/keys [team-id members team-type]}]
  (let [button-id  (str "team-actions-" team-id)
        loading-id (pr-str (str team-id))]
    [:tr {:id (str "team-container-" team-id)}
     [:td {:class "align-middle"} team-name]
     [:td {:class "align-middle"}
      (if (seq members)
        [:div {:class "wa-cluster wa-gap-2xs"}
         (for [{:member/keys [name] :as member} members]
           [:a {:href (urls/link-member member)}
            name])]
        [:span {:class "wa-color-text-quiet italic"}
         [:i18n/tr :band-settings/team-no-members]])]
     [:td {:class "align-middle"} (team-type-label team-type)]
     [:td {:class "align-top text-right"}
      (ui2/row-action-menu
       {:button-id button-id
        :items     [{:label              [:i18n/tr :action/update]
                     :data-attr:disabled (str "!!$loading && $loading !== " loading-id)
                     :data-attr:loading  (str "$loading === " loading-id)
                     :data-id            team-id
                     :data-action        (d*/act req ::actions/open-team-edit)}
                    {:label       [:i18n/tr :action/remove]
                     :variant     "danger"
                     :data-dialog (format "open %s" (ui2/remove-dialog-id "team" team-id))}]})]]))

(defn teams-panel [{:keys [page-state db] :as req}]
  (let [teams (q/retrieve-all-teams db)]
    [:div {:id           "teams-panel"
           :data-signals (d*/->signals {:team-create (:team-create page-state)
                                        :team        (:team page-state)})}
     (team-create-form req)
     (team-edit-form req)
     (for [team teams]
       (team-remove-dialog req team))
     (ui2/section-card
      {:title    [:i18n/tr :band-settings/team-manage-title]
       :subtitle [:i18n/tr :band-settings/team-manage-subtitle]
       :actions  [[button/Button {:appearance  "outlined"
                                  :variant     "brand"
                                  :size        "m"
                                  :data-id     "team-create"
                                  :data-action (d*/act req ::actions/open-team-create)}
                   [:i18n/tr :band-settings/team-create]]]}
      (ui2/table-shell
       (if (seq teams)
         [:table
          [:thead
           [:tr
            [:th [:i18n/tr :band-settings/team-column-name]]
            [:th [:i18n/tr :band-settings/team-members]]
            [:th [:i18n/tr :band-settings/team-column-type]]
            [:th]]]
          [:tbody
           (for [team teams]
             (team-table-row req team))]]
         (ui2/empty-state
          [:i18n/tr :band-settings/team-empty-title]
          [:i18n/tr :band-settings/team-empty-subtitle]))))]))

(defn page [req]
  (let [title [:i18n/tr :band-settings/team-title]]
    (ui2/datastar-page*
     [:div {:class "wa-stack wa-gap-2xl"}
      [page-header/PageHeader
       {:breadcrumb [breadcrumb/Breadcrumb
                     {}
                     [breadcrumb/BreadcrumbItem {::breadcrumb/href "/band-settings"}
                      [:i18n/tr :band-settings/title]]
                     [breadcrumb/BreadcrumbItem title]]
        :title      title
        :subtitle   [:i18n/tr :band-settings/team-page-subtitle]}]
      (teams-panel req)])))

(d*/refresh-all!)
