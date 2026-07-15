(ns app.nexus-test
  (:require
   [app.datastar :as datastar]
   [app.ig]
   [app.insurance.exporters :as exporters]
   [app.nexus :as app-nexus]
   [app.system]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]
   [integrant.core :as ig]
   [starfederation.datastar.clojure.adapter.http-kit :as hk-gen]
   [starfederation.datastar.clojure.adapter.test :as d*test]))

(defn realized-sse-response [response]
  (cond-> response
    (volatile? (:body response)) (update :body deref)))

(defn dispatch-actions
  [nexus-config system request actions errors_]
  (app-nexus/dispatch-actions
   nexus-config
   system
   {:request request
    :response actions}
   #(swap! errors_ conj %)))

(deftest nexus-integrant-component-builds-a-nexus-config
  (let [nexus-config (ig/init-key :app.ig/nexus {})]
    (is (= app-nexus/system->state
           (:nexus/system->state nexus-config)))
    (is (contains? (:nexus/actions nexus-config)
                   :app.settings.discounts.actions/create-discount-type))
    (is (contains? (:nexus/actions nexus-config)
                   :app.settings.teams.actions/create-team))
    (is (contains? (:nexus/actions nexus-config)
                   :app.settings.sections.actions/create-section))
    (is (contains? (:nexus/actions nexus-config)
                   :app.members.index.actions/set-search-phrase))
    (is (contains? (:nexus/actions nexus-config)
                   :app.songs.index.actions/set-search-phrase))
    (is (contains? (:nexus/actions nexus-config)
                   :app.songs.index.actions/set-repertoire-filter))
    (is (contains? (:nexus/actions nexus-config)
                   :app.songs.index.actions/force-sync-songs))
    (is (contains? (:nexus/actions nexus-config)
                   :app.members.invite.actions/submit-member-invite))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.edit.actions/update-gig))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.edit.actions/create-gig))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.edit.actions/delete-gig))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.detail.actions/update-attendance-plan))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.detail.actions/update-attendance-motivation))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.detail.actions/update-attendance-comment))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.detail.actions/switch-attendance-comment))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.detail.actions/toggle-attendance-committed))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.detail.actions/send-reminder-to-all))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.probeplan.actions/toggle-probeplan-song))
    (is (contains? (:nexus/actions nexus-config)
                   :app.probeplan.actions/open-edit))
    (is (contains? (:nexus/actions nexus-config)
                   :app.probeplan.actions/cancel-edit))
    (is (contains? (:nexus/actions nexus-config)
                   :app.probeplan.actions/save-probeplans))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.log-plays.actions/update-rating))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.log-plays.actions/toggle-intensive))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.actions/delete-policy))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.actions/duplicate-policy))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.coverage.edit.actions/update-instrument-coverage))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.coverage.edit.actions/delete-instrument-coverage))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.coverage.edit.actions/validate-coverage-field))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.coverage.create.actions/validate-instrument-field))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.coverage.create.actions/save-instrument-step))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.coverage.create.actions/validate-coverage-field))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.coverage.create.actions/create-coverage))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.survey.actions/transition))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.survey.actions/save-edit))
    (is (contains? (:nexus/actions nexus-config)
                   :app.insurance.survey.actions/dismiss-review))
    (is (contains? (:nexus/actions nexus-config)
                   :app.poll.edit.actions/create-poll))
    (is (contains? (:nexus/actions nexus-config)
                   :app.poll.edit.actions/update-poll))
    (is (contains? (:nexus/actions nexus-config)
                   :app.poll.edit.actions/delete-poll))
    (is (contains? (:nexus/actions nexus-config)
                   :app.poll.detail.actions/open-poll))
    (is (contains? (:nexus/actions nexus-config)
                   :app.poll.detail.actions/close-poll))
    (is (contains? (:nexus/actions nexus-config)
                   :app.poll.detail.actions/cast-vote))
    (is (every? (:nexus/actions nexus-config)
                #{:app.account.actions/validate-profile-field
                  :app.account.actions/stage-avatar
                  :app.account.actions/remove-avatar
                  :app.account.actions/save-profile
                  :app.account.actions/save-date-time-preferences
                  :app.account.actions/toggle-notifications
                  :app.account.actions/enable-browser-notifications
                  :app.account.actions/update-notification-settings
                  :app.account.actions/update-break-settings
                  :app.account.actions/end-break
                  :app.account.actions/launch-app}))
    (is (contains? (:nexus/effects nexus-config) :db/transact))
    (is (contains? (:nexus/effects nexus-config) :app.account/save-profile))
    (is (contains? (:nexus/effects nexus-config) :app.datastar/respond-sse))
    (is (not-any? #(contains? (:nexus/effects nexus-config) %)
                  #{:app.datastar/merge-signals
                    :app.datastar/remove-signals
                    :app.datastar/open-form
                    :app.datastar/close-form
                    :app.datastar/redirect}))
    (is (contains? (:nexus/effects nexus-config) :app.gigs/trigger-gig-details-edited))
    (is (contains? (:nexus/effects nexus-config) :app.gigs/trigger-gig-created))
    (is (contains? (:nexus/effects nexus-config) :app.gigs/trigger-gig-deleted))
    (is (contains? (:nexus/effects nexus-config) :app.gigs/trigger-gig-edited))
    (is (contains? (:nexus/effects nexus-config) :app.gigs/recalc-play-stats))
    (is (contains? (:nexus/effects nexus-config) :app.gigs/send-reminder-to-all))
    (is (contains? (:nexus/effects nexus-config) :app.songs/trigger-sync-all-songs))
    (is (contains? (:nexus/effects nexus-config) :app.members/send-user-invitation))
    (is (contains? (:nexus/effects nexus-config) :app.members/set-keycloak-account-enabled))
    (is (contains? (:nexus/effects nexus-config) :app.members.index/resend-invitation))
    (is (contains? (:nexus/effects nexus-config) :app.members.index/delete-invitation))
    (is (contains? (:nexus/effects nexus-config) :app.poll/send-poll-opened))))

(deftest dispatch-actions-finalizes-one-ordered-sse-response-test
  (let [{:keys [conn]} (tc/new-system "nexus-ordered-sse-response")
        system         {:datomic {:conn conn}}
        request        {:protocol "HTTP/1.1"}
        errors_        (atom [])
        events         [[:app.datastar.sse/merge-signals {:loading false}]
                        [:app.datastar.sse/redirect "/done"]]
        response       (with-redefs [hk-gen/->sse-response d*test/->sse-response]
                         (dispatch-actions
                          (app-nexus/nexus)
                          system
                          request
                          [[:app.datastar/respond-sse events]]
                          errors_))]
    (is (=
         {:errors []
          :response
          {:status 200
           :headers {"Cache-Control" "no-cache"
                     "Content-Type" "text/event-stream"}
           :body
           ["event: datastar-patch-signals\ndata: signals {\"loading\":false}\n\n"
            (str "event: datastar-patch-elements\n"
                 "data: selector body\n"
                 "data: mode append\n"
                 "data: elements <script data-effect=\"el.remove()\">"
                 "setTimeout(() => window.location.href =\"/done\")</script>\n\n")]}}
         {:errors @errors_
          :response (realized-sse-response response)}))))

(deftest dispatch-actions-preserves-an-ordinary-ring-response-test
  (let [{:keys [conn]} (tc/new-system "nexus-ring-response")
        errors_        (atom [])
        ring-response  {:status 303
                        :headers {"Location" "/done"}
                        :body ""}
        nexus-config   (assoc-in (app-nexus/nexus)
                                 [:nexus/effects :test/ring-response]
                                 (fn [_ _]
                                   ring-response))]
    (is (= {:errors []
            :response ring-response}
           {:errors @errors_
            :response (dispatch-actions
                       nexus-config
                       {:datomic {:conn conn}}
                       {:protocol "HTTP/1.1"}
                       [[:test/ring-response]]
                       errors_)}))))

(deftest dispatch-actions-rejects-multiple-sse-response-owners-test
  (let [{:keys [conn]} (tc/new-system "nexus-multiple-sse-responses")
        system         {:datomic {:conn conn}}
        request        {:protocol "HTTP/1.1"}
        errors_        (atom [])
        opened_        (atom 0)
        adapter        (fn [req opts]
                         (swap! opened_ inc)
                         (d*test/->sse-response req opts))
        response       (with-redefs [hk-gen/->sse-response adapter]
                         (dispatch-actions
                          (app-nexus/nexus)
                          system
                          request
                          [[:app.datastar/respond-sse
                            [[:app.datastar.sse/merge-signals {:first true}]]]
                           [:app.datastar/respond-sse
                            [[:app.datastar.sse/merge-signals {:second true}]]]]
                          errors_))
        error          (first @errors_)]
    (is (= {:status 204
            :opened 0
            :error-count 1
            :error-message "One Nexus dispatch may contain at most one response owner"
            :error-data {:response-count 2}}
           {:status (:status response)
            :opened @opened_
            :error-count (count @errors_)
            :error-message (ex-message error)
            :error-data (ex-data error)}))))

(deftest nested-and-outer-response-owners-are-rejected-test
  (let [{:keys [conn]} (tc/new-system "nexus-nested-response-conflict")
        system         {:datomic {:conn conn}}
        request        {:protocol "HTTP/1.1"}
        errors_        (atom [])
        opened_        (atom 0)
        team-id        (random-uuid)
        response       (with-redefs [hk-gen/->sse-response
                                     (fn [req opts]
                                       (swap! opened_ inc)
                                       (d*test/->sse-response req opts))]
                         (dispatch-actions
                          (app-nexus/nexus)
                          system
                          request
                          [[:db/transact
                            [{:team/team-id team-id
                              :team/name "Nested owner"}]
                            {:on-success
                             [[:app.datastar/respond-sse
                               [[:app.datastar.sse/merge-signals
                                 {:nested true}]]]]}]
                           [:app.datastar/respond-sse
                            [[:app.datastar.sse/merge-signals
                              {:outer true}]]]]
                          errors_))
        error          (first @errors_)]
    (is (= {:status 204
            :opened 0
            :team-name "Nested owner"
            :error-count 1
            :error-message "One Nexus dispatch may contain at most one response owner"
            :error-data {:response-count 2}}
           {:status (:status response)
            :opened @opened_
            :team-name (:team/name
                        (d/entity (d/db conn) [:team/team-id team-id]))
            :error-count (count @errors_)
            :error-message (ex-message error)
            :error-data (ex-data error)}))))

(deftest failed-server-effect-cannot-finalize-a-success-response-test
  (let [{:keys [conn]} (tc/new-system "nexus-fail-fast-response")
        ran_           (atom [])
        opened_        (atom 0)
        errors_        (atom [])
        fail-fx        (fn [_ _]
                         (throw (ex-info "server effect failed" {:effect :test/fail})))
        after-fx       (fn [_ _]
                         (swap! ran_ conj :after))
        nexus-config   (-> (app-nexus/nexus)
                           (assoc-in [:nexus/effects :test/fail] fail-fx)
                           (assoc-in [:nexus/effects :test/after] after-fx))
        response       (with-redefs [hk-gen/->sse-response
                                     (fn [req opts]
                                       (swap! opened_ inc)
                                       (d*test/->sse-response req opts))]
                         (dispatch-actions
                          nexus-config
                          {:datomic {:conn conn}}
                          {:protocol "HTTP/1.1"}
                          [[:test/fail]
                           [:test/after]
                           [:app.datastar/respond-sse
                            [[:app.datastar.sse/redirect "/should-not-run"]]]]
                          errors_))]
    (is (= {:status 204
            :ran []
            :opened 0
            :error-count 1
            :error-message "server effect failed"}
           {:status (:status response)
            :ran @ran_
            :opened @opened_
            :error-count (count @errors_)
            :error-message (some-> @errors_ first ex-message)}))))

(deftest transaction-success-response-plan-reaches-the-http-boundary-test
  (let [{:keys [conn]} (tc/new-system "nexus-transaction-success-response")
        system         {:datomic {:conn conn}}
        request        {:protocol "HTTP/1.1"}
        errors_        (atom [])
        team-id        (random-uuid)
        response       (with-redefs [hk-gen/->sse-response d*test/->sse-response]
                         (dispatch-actions
                          (app-nexus/nexus)
                          system
                          request
                          [[:db/transact
                            [{:team/team-id team-id
                              :team/name "Nested response"}]
                            {:on-success
                             [[:app.datastar/respond-sse
                               [[:app.datastar.sse/merge-signals
                                 {:transaction "saved"}]]]]}]]
                          errors_))]
    (is (= {:errors []
            :team-name "Nested response"
            :response-events
            ["event: datastar-patch-signals\ndata: signals {\"transaction\":\"saved\"}\n\n"]}
           {:errors @errors_
            :team-name (:team/name (d/entity (d/db conn) [:team/team-id team-id]))
            :response-events (:body (realized-sse-response response))}))))

(deftest transaction-error-response-plan-reaches-the-http-boundary-test
  (let [{:keys [conn]} (tc/new-system "nexus-transaction-error-response")
        system         {:datomic {:conn conn}}
        request        {:protocol "HTTP/1.1"}
        errors_        (atom [])
        now            #inst "2026-03-20T12:00:00.000-00:00"
        active-id      (random-uuid)
        blocked-id     (random-uuid)
        _ @(d/transact
            conn
            [{:insurance.survey/survey-id active-id
              :insurance.survey/created-at now
              :insurance.survey/closes-at
              #inst "2026-04-20T12:00:00.000-00:00"}])
        response       (with-redefs [hk-gen/->sse-response d*test/->sse-response]
                         (dispatch-actions
                          (app-nexus/nexus)
                          system
                          request
                          [[:db/transact
                            [[:insurance.survey/activate
                              now
                              {:insurance.survey/survey-id blocked-id
                               :insurance.survey/created-at now
                               :insurance.survey/closes-at
                               #inst "2026-04-20T12:00:00.000-00:00"}]]
                            {:on-error
                             {:insurance.survey.error/active-exists
                              [[:app.datastar/respond-sse
                                [[:app.datastar.sse/merge-signals
                                  {:transaction "conflict"}]]]]}}]]
                          errors_))]
    (is (= {:errors []
            :blocked-survey nil
            :response-events
            ["event: datastar-patch-signals\ndata: signals {\"transaction\":\"conflict\"}\n\n"]}
           {:errors @errors_
            :blocked-survey (d/entid (d/db conn)
                                     [:insurance.survey/survey-id blocked-id])
            :response-events (:body (realized-sse-response response))}))))

(deftest custom-effect-nested-response-plan-reaches-the-http-boundary-test
  (let [{:keys [conn]} (tc/new-system "nexus-custom-effect-response")
        policy-id      (random-uuid)
        _              @(d/transact
                         conn
                         [{:insurance.policy/policy-id       policy-id
                           :insurance.policy/name            "Nested response policy"
                           :insurance.policy/status          :insurance.policy.status/draft
                           :insurance.policy/currency        :currency/EUR
                           :insurance.policy/effective-at    #inst "2026-01-01T00:00:00.000-00:00"
                           :insurance.policy/effective-until #inst "2026-12-31T00:00:00.000-00:00"
                           :insurance.policy/premium-factor  0.01M}])
        sent_          (atom nil)
        errors_        (atom [])
        response       (with-redefs [exporters/send-email!
                                     (fn [& args]
                                       (reset! sent_ args))
                                     hk-gen/->sse-response d*test/->sse-response]
                         (dispatch-actions
                          (app-nexus/nexus)
                          {:datomic {:conn conn}
                           :env {:smtp-sno {:from "insurance@example.test"}}}
                          {:protocol "HTTP/1.1"
                           :db (d/db conn)}
                          [[:app.insurance/send-policy-changes
                            {:policy-id policy-id
                             :recipient "recipient@example.test"
                             :subject "Policy changes"
                             :body "Attached"
                             :attachment-filename-new "new.xlsx"
                             :attachment-filename-changes "changes.xlsx"
                             :on-success
                             [[:app.datastar/respond-sse
                               [[:app.datastar.sse/merge-signals
                                 {:delivery "sent"}]]]]}]]
                          errors_))]
    (is (= {:errors []
            :email-sent? true
            :response-events
            ["event: datastar-patch-signals\ndata: signals {\"delivery\":\"sent\"}\n\n"]}
           {:errors @errors_
            :email-sent? (some? @sent_)
            :response-events (:body (realized-sse-response response))}))))

(deftest system-config-wires-nexus-into-the-handler-system
  (let [cfg (app.system/system-config {:profile :test})]
    (is (= (ig/ref :app.ig/nexus)
           (get-in cfg [:app.ig/handler :nexus])))))

(deftest system->state-includes-current-member-id-and-roles-from-request
  (let [{:keys [conn]} (tc/new-system "nexus-state")
        member-id      (random-uuid)
        env            {:app-base-url "https://example.test"}
        state          (app-nexus/system->state
                        {:system  {:datomic {:conn conn}
                                   :env     env}
                         :request {:session {:session/member {:member/member-id member-id}
                                             :session/roles  #{:admin}}}})]
    (is (= member-id (:current-member-id state)))
    (is (= #{:admin} (:current-user-roles state)))
    (is (= env (:env state)))))

(deftest browser-tab-id-signal-addresses-page-state
  (let [{:keys [conn]} (tc/new-system "nexus-browser-tab-id")
        tab-id         (str (random-uuid))
        request        {:body-params {:tab-id tab-id}}]
    (try
      (swap! datastar/!page-state assoc tab-id {:existing :value})
      (app-nexus/assoc-page-state-fx nil
                                     {:request request}
                                     [:team-create]
                                     {:open true})
      (is (= {:open true}
             (get-in @datastar/!page-state [tab-id :team-create])))
      (is (= :value
             (get-in (app-nexus/system->state
                      {:system  {:datomic {:conn conn}}
                       :request request})
                     [:page-state :existing])))
      (finally
        (swap! datastar/!page-state dissoc tab-id)))))

(deftest multipart-tab-id-signal-addresses-page-state
  (let [{:keys [conn]} (tc/new-system "nexus-multipart-tab-id")
        tab-id         (str (random-uuid))
        request        {:parameters {:multipart {:tab-id tab-id}}}]
    (try
      (swap! datastar/!page-state assoc tab-id {:existing :value})
      (app-nexus/assoc-page-state-fx nil
                                     {:request request}
                                     [:account-profile :_saved?]
                                     true)
      (is (true?
           (get-in @datastar/!page-state
                   [tab-id :account-profile :_saved?])))
      (is (= :value
             (get-in (app-nexus/system->state
                      {:system {:datomic {:conn conn}}
                       :request request})
                     [:page-state :existing])))
      (finally
        (swap! datastar/!page-state dissoc tab-id)))))

(deftest db-transact-fx-dispatches-on-success-actions
  (let [{:keys [conn]} (tc/new-system "nexus-db-transact-on-success")
        team-id        (random-uuid)
        dispatched_    (atom nil)
        response       {:status 200 :headers {} :body "patched"}
        result         (app-nexus/db-transact-fx
                        {:dispatch (fn [actions dispatch-data]
                                     (reset! dispatched_ [actions dispatch-data])
                                     {:results [{:res response}]})}
                        {:system {:datomic {:conn conn}}}
                        [[[{:team/team-id team-id
                            :team/name    "On Success Test"}]
                          {:on-success [[:test/on-success team-id]]}]])]
    (is (= response result))
    (is (= [[:test/on-success team-id]]
           (first @dispatched_)))
    (is (some? (-> @dispatched_ second :tx-result :db-after)))))

(deftest db-transact-fx-dispatches-matching-on-error-actions
  (let [{:keys [conn]} (tc/new-system "nexus-db-transact-on-error")
        now             #inst "2026-03-20T12:00:00.000-00:00"
        active-id       (random-uuid)
        blocked-id      (random-uuid)
        response        {:status 200 :headers {} :body "conflict patched"}
        dispatched_     (atom nil)
        _ @(d/transact
            conn
            [{:insurance.survey/survey-id active-id
              :insurance.survey/created-at now
              :insurance.survey/closes-at
              #inst "2026-04-20T12:00:00.000-00:00"}])
        tx-data [[:insurance.survey/activate
                  now
                  {:insurance.survey/survey-id blocked-id
                   :insurance.survey/created-at now
                   :insurance.survey/closes-at
                   #inst "2026-04-20T12:00:00.000-00:00"}]]
        opts {:on-error
              {:insurance.survey.error/active-exists
               [[:test/on-error blocked-id]]}}
        result (app-nexus/db-transact-fx
                {:dispatch
                 (fn [actions dispatch-data]
                   (reset! dispatched_ [actions dispatch-data])
                   {:results [{:res response}]})}
                {:system {:datomic {:conn conn}}}
                [[tx-data opts]])]
    (is (= response result))
    (is (= [[:test/on-error blocked-id]]
           (first @dispatched_)))
    (is (= :insurance.survey.error/active-exists
           (some-> @dispatched_ second :tx-error ex-data :app/error-code)))
    (is (nil? (d/entid (d/db conn)
                       [:insurance.survey/survey-id blocked-id])))))

(deftest batch-transactions-replaces-generated-values
  (let [[tx] (app-nexus/batch-transactions
              [[[{:plain-a :db/gen-uuid
                  :plain-b :db/gen-uuid
                  :named-a [:db/gen-uuid :shared-id]
                  :named-b [:db/gen-uuid :shared-id]
                  :named-c [:db/gen-uuid :other-id]
                  :now-a   :db/now
                  :now-b   :db/now}]
                {}]]
              #{})]
    (is (uuid? (:plain-a tx)))
    (is (uuid? (:plain-b tx)))
    (is (not= (:plain-a tx) (:plain-b tx)))
    (is (uuid? (:named-a tx)))
    (is (= (:named-a tx) (:named-b tx)))
    (is (not= (:named-a tx) (:named-c tx)))
    (is (instance? java.util.Date (:now-a tx)))
    (is (= (:now-a tx) (:now-b tx)))))
