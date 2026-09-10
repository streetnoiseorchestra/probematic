(ns app.members.invite.durable-mail-test
  (:require
   [app.email.mailers :as mailers]
   [app.i18n :as i18n]
   [app.members.effects :as effects]
   [app.members.effects-test :as invitations]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]))

(use-fixtures :each tc/with-released-test-connections)

(deftest invitation-and-reissue-commit-mail-intents-without-copying-the-bearer-into-job-arguments
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [queued      (atom [])
            deps        (assoc (invitations/fake-deps queued) :random-code (constantly "original-test-bearer"))
            req         (assoc-in (invitations/request conn) [:system :frame-loop] runtime)
            mail-system {:datomic {:conn conn}                           :i18n-langs (i18n/read-langs)
                         :env     {:app-base-url "https://example.test"}}]
        (writer/call! (:write-runner runtime)
                      #(deref (d/transact conn [{:section/name "Sopran" :section/active? true :section/position 1}])))
        (let [created   (effects/invite-member! deps req invitations/member-invite-form)
              member-id (get-in created [:member-invite/member :member/member-id])]
          (is (= :created (:member-invite/persist-status created)))
          (is (true? (:member-invite/email-queued? created)))
          (is (map? (effects/resend-invitation! deps req "original-test-bearer")))
          (is (= "replacement-test-bearer"
                 (effects/reissue-invitation! (assoc deps :now (constantly #inst "2026-08-16T00:00:00Z")
                                                     :random-code (constantly "replacement-test-bearer"))
                                              req "original-test-bearer")))
          (is (nil? (effects/resend-invitation! deps req "original-test-bearer")))
          (is (= :conflict (:member-invite/persist-status (effects/invite-member! deps req invitations/member-invite-form))))
          (writer/call! (:write-runner runtime) (constantly nil))
          (let [jobs (sort-by #(get-in % [:args :source-t]) (drip/list-jobs client {}))]
            (is (= 3 (count jobs)))
            (is (empty? @queued))
            (doseq [[job bearer] (map vector jobs ["original-test-bearer" "original-test-bearer" "replacement-test-bearer"])]
              (let [invocation (:args job)
                    source     (d/entity (d/db conn) (d/t->tx (:source-t invocation)))
                    message    (mailers/prepare! mail-system invocation)]
                (is (= ::mailers/member-invitation (:mailer invocation)))
                (is (= {:member-id member-id} (:arguments invocation)))
                (is (not (str/includes? (:audit/jobs source) bearer)))
                (is (= ["alice@example.com"] (get-in message [:email/messages 0 :to])))
                (is (str/includes? (get-in message [:email/messages 0 :text]) bearer))
                (is (= (:email-id invocation) (:email/email-id message)))))))))))
