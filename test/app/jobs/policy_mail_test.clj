(ns app.jobs.policy-mail-test
  (:require [app.game-loop :as game]
            [app.email.email-worker :as email-worker]
            [app.email.lettermint :as lettermint]
            [app.insurance.exporters :as exporters]
            [app.insurance.test-support :as insurance-test]
            [app.jobs.feedback :as feedback]
            [app.jobs.policy-mail :as mail]
            [app.test-common :as tc]
            [app.write-runner :as writer]
            [app.write-runner-test :as runner-test]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.api :as d]
            [s-exp.drip :as drip])
  (:import (java.util.concurrent ConcurrentHashMap)))

(use-fixtures :each tc/with-released-test-connections)

(deftest delivery-precedes-confirmation-and-a-committed-receipt-prevents-resending
  (runner-test/with-runtime
    (fn [runtime _ conn]
      (let [policy-id  (random-uuid)
            actor-id   (random-uuid)
            control    (:write-runner runtime)
            system     {:frame-loop runtime
                        :datomic    {:conn conn}
                        :env        {:insurance {:email-reply-to "insurance@example.test"}}
                        :lettermint {:from                    "sender@example.test"
                                     :project-api-token       "policy-mail-test-token"
                                     :testing-addresses-only? false
                                     :timeout-ms              2000}}
            args       (writer/call!
                        control
                        (fn []
                          (insurance-test/seed-policy! conn policy-id)
                          @(d/transact conn [{:member/member-id actor-id}])
                          {:effect-id (random-uuid)
                           :origin    {:member-id actor-id}
                           :source-t  (d/basis-t (d/db conn))
                           :mail      {:policy-id                   policy-id
                                       :recipient                   "Insurance Team <insurance@example.test>"
                                       :subject                     "Changes"
                                       :body                        "Attached"
                                       :attachment-filename-new     "new.xls"
                                       :attachment-filename-changes "changes.xls"}}))
            receipt    [:app.external-effect/id (:effect-id args)]
            policy-ref [:insurance.policy/policy-id policy-id]
            entered    (promise)
            release    (promise)
            done       (promise)
            sent       (promise)
            response   (atom {:error :provider :retry? true})
            snapshots  (atom [])
            deliveries (atom [])]
        (with-redefs [exporters/generate-attachments!
                      (fn [policy filename-new filename-changes]
                        (swap! snapshots conj (:insurance.policy/name policy))
                        [{:filename     filename-new
                          :content-type "application/vnd.ms-excel"
                          :content      (byte-array [0 1 -1 127])}
                         {:filename     filename-changes
                          :content-type "application/vnd.ms-excel"
                          :content      (byte-array [2 3])}])
                      lettermint/send-email!
                      (fn [_ message options]
                        (let [current (swap! deliveries conj
                                             {:message message
                                              :options options
                                              :thread  (Thread/currentThread)})]
                          (when (= 3 (count current))
                            (deliver sent true)))
                        @response)]
          (let [failure (try
                          (mail/deliver! system args)
                          nil
                          (catch Exception error error))]
            (is (= true (:retry? (ex-data failure)))))
          (is (nil? (d/entid (d/db conn) receipt)))
          (is (= :insurance.policy.status/draft
                 (:insurance.policy/status (d/entity (d/db conn) policy-ref))))
          (reset! response {:result     :email-sent
                            :message-id "policy-message"
                            :status     :queued})
          (let [closed (writer/create)]
            (writer/close! closed)
            (is (thrown-with-msg? Exception #"stopped"
                                  (mail/deliver!
                                   (assoc-in system [:frame-loop :write-runner] closed)
                                   args)))
            (is (= 2 (count @deliveries)))
            (is (nil? (d/entid (d/db conn) receipt)))
            (is (= :insurance.policy.status/draft
                   (:insurance.policy/status (d/entity (d/db conn) policy-ref)))))
          (writer/call!
           control
           #(deref (d/transact conn
                               [[:db/add policy-ref
                                 :insurance.policy/name
                                 "Later name"]])))
          (try
            (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                  (fn [_] (deliver entered true) @release))
            (is (= true (deref entered 5000 ::timeout)))
            (future
              (deliver done
                       (try
                         (mail/deliver! system args)
                         :done
                         (catch Throwable error error))))
            (is (= true (deref sent 5000 ::timeout)))
            (is (= 3 (count @deliveries)))
            (let [{:keys [message options thread]} (last @deliveries)]
              (is (= {:from     "sender@example.test"
                      :reply-to ["insurance@example.test"]
                      :to       ["insurance@example.test"]
                      :subject  "Changes"
                      :text     "Attached"
                      :attachments
                      [{:filename     "new.xls"
                        :content-type "application/vnd.ms-excel"
                        :content      "AAH/fw=="}
                       {:filename     "changes.xls"
                        :content-type "application/vnd.ms-excel"
                        :content      "AgM="}]}
                     message))
              (is (= {:idempotency-key (str (:effect-id args))} options))
              (is (not (identical? (::game/thread runtime) thread))))
            (is (= ["Insurance 2026" "Insurance 2026" "Insurance 2026"]
                   @snapshots))
            (is (nil? (d/entid (d/db conn) receipt)))
            (is (= ::waiting (deref done 50 ::waiting)))
            (deliver release true)
            (is (= :done (deref done 5000 ::timeout)))
            (is (some? (d/entid (d/db conn) receipt)))
            (is (= {:audit/action ::mail/confirm-delivered
                    :audit/origin :app.origin/job
                    :audit/user   {:member/member-id actor-id}}
                   (d/q '[:find (pull ?tx [:audit/action
                                           :audit/origin
                                           {:audit/user [:member/member-id]}]) .
                          :in $ ?effect-id
                          :where [?effect :app.external-effect/id ?effect-id ?tx]]
                        (d/db conn)
                        (:effect-id args))))
            (is (= :insurance.policy.status/active
                   (:insurance.policy/status (d/entity (d/db conn) policy-ref))))
            (is (= "Later name"
                   (:insurance.policy/name (d/entity (d/db conn) policy-ref))))
            (mail/deliver! system args)
            (is (= 3 (count @deliveries)))
            (finally (deliver release true))))))))

(deftest policy-job-freezes-the-first-accepted-payload-before-confirmation
  (runner-test/with-runtime
    (fn [runtime _ conn]
      (let [policy-id   (random-uuid)
            control     (:write-runner runtime)
            system      {:frame-loop runtime
                         :datomic    {:conn conn}
                         :env        {:insurance {:email-reply-to "insurance@example.test"}}
                         :lettermint {:from  "frozen@example.test"
                                      :route "policy"}}
            args        (writer/call!
                         control
                         (fn []
                           (insurance-test/seed-policy! conn policy-id)
                           {:effect-id (random-uuid)
                            :origin    {}
                            :source-t  (d/basis-t (d/db conn))
                            :mail      {:policy-id                   policy-id
                                        :recipient                   "insurance@example.test"
                                        :subject                     "Changes"
                                        :body                        "Attached"
                                        :attachment-filename-new     "new.xls"
                                        :attachment-filename-changes "changes.xls"}}))
            job-id      (random-uuid)
            stored      (atom nil)
            deliveries  (atom [])
            completions (atom [])
            discards    (atom [])
            response    (atom {:error :provider :retry? false})
            closed      (writer/create)
            generate    (fn [_ filename-new filename-changes]
                          [{:filename     filename-new
                            :content-type "application/vnd.ms-excel"
                            :content      (byte-array [1 2 3])}
                           {:filename     filename-changes
                            :content-type "application/vnd.ms-excel"
                            :content      (byte-array [4 5 6])}])]
        (writer/close! closed)
        (with-redefs [exporters/generate-attachments! generate
                      email-worker/lettermint-handler
                      (fn [_ email]
                        (is (some? @stored))
                        (swap! deliveries conj email)
                        @response)
                      feedback/failure!               (fn [& _])
                      feedback/redirect!              (fn [& _])
                      drip/update-job                 (fn [_ _ changes]
                                                        (reset! stored (:metadata changes)))
                      drip/complete-job               (fn [_ id]
                                                        (swap! completions conj id))
                      drip/discard-job                (fn [_ id]
                                                        (swap! discards conj id))]
          (let [permanent-id   (random-uuid)
                permanent-args (assoc args :effect-id (random-uuid))]
            (mail/handle! system
                          ::client
                          {:id permanent-id :args permanent-args :attempt 1})
            (is (= [permanent-id] @discards))
            (is (nil? (d/entid
                       (d/db conn)
                       [:app.external-effect/id (:effect-id permanent-args)]))))
          (reset! response {:result :email-sent})
          (is (thrown-with-msg?
               Exception
               #"stopped"
               (mail/handle!
                (assoc-in system [:frame-loop :write-runner] closed)
                ::client
                {:id job-id :args args :attempt 1 :metadata nil})))
          (is (nil? (d/entid (d/db conn)
                             [:app.external-effect/id (:effect-id args)])))
          (let [prepared (:email/prepared @stored)]
            (is (= (:effect-id args) (:email/email-id prepared)))
            (is (= {:from     "frozen@example.test"
                    :reply-to ["insurance@example.test"]
                    :route    "policy"}
                   (select-keys (first (:email/messages prepared))
                                [:from :reply-to :route])))
            (is (= ["AQID" "BAUG"]
                   (mapv :content
                         (get-in prepared [:email/messages 0 :attachments]))))
            (with-redefs [exporters/generate-attachments!
                          (fn [& _]
                            (throw (ex-info "payload was regenerated" {})))]
              (mail/handle! (-> system
                                (assoc :lettermint
                                       {:from  "changed@example.test"
                                        :route "changed"})
                                (assoc-in [:env :insurance :email-reply-to]
                                          "changed-reply@example.test"))
                            ::client
                            {:id       job-id
                             :args     args
                             :attempt  2
                             :metadata @stored})))
          (is (= [job-id] @completions))
          (is (= 3 (count @deliveries)))
          (is (= (second @deliveries) (last @deliveries)))
          (is (some? (d/entid (d/db conn)
                              [:app.external-effect/id (:effect-id args)]))))))))

(deftest later-policy-edits-are-not-confirmed-as-part-of-an-older-email
  (let [policy-id  (random-uuid)
        kept-id    (random-uuid)
        changed-id (random-uuid)
        new-id     (random-uuid)
        kept       {:instrument.coverage/coverage-id kept-id :instrument.coverage/change :instrument.coverage.change/new}
        changed    {:instrument.coverage/coverage-id changed-id :instrument.coverage/change :instrument.coverage.change/new}
        sent       {:insurance.policy/policy-id           policy-id      :insurance.policy/status :insurance.policy.status/draft
                    :insurance.policy/covered-instruments [kept changed]}
        current    (assoc sent :insurance.policy/covered-instruments
                          [kept (assoc changed :instrument.coverage/value 100M)
                           {:instrument.coverage/coverage-id new-id}])]
    (is (= [{:insurance.policy/policy-id policy-id :insurance.policy/status :insurance.policy.status/active}
            [:db/add [:instrument.coverage/coverage-id kept-id] :instrument.coverage/status :instrument.coverage.status/coverage-active]
            [:db/add [:instrument.coverage/coverage-id kept-id] :instrument.coverage/change :instrument.coverage.change/none]]
           (vec (mail/confirmation-tx sent current))))
    (is (empty? (mail/confirmation-tx sent nil)))
    (is (empty? (mail/confirmation-tx sent (assoc current :insurance.policy/status :insurance.policy.status/sent))))))
