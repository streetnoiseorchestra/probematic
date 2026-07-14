(ns app.insurance.page-shells-test
  (:require
   [app.insurance.coverage.create.views :as coverage-create.views]
   [app.insurance.coverage.edit.views :as coverage-edit.views]
   [app.insurance.coverage.views :as coverage.views]
   [app.insurance.index.views :as index.views]
   [app.insurance.policy.changes.views :as policy-changes.views]
   [app.insurance.policy.create.views :as policy-create.views]
   [app.insurance.policy.dashboard.views :as dashboard.views]
   [app.insurance.policy.notifications.views :as policy-notifications.views]
   [app.insurance.policy.review.views :as review.views]
   [app.insurance.policy.settings.views :as settings.views]
   [app.insurance.policy.surveys.views :as surveys.views]
   [app.insurance.policy.workbench.views :as workbench.views]
   [app.insurance.survey.views :as survey.views]
   [app.insurance.test-support :as insurance-test]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.icon :as ico]
   [app.ui2.page-shell-test-support :as page-shell]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(defn fixture []
  (let [{:keys [conn member-id]} (tc/new-system "insurance-page-shells")
        ids                      (insurance-test/seed-page-shell-fixture! conn member-id)]
    (merge ids
           {:conn conn
            :request
            {:db             (d/db conn)
             :current-locale "en"
             :session        {:session/member {:member/member-id member-id
                                               :member/name      "Ada"
                                               :member/email     "ada@example.test"}}
             :system         {:env {:app-base-url "https://example.test"}}
             :tr             tr
             ::r/router      router}})))

(deftest directory-and-coverage-use-shared-page-shells
  (let [{:keys [request policy-id coverage-id]} (fixture)]
    (testing "The Insurance directory owns collection context and policy creation."
      (is (= {:width       :wide
              :breadcrumbs [:home :insurance/title]
              :mobile      {:label :home :href "/"}
              :actions     [{:label :insurance/new-policy
                             :href  "/insurance-new/"
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    []}
             (-> request index.views/page page-shell/page-contract))))
    (testing "Coverage detail keeps the policy and instrument in context."
      (is (= {:width       :wide
              :breadcrumbs [:insurance/title "Insurance 2026" "Test Trumpet"]
              :mobile      {:label "Insurance 2026"
                            :href  (str "/insurance-policy/" policy-id "/")}
              :actions     [{:label :action/edit
                             :href  (str "/insurance-coverage-edit/" coverage-id "/")
                             :appearance "outlined"
                             :variant "brand"}]
              :overflow    []}
             (-> request
                 (assoc :path-params {:coverage-id coverage-id})
                 coverage.views/page
                 page-shell/page-contract))))))

(deftest insurance-directory-faq-uses-active-policy-coverage-data
  (let [{:keys [conn coverage-type-id policy-id request]} (fixture)
        optional-type-id (random-uuid)
        _                @(d/transact
                           conn
                           [[:db/add
                             [:insurance.coverage.type/type-id coverage-type-id]
                             :insurance.coverage.type/name
                             "Worldwide touring"]
                            [:db/add
                             [:insurance.coverage.type/type-id coverage-type-id]
                             :insurance.coverage.type/description
                             "Coverage while travelling with the instrument."]
                            {:db/id                                  "optional-coverage-type"
                             :insurance.coverage.type/type-id        optional-type-id
                             :insurance.coverage.type/name           "Locked rehearsal storage"
                             :insurance.coverage.type/description    "Coverage while stored in a locked rehearsal room."
                             :insurance.coverage.type/premium-factor 0.25M}
                            [:db/add
                             [:insurance.policy/policy-id policy-id]
                             :insurance.policy/coverage-types
                             "optional-coverage-type"]])
        request          (assoc request :db (d/db conn))
        coverage-faq     (l/select-one "#faq5" (index.views/page request))
        coverage-copy    ["Worldwide touring"
                          "Coverage while travelling with the instrument."
                          "Locked rehearsal storage"
                          "Coverage while stored in a locked rehearsal room."]
        required-schema? (boolean
                          (d/entid
                           (:db request)
                           :insurance.coverage.type/required?))]
    (testing "coverage names and descriptions come from the active policy"
      (is (= coverage-copy
             (filterv #(str/includes? (l/text coverage-faq) %)
                      coverage-copy))))
    (testing "each policy type is identified as required or optional"
      (is (= :available (if required-schema? :available :missing)))
      (when required-schema?
        @(d/transact
          conn
          [[:db/add
            [:insurance.coverage.type/type-id coverage-type-id]
            :insurance.coverage.type/required?
            true]
           [:db/add
            [:insurance.coverage.type/type-id optional-type-id]
            :insurance.coverage.type/required?
            false]])
        (let [view     (-> request
                           (assoc :db (d/db conn))
                           index.views/page)
              faq-text (l/text (l/select-one "#faq5" view))
              labels   ["coverage-required" "coverage-optional"]]
          (is (= labels
                 (filterv #(str/includes? faq-text %) labels))))))))

(deftest policy-dashboard-lifecycle-toolbar
  (let [{:keys [conn request policy-id coverage-id]} (fixture)
        policy-url       (str "/insurance-policy/" policy-id "/")
        review-url       (str policy-url "review")
        workbench-url    (str policy-url "workbench")
        settings-url     (str policy-url "settings")
        surveys-url      (str policy-url "surveys")
        changes-url      (str "/insurance-policy-changes/" policy-id "/")
        notifications-url (str "/insurance-policy-notify/" policy-id "/")
        request-for      (fn [db]
                           (assoc request
                                  :db db
                                  :path-params {:policy-id policy-id}))
        todo-contract    (-> (:db request)
                             request-for
                             dashboard.views/page
                             page-shell/page-contract)
        ready-db         (:db-after
                          @(d/transact conn [[:db/add
                                              [:instrument.coverage/coverage-id coverage-id]
                                              :instrument.coverage/status
                                              :instrument.coverage.status/reviewed]]))
        ready-contract   (-> ready-db
                             request-for
                             dashboard.views/page
                             page-shell/page-contract)
        sent-db          (:db-after
                          @(d/transact conn [[:db/add
                                              [:insurance.policy/policy-id policy-id]
                                              :insurance.policy/status
                                              :insurance.policy.status/sent]]))
        sent-contract    (-> sent-db
                             request-for
                             dashboard.views/page
                             page-shell/page-contract)
        active-db        (:db-after
                          @(d/transact conn [[:db/add
                                              [:insurance.policy/policy-id policy-id]
                                              :insurance.policy/status
                                              :insurance.policy.status/active]]))
        active-contract  (-> active-db
                             request-for
                             dashboard.views/page
                             page-shell/page-contract)]
    (testing "The primary action follows the policy lifecycle and outstanding review work."
      (is (= {:todo   [{:label      :insurance/review
                        :href       review-url
                        :appearance "filled"
                        :variant    "brand"}]
              :ready  [{:label      :insurance/send-changes
                        :href       changes-url
                        :appearance "filled"
                        :variant    "brand"}]
              :sent   [{:label      :insurance/workbench
                        :href       workbench-url
                        :appearance "filled"
                        :variant    "brand"}]
              :active [{:label      :insurance/request-payments-title
                        :href       notifications-url
                        :appearance "filled"
                        :variant    "brand"}]}
             {:todo   (:actions todo-contract)
              :ready  (:actions ready-contract)
              :sent   (:actions sent-contract)
              :active (:actions active-contract)})))
    (testing "Secondary actions remain reachable without offering a live blocked destination."
      (is (= {:todo [{:label :insurance/workbench
                      :value workbench-url}
                     {:label :insurance/add-coverage
                      :value (str "/insurance-coverage-create/" policy-id)}
                     {:label :insurance/policy-settings
                      :value settings-url}
                     {:label :insurance/manage-surveys
                      :value surveys-url}
                     {:label    :insurance/send-changes
                      :disabled true}]
              :ready [{:label :insurance/review
                       :value review-url}
                      {:label :insurance/workbench
                       :value workbench-url}
                      {:label :insurance/add-coverage
                       :value (str "/insurance-coverage-create/" policy-id)}
                      {:label :insurance/policy-settings
                       :value settings-url}
                      {:label :insurance/manage-surveys
                       :value surveys-url}]
              :sent [{:label :insurance/review
                      :value review-url}
                     {:label :insurance/add-coverage
                      :value (str "/insurance-coverage-create/" policy-id)}
                     {:label :insurance/policy-settings
                      :value settings-url}
                     {:label :insurance/manage-surveys
                      :value surveys-url}]
              :active [{:label :insurance/review
                        :value review-url}
                       {:label :insurance/workbench
                        :value workbench-url}
                       {:label :insurance/add-coverage
                        :value (str "/insurance-coverage-create/" policy-id)}
                       {:label :insurance/policy-settings
                        :value settings-url}
                       {:label :insurance/manage-surveys
                        :value surveys-url}]}
             {:todo   (:overflow todo-contract)
              :ready  (:overflow ready-contract)
              :sent   (:overflow sent-contract)
              :active (:overflow active-contract)})))))

(deftest open-policy-survey-is-prominent-on-the-dashboard
  (let [{:keys [conn coverage-id outsider-id request policy-id]} (fixture)
        member-id   (get-in request [:session :session/member :member/member-id])
        surveys-url (urls/link-policy-surveys policy-id)
        _           @(d/transact
                      conn
                      [{:insurance.survey/survey-id   (random-uuid)
                        :insurance.survey/policy      [:insurance.policy/policy-id policy-id]
                        :insurance.survey/created-at  #inst "2026-07-01T00:00:00.000-00:00"
                        :insurance.survey/closes-at   #inst "2099-08-01T00:00:00.000-00:00"
                        :insurance.survey/responses
                        [{:insurance.survey.response/response-id  (random-uuid)
                          :insurance.survey.response/member       [:member/member-id member-id]
                          :insurance.survey.response/completed-at #inst "2026-07-02T00:00:00.000-00:00"}
                         {:insurance.survey.response/response-id (random-uuid)
                          :insurance.survey.response/member      [:member/member-id outsider-id]
                          :insurance.survey.response/coverage-reports
                          [{:insurance.survey.report/report-id (random-uuid)
                            :insurance.survey.report/coverage  [:instrument.coverage/coverage-id coverage-id]}]}]}])
        view        (-> request
                        (assoc :db (d/db conn)
                               :path-params {:policy-id policy-id})
                        dashboard.views/page)
        contract    (page-shell/page-contract view)
        main-column (first (l/select ".leading-none.wa-grid" view))
        cards       (->> (l/children main-column)
                         (filter #(= card/Card (first %)))
                         vec)
        survey-card (second cards)
        card-action (l/select-one button/Button survey-card)
        action-icon (l/select-one ico/Icon card-action)
        progress    (l/select-one 'div.insurance-dashboard-survey-progress survey-card)
        percentage  (l/select-one ".insurance-dashboard-survey-progress-value" survey-card)
        summary     (l/select-one ".wa-text-end" survey-card)]
    (is (= {:toolbar-actions [{:label      :insurance/manage-surveys
                               :href       surveys-url
                               :appearance "outlined"
                               :variant    "brand"}]
            :overflow-labels [:insurance/review
                              :insurance/workbench
                              :insurance/add-coverage
                              :insurance/policy-settings
                              :insurance/send-changes]
            :card-position   1
            :card-classes    #{"insurance-dashboard-card"
                               "insurance-dashboard-survey-progress-card"}
            :card-title      :insurance/survey-responses-title
            :card-action     {:slot       "header-actions"
                              :href       surveys-url
                              :appearance "plain"
                              :variant    "brand"
                              :title      "manage-surveys"
                              :aria-label "manage-surveys"
                              :icon       :clipboard-text}
            :progress       {:role          "progressbar"
                             :aria-valuemin 0
                             :aria-valuemax 100
                             :aria-valuenow 50
                             :aria-label    [:i18n/tr
                                             :insurance/survey-progress-summary
                                             {:completed 1 :total 2}]
                             :style         {"--progress-value" "50.0%"}}
            :percentage     {:type                    "percent"
                             :value                   0.5
                             :minimum-fraction-digits 0
                             :maximum-fraction-digits 0}
            :legend-labels   [:insurance/survey-complete
                              :insurance/survey-incomplete]
            :legend-counts   ["1" "1"]
            :summary         {:key  :insurance/survey-progress-summary
                              :vars {:completed 1 :total 2}}}
           {:toolbar-actions (:actions contract)
            :overflow-labels (mapv :label (:overflow contract))
            :card-position   (.indexOf cards survey-card)
            :card-classes    (:class (l/attrs survey-card))
            :card-title      (page-shell/translation-key
                              (l/select-one 'h2 survey-card))
            :card-action     (assoc
                              (select-keys (l/attrs card-action)
                                           [:slot
                                            :href
                                            :appearance
                                            :variant
                                            :title
                                            :aria-label])
                              :icon (::ico/name (l/attrs action-icon)))
            :progress       (select-keys (l/attrs progress)
                                         [:role
                                          :aria-valuemin
                                          :aria-valuemax
                                          :aria-valuenow
                                          :aria-label
                                          :style])
            :percentage     (select-keys (l/attrs percentage)
                                         [:type
                                          :value
                                          :minimum-fraction-digits
                                          :maximum-fraction-digits])
            :legend-labels   (mapv page-shell/translation-key
                                   (l/select '[dl dt] survey-card))
            :legend-counts   (mapv l/text (l/select '[dl dd] survey-card))
            :summary         {:key  (page-shell/translation-key summary)
                              :vars (some-> (l/select-one :i18n/tr summary)
                                            l/last-child)}}))))

(deftest open-survey-replaces-each-policy-primary-action
  (let [{:keys [conn coverage-id request policy-id]} (fixture)
        _           @(d/transact
                      conn
                      [{:insurance.survey/survey-id   (random-uuid)
                        :insurance.survey/policy      [:insurance.policy/policy-id policy-id]
                        :insurance.survey/created-at  #inst "2026-07-01T00:00:00.000-00:00"
                        :insurance.survey/closes-at   #inst "2099-08-01T00:00:00.000-00:00"}])
        contract-at (fn [db]
                      (-> request
                          (assoc :db db
                                 :path-params {:policy-id policy-id})
                          dashboard.views/page
                          page-shell/page-contract))
        todo        (contract-at (d/db conn))
        ready-db    (:db-after
                     @(d/transact conn [[:db/add
                                         [:instrument.coverage/coverage-id coverage-id]
                                         :instrument.coverage/status
                                         :instrument.coverage.status/reviewed]]))
        ready       (contract-at ready-db)
        sent-db     (:db-after
                     @(d/transact conn [[:db/add
                                         [:insurance.policy/policy-id policy-id]
                                         :insurance.policy/status
                                         :insurance.policy.status/sent]]))
        sent        (contract-at sent-db)
        active-db   (:db-after
                     @(d/transact conn [[:db/add
                                         [:insurance.policy/policy-id policy-id]
                                         :insurance.policy/status
                                         :insurance.policy.status/active]]))
        active      (contract-at active-db)
        summarize   (fn [contract]
                      {:actions  (mapv :label (:actions contract))
                       :overflow (mapv :label (:overflow contract))})]
    (is (= {:todo   {:actions  [:insurance/manage-surveys]
                     :overflow [:insurance/review
                                :insurance/workbench
                                :insurance/add-coverage
                                :insurance/policy-settings
                                :insurance/send-changes]}
            :ready  {:actions  [:insurance/manage-surveys]
                     :overflow [:insurance/send-changes
                                :insurance/review
                                :insurance/workbench
                                :insurance/add-coverage
                                :insurance/policy-settings]}
            :sent   {:actions  [:insurance/manage-surveys]
                     :overflow [:insurance/workbench
                                :insurance/review
                                :insurance/add-coverage
                                :insurance/policy-settings]}
            :active {:actions  [:insurance/manage-surveys]
                     :overflow [:insurance/request-payments-title
                                :insurance/review
                                :insurance/workbench
                                :insurance/add-coverage
                                :insurance/policy-settings]}}
           {:todo   (summarize todo)
            :ready  (summarize ready)
            :sent   (summarize sent)
            :active (summarize active)}))))

(deftest policy-dashboard-overflow-actions-have-icons
  (let [{:keys [request policy-id]} (fixture)
        view         (-> request
                         (assoc :path-params {:policy-id policy-id})
                         dashboard.views/page)
        surface      (l/select-one page-surface/PageSurface view)
        toolbar      (-> surface l/attrs ::page-surface/toolbar)
        overflow     (-> toolbar l/attrs ::page-toolbar/overflow-items)
        item-summary (mapv (fn [item]
                             (let [icon (l/select-one ico/Icon item)]
                               {:label (page-shell/translation-key item)
                                :icon  (::ico/name (l/attrs icon))
                                :slot  (:slot (l/attrs icon))}))
                           (l/select 'wa-dropdown-item overflow))]
    (is (= [{:label :insurance/workbench
             :icon  :table
             :slot  "icon"}
            {:label :insurance/add-coverage
             :icon  :plus-circle
             :slot  "icon"}
            {:label :insurance/policy-settings
             :icon  :gear
             :slot  "icon"}
            {:label :insurance/manage-surveys
             :icon  :clipboard-text
             :slot  "icon"}
            {:label :insurance/send-changes
             :icon  :paper-plane-right
             :slot  "icon"}]
           item-summary))))

(deftest policy-dashboard-does-not-offer-team-actions-to-other-members
  (let [{:keys [conn request outsider-id policy-id]} (fixture)
        active-db (:db-after
                   @(d/transact conn [[:db/add
                                       [:insurance.policy/policy-id policy-id]
                                       :insurance.policy/status
                                       :insurance.policy.status/active]]))
        contract (-> request
                     (assoc :db active-db
                            :path-params {:policy-id policy-id}
                            :session {:session/member
                                      {:member/member-id outsider-id}})
                     dashboard.views/page
                     page-shell/page-contract)]
    (is (= [{:label      :insurance/add-coverage
             :href       (str "/insurance-coverage-create/" policy-id)
             :appearance "filled"
             :variant    "brand"}]
           (:actions contract)))
    (is (not-any? #{:insurance/manage-surveys
                    :insurance/request-payments-title}
                  (concat (map :label (:actions contract))
                          (map :label (:overflow contract)))))))

(deftest policy-workflows-use-wide-contextual-surfaces
  (let [{:keys [request policy-id]} (fixture)
        policy-url (str "/insurance-policy/" policy-id "/")]
    (testing "Review keeps policy context while review commands remain with the content."
      (is (= {:width       :wide
              :breadcrumbs [:insurance/title "Insurance 2026" :insurance/review]
              :mobile      {:label "Insurance 2026" :href policy-url}
              :actions     []
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id})
                 review.views/page
                 page-shell/page-contract))))
    (testing "The coverage workbench keeps its filters and bulk actions out of the page toolbar."
      (is (= {:width       :wide
              :breadcrumbs [:insurance/title "Insurance 2026" :insurance/workbench]
              :mobile      {:label "Insurance 2026" :href policy-url}
              :actions     []
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id})
                 workbench.views/page
                 page-shell/page-contract))))
    (testing "Policy settings uses the same wide policy context."
      (is (= {:width       :wide
              :breadcrumbs [:insurance/title "Insurance 2026" :insurance/policy-settings]
              :mobile      {:label "Insurance 2026" :href policy-url}
              :actions     []
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id})
                 settings.views/page
                 page-shell/page-contract))))))

(deftest policy-creation-uses-a-standard-form-surface
  (let [{:keys [request]} (fixture)]
    (is (= {:width       :standard
            :breadcrumbs [:insurance/title :insurance/create-title]
            :mobile      {:label :insurance/title :href "/insurance"}
            :actions     [{:label :action/cancel
                           :href  "/insurance"
                           :appearance "outlined"}
                          {:label :action/create
                           :form "insurance-policy-create-form"
                           :type "submit"
                           :appearance "filled"
                           :variant "brand"}]
            :overflow    []}
           (-> request
               policy-create.views/page
               page-shell/page-contract)))))

(deftest policy-changes-use-a-standard-confirmation-surface
  (let [{:keys [request policy-id]} (fixture)
        policy-url (urls/link-policy policy-id)
        view       (-> request
                       (assoc :path-params {:policy-id policy-id})
                       policy-changes.views/page)]
    (is (= {:width       :standard
            :breadcrumbs [:insurance/title "Insurance 2026" :insurance/send-changes]
            :mobile      {:label "Insurance 2026" :href policy-url}
            :actions     [{:label :action/cancel
                           :href  policy-url
                           :appearance "outlined"}
                          {:label :insurance/confirm-and-send
                           :data-dialog "open insurance-policy-send-changes-dialog"
                           :appearance "filled"
                           :variant "brand"
                           :disabled true}]
            :overflow    [{:label :insurance/confirm-skip-send
                           :data-dialog "open insurance-policy-confirm-changes-dialog"}]}
           (page-shell/page-contract view)))
    (testing "an unconfigured exporter disables spreadsheet actions with guidance"
      (let [guidance (l/select-one "#insurance-policy-exporter-guidance" view)
            previews (l/select :app.ui2.button/button
                               (l/select-one "#insurance-policy-attachments" view))
            send-dialog (l/select-one "#insurance-policy-send-changes-dialog" view)]
        (is (= {:guidance :insurance/exporter-not-configured-guidance
                :previews [true true]
                :send     true}
               {:guidance (page-shell/translation-key guidance)
                :previews (mapv (comp :disabled l/attrs) previews)
                :send     (-> (l/select :app.ui2.button/button send-dialog)
                              last
                              l/attrs
                              :disabled)}))))))

(deftest configured-policy-changes-enable-spreadsheet-actions
  (let [{:keys [conn coverage-type-id policy-id request]} (fixture)]
    @(d/transact
      conn
      [{:db/id [:insurance.policy/policy-id policy-id]
        :insurance.policy/exporter-id :insurance/exporter-harmonia-v1
        :insurance.policy/export-mappings
        [{:insurance.export.mapping/role
          :insurance.exporter.harmonia-v1/overnight-vehicle
          :insurance.export.mapping/coverage-type
          [:insurance.coverage.type/type-id coverage-type-id]}
         {:insurance.export.mapping/role
          :insurance.exporter.harmonia-v1/unattended-building
          :insurance.export.mapping/coverage-type
          [:insurance.coverage.type/type-id coverage-type-id]}]}])
    (let [view (-> request
                   (assoc :db (d/db conn)
                          :path-params {:policy-id policy-id})
                   policy-changes.views/page)
          send-action (second (:actions (page-shell/page-contract view)))
          previews (l/select :app.ui2.button/button
                             (l/select-one "#insurance-policy-attachments" view))]
      (is (= {:send-disabled? false
              :guidance?      false
              :preview-disabled [nil nil]}
             {:send-disabled? (true? (:disabled send-action))
              :guidance?      (boolean
                               (l/select-one
                                "#insurance-policy-exporter-guidance"
                                view))
              :preview-disabled (mapv (comp :disabled l/attrs)
                                      previews)})))))

(deftest payment-notifications-use-a-wide-policy-surface
  (let [{:keys [request policy-id]} (fixture)
        policy     (q/retrieve-policy (:db request) policy-id)
        policy-url (urls/link-policy policy-id)]
    (is (= {:width       :wide
            :breadcrumbs [:insurance/title "Insurance 2026"
                          :insurance/request-payments-title]
            :mobile      {:label "Insurance 2026" :href policy-url}
            :actions     [{:label :action/cancel
                           :href  policy-url
                           :appearance "outlined"}
                          {:label :insurance/send-payment-notifications
                           :form "insurance-payment-notifications-form"
                           :type "submit"
                           :appearance "filled"
                           :variant "brand"}]
            :overflow    []}
           (-> request
               (assoc :path-params {:policy-id policy-id}
                      :policy policy)
               policy-notifications.views/page
               page-shell/page-contract)))))

(deftest payment-notifications-hide-private-data-from-other-members
  (let [{:keys [request outsider-id policy-id]} (fixture)
        policy (q/retrieve-policy (:db request) policy-id)
        view (-> request
                 (assoc :path-params {:policy-id policy-id}
                        :policy policy
                        :session {:session/member
                                  {:member/member-id outsider-id}})
                 policy-notifications.views/page)]
    (is (empty? (:actions (page-shell/page-contract view))))
    (is (nil? (l/select-one "#insurance-payment-notifications-form" view)))))

(deftest policy-surveys-use-a-wide-policy-management-surface
  (let [{:keys [request policy-id]} (fixture)
        policy-url (urls/link-policy policy-id)]
    (is (= {:width       :wide
            :breadcrumbs [:insurance/title "Insurance 2026"
                          :insurance/manage-surveys]
            :mobile      {:label "Insurance 2026" :href policy-url}
            :actions     [{:label      :insurance/start-survey
                           :form       "insurance-survey-admin-form"
                           :type       "submit"
                           :appearance "filled"
                           :variant    "brand"}]
            :overflow    []}
           (-> request
               (assoc :path-params {:policy-id policy-id})
               surveys.views/page
               page-shell/page-contract)))))

(deftest member-instrument-check-uses-a-compact-context-only-surface
  (let [{:keys [conn request policy-id coverage-id]} (fixture)
        member-id (get-in request [:session :session/member :member/member-id])
        _ (insurance-test/seed-member-survey!
           conn
           {:coverage-ids [coverage-id]
            :member-id    member-id
            :policy-id    policy-id})]
    (is (= {:width       :compact
            :breadcrumbs [:home :insurance/review-title]
            :mobile      {:label :home :href "/"}
            :actions     []
            :overflow    []}
           (-> request
               (assoc :db (d/db conn)
                      :path-params {:policy-id policy-id}
                      :policy (q/retrieve-policy (d/db conn) policy-id))
               survey.views/page
               page-shell/page-contract)))))

(deftest coverage-creation-uses-standard-step-surfaces
  (let [{:keys [request policy-id instrument-id]} (fixture)
        redirect "/return"
        step-one (urls/link-coverage-create-edit policy-id instrument-id redirect)
        step-two (urls/link-coverage-create2 policy-id instrument-id redirect)
        step-three (urls/link-coverage-create3 policy-id instrument-id redirect)]
    (testing "The instrument step can be cancelled or submitted from the toolbar."
      (is (= {:width       :standard
              :breadcrumbs [:insurance/title "Insurance 2026"
                            :insurance/add-coverage-title :insurance/instrument-step]
              :mobile      {:label "Insurance 2026"
                            :href  (str "/insurance-policy/" policy-id "/")}
              :actions     [{:label :action/cancel
                             :href  (str "/insurance-policy/" policy-id "/")
                             :appearance "outlined"}
                            {:label :action/next
                             :form "coverage-create-instrument-form"
                             :type "submit"
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id}
                        :query-params {:instrument-id (str instrument-id)
                                       :redirect redirect})
                 coverage-create.views/instrument-page
                 page-shell/page-contract))))
    (testing "The photos step preserves the redirect in both directions."
      (is (= {:width       :standard
              :breadcrumbs [:insurance/title "Insurance 2026"
                            :insurance/add-coverage-title :insurance/photos-step]
              :mobile      {:label :insurance/instrument-step :href step-one}
              :actions     [{:label :action/back
                             :href step-one
                             :appearance "outlined"}
                            {:label :action/next
                             :href step-three
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id
                                      :instrument-id instrument-id}
                        :query-params {:redirect redirect})
                 coverage-create.views/photos-page
                 page-shell/page-contract))))
    (testing "The coverage step submits the final form and returns to Photos."
      (is (= {:width       :standard
              :breadcrumbs [:insurance/title "Insurance 2026"
                            :insurance/add-coverage-title :insurance/coverage-step]
              :mobile      {:label :insurance/photos-step :href step-two}
              :actions     [{:label :action/back
                             :href step-two
                             :appearance "outlined"}
                            {:label :action/save
                             :form "coverage-create-coverage-form"
                             :type "submit"
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    []}
             (-> request
                 (assoc :path-params {:policy-id policy-id
                                      :instrument-id instrument-id}
                        :query-params {:redirect redirect})
                 coverage-create.views/coverage-page
                 page-shell/page-contract))))))

(deftest coverage-edit-uses-form-actions-and-role-gated-overflow
  (let [{:keys [request coverage-id outsider-id]} (fixture)
        edit-request (assoc request :path-params {:coverage-id coverage-id})]
    (testing "An insurance-team member can save or delete while the instrument remains the title."
      (is (= {:width       :standard
              :breadcrumbs [:insurance/title "Insurance 2026" "Test Trumpet" :action/edit]
              :mobile      {:label "Test Trumpet"
                            :href  (str "/insurance-coverage/" coverage-id "/")}
              :actions     [{:label :action/cancel
                             :href  (str "/insurance-coverage/" coverage-id "/")
                             :appearance "plain"}
                            {:label :action/save
                             :form "coverage-edit-form"
                             :type "submit"
                             :appearance "filled"
                             :variant "brand"}]
              :overflow    [{:label :action/delete
                             :data-dialog (str "open coverage-remove-" coverage-id)
                             :variant "danger"}]}
             (-> edit-request
                 coverage-edit.views/page
                 page-shell/page-contract))))
    (testing "Delete is absent when the current member is outside the insurance team."
      (is (= []
             (-> edit-request
                 (assoc-in [:session :session/member :member/member-id] outsider-id)
                 coverage-edit.views/page
                 page-shell/page-contract
                 :overflow))))))

(deftest long-insurance-trails-use-responsive-item-limits
  (let [{:keys [request policy-id coverage-id instrument-id]} (fixture)
        policy       (q/retrieve-policy (:db request) policy-id)
        policy-req   (assoc request :path-params {:policy-id policy-id})
        coverage-req (assoc request :path-params {:coverage-id coverage-id})]
    (is (= {:coverage-detail  [2 2]
            :coverage-edit    [2 3]
            :coverage-create  [2 3]
            :coverage-photos  [2 3]
            :coverage-final   [2 3]
            :review           [2 2]
            :workbench        [2 2]
            :settings         [2 2]
            :surveys          [2 2]
            :notifications    [2 2]
            :changes          [2 2]}
           {:coverage-detail (page-shell/breadcrumb-max-items
                              (coverage.views/page coverage-req))
            :coverage-edit   (page-shell/breadcrumb-max-items
                              (coverage-edit.views/page coverage-req))
            :coverage-create (page-shell/breadcrumb-max-items
                              (coverage-create.views/instrument-page
                               (assoc policy-req
                                      :query-params
                                      {:instrument-id (str instrument-id)})))
            :coverage-photos (page-shell/breadcrumb-max-items
                              (coverage-create.views/photos-page
                               (assoc request
                                      :path-params
                                      {:policy-id     policy-id
                                       :instrument-id instrument-id})))
            :coverage-final  (page-shell/breadcrumb-max-items
                              (coverage-create.views/coverage-page
                               (assoc request
                                      :path-params
                                      {:policy-id     policy-id
                                       :instrument-id instrument-id})))
            :review          (page-shell/breadcrumb-max-items
                              (review.views/page policy-req))
            :workbench       (page-shell/breadcrumb-max-items
                              (workbench.views/page policy-req))
            :settings        (page-shell/breadcrumb-max-items
                              (settings.views/page policy-req))
            :surveys         (page-shell/breadcrumb-max-items
                              (surveys.views/page policy-req))
            :notifications   (page-shell/breadcrumb-max-items
                              (policy-notifications.views/page
                               (assoc policy-req :policy policy)))
            :changes         (page-shell/breadcrumb-max-items
                              (policy-changes.views/page policy-req))}))))
