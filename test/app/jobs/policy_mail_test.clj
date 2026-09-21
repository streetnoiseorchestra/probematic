(ns app.jobs.policy-mail-test
  (:require [app.game-loop :as game]
            [app.insurance.exporters :as exporters]
            [app.insurance.test-support :as insurance-test]
            [app.jobs.policy-mail :as mail]
            [app.test-common :as tc]
            [app.write-runner :as writer]
            [app.write-runner-test :as runner-test]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.api :as d])
  (:import (java.util.concurrent ConcurrentHashMap)))

(use-fixtures :each tc/with-released-test-connections)

(deftest delivery-precedes-confirmation-and-a-committed-receipt-prevents-resending
  (runner-test/with-runtime
    (fn [runtime _ conn]
      (let [policy-id  (random-uuid)
            actor-id   (random-uuid)
            control    (:write-runner runtime)
            system     {:frame-loop runtime :datomic {:conn conn} :env {:smtp-sno {}}}
            args       (writer/call! control
                                     (fn []
                                       (insurance-test/seed-policy! conn policy-id)
                                       @(d/transact conn [{:member/member-id actor-id}])
                                       {:effect-id (random-uuid)
                                        :origin    {:member-id actor-id}
                                        :source-t  (d/basis-t (d/db conn))
                                        :mail      {:policy-id                   policy-id     :recipient "insurance@example.test"
                                                    :subject                     "Changes"     :body      "Attached"
                                                    :attachment-filename-new     "new.xls"
                                                    :attachment-filename-changes "changes.xls"}}))
            receipt    [:app.external-effect/id (:effect-id args)]
            policy-ref [:insurance.policy/policy-id policy-id]
            failure    (ex-info "SMTP rejected the message" {})
            entered    (promise)
            release    (promise)
            sent       (promise)
            done       (promise)
            calls      (atom 0)]
        (with-redefs [exporters/send-email! (fn [& _] (throw failure))]
          (is (identical? failure (try (mail/deliver! system args) (catch Exception e e)))))
        (is (nil? (d/entid (d/db conn) receipt)))
        (is (= :insurance.policy.status/draft (:insurance.policy/status (d/entity (d/db conn) policy-ref))))
        (let [closed (writer/create)]
          (writer/close! closed)
          (with-redefs [exporters/send-email! (fn [& _] (swap! calls inc))]
            (is (thrown-with-msg? Exception #"stopped"
                                  (mail/deliver! (assoc-in system [:frame-loop :write-runner] closed) args))))
          (is (= 1 @calls))
          (is (nil? (d/entid (d/db conn) receipt)))
          (is (= :insurance.policy.status/draft (:insurance.policy/status (d/entity (d/db conn) policy-ref)))))
        (writer/call! control #(deref (d/transact conn [[:db/add policy-ref :insurance.policy/name "Later name"]])))
        (try
          (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                (fn [_] (deliver entered true) @release))
          (is (= true (deref entered 5000 ::timeout)))
          (with-redefs [exporters/send-email!
                        (fn [policy & _]
                          (swap! calls inc)
                          (deliver sent [(:insurance.policy/name policy) (Thread/currentThread)]))]
            (future (deliver done (try (mail/deliver! system args) :done (catch Throwable e e))))
            (let [delivery (deref sent 5000 ::timeout)]
              (is (vector? delivery))
              (when (vector? delivery)
                (is (= "Insurance 2026" (first delivery)))
                (is (not (identical? (::game/thread runtime) (second delivery))))))
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
            (is (= :insurance.policy.status/active (:insurance.policy/status (d/entity (d/db conn) policy-ref))))
            (is (= "Later name" (:insurance.policy/name (d/entity (d/db conn) policy-ref))))
            (mail/deliver! system args)
            (is (= 2 @calls)))
          (finally (deliver release true)))))))

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
