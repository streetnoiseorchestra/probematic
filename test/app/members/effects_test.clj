(ns app.members.effects-test
  (:require
   [app.members.effects :as effects]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d])
  (:import
   [java.util.concurrent CountDownLatch TimeUnit]))

(def issued-at #inst "2026-07-16T10:00:00.000-00:00")
(def expires-at #inst "2026-08-15T10:00:00.000-00:00")
(def pending :member.invite.status/pending)
(def accepting :member.invite.status/accepting)
(def revoked :member.invite.status/revoked)

(defn seed-member! [conn member-id]
  @(d/transact
    conn
    [{:member/member-id member-id
      :member/name "Alice Example"
      :member/email "alice@example.com"
      :member/username "alice.example"}]))

(defn seed-invitation!
  [conn member-id {:keys [code expiry status generation]}]
  @(d/transact
    conn
    [(cond-> {:member/member-id member-id
              :member/invite-status status
              :member/invite-generation generation
              :member/invite-status-at issued-at}
       code (assoc :member/invite-code code)
       expiry (assoc :member/invite-expires-at expiry))]))

(defn invitation [conn member-id]
  (let [member (d/entity (d/db conn) [:member/member-id member-id])
        status (:member/invite-status member)]
    {:status (if (keyword? status) status (:db/ident status))
     :generation (:member/invite-generation member)
     :code (:member/invite-code member)
     :expiry (:member/invite-expires-at member)}))

(defn request [conn]
  {:datomic-conn conn
   :system {:datomic {:conn conn}
            :redis :fake-redis
            :env {}
            :i18n-langs {}}})

(defn fake-deps [queued]
  {:now (constantly issued-at)
   :random-code (constantly "generated-code")
   :build-new-user-invite
   (fn [_email-system member code]
     {:member-id (:member/member-id member)
      :code code})
   :queue-email!
   (fn [_email-system message]
     (swap! queued conj message))})

(def member-invite-form
  {:name "Alice Example"
   :nick "alice"
   :email "alice@example.com"
   :username "alice.example"
   :phone "+43677123456"
   :section-name "Sopran"
   :active true
   :create-sno-id true})

(deftest invite-member-effect-runs-the-workflow-and-preserves-the-first-invitation-test
  (let [{:keys [conn] actor-member-id :member-id}
        (tc/new-system "member-invitation-create-effect")
        first-member-id (random-uuid)
        first-ledger-id (random-uuid)
        second-member-id (random-uuid)
        second-ledger-id (random-uuid)
        generated-ids_ (atom [first-member-id
                              first-ledger-id
                              second-member-id
                              second-ledger-id])
        generated-codes_ (atom ["original-code" "conflicting-code"])
        queued_ (atom [])
        invite-member! (ns-resolve 'app.members.effects 'invite-member!)
        deps (assoc
              (fake-deps queued_)
              :random-uuid
              (fn []
                (let [generated-id (first @generated-ids_)]
                  (swap! generated-ids_ subvec 1)
                  generated-id))
              :random-code
              (fn []
                (let [code (first @generated-codes_)]
                  (swap! generated-codes_ subvec 1)
                  code)))
        req (assoc-in (request conn)
                      [:session :session/member :member/member-id]
                      actor-member-id)]
    @(d/transact conn [{:section/name "Sopran"}])
    (is (some? invite-member!))
    (when invite-member!
      (let [first-result (invite-member! deps req member-invite-form)
            second-result (invite-member! deps req member-invite-form)
            db (d/db conn)
            member (d/entity db [:member/member-id first-member-id])
            status (:member/invite-status member)]
        (is (= {:first-status :created
                :second-status :conflict
                :created-member-id first-member-id
                :member-count 1
                :ledger-count 1
                :invitation-code "original-code"
                :invitation-generation 1
                :invitation-status pending
                :remaining-generated-ids []
                :queued [{:member-id first-member-id
                          :code "original-code"}]}
               {:first-status (:member-invite/persist-status first-result)
                :second-status (:member-invite/persist-status second-result)
                :created-member-id
                (get-in first-result
                        [:member-invite/member :member/member-id])
                :member-count
                (d/q '[:find (count ?member) .
                       :where [?member :member/email]]
                     db)
                :ledger-count
                (d/q '[:find (count ?ledger) .
                       :where [?ledger :ledger/ledger-id]]
                     db)
                :invitation-code (:member/invite-code member)
                :invitation-generation (:member/invite-generation member)
                :invitation-status
                (if (keyword? status) status (:db/ident status))
                :remaining-generated-ids @generated-ids_
                :queued @queued_}))))))

(deftest invite-member-effect-rejects-creation-without-an-invitation-test
  (let [{:keys [conn]} (tc/new-system "member-without-invitation-effect")
        queued_ (atom [])
        invite-member! (ns-resolve 'app.members.effects 'invite-member!)
        exception
        (when invite-member!
          (try
            (invite-member!
             (fake-deps queued_)
             (request conn)
             (assoc member-invite-form :create-sno-id false))
            nil
            (catch clojure.lang.ExceptionInfo exception
              exception)))]
    (is (some? invite-member!))
    (when invite-member!
      (is (= {:message "NOT YET IMPLEMENTED Member invitations require SNO ID creation"
              :data {:create-sno-id false}
              :member-count 0
              :ledger-count 0
              :queued []}
             {:message (ex-message exception)
              :data (ex-data exception)
              :member-count
              (or
               (d/q '[:find (count ?member) .
                      :where [?member :member/email]]
                    (d/db conn))
               0)
              :ledger-count
              (or
               (d/q '[:find (count ?ledger) .
                      :where [?ledger :ledger/ledger-id]]
                    (d/db conn))
               0)
              :queued @queued_})))))
(deftest resend-preserves-the-current-code-and-generation-test
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-resend")
        queued (atom [])]
    (seed-member! conn member-id)
    (seed-invitation! conn member-id
                      {:code "same-code"
                       :expiry expires-at
                       :status pending
                       :generation 7})
    (effects/resend-invitation!
     (assoc (fake-deps queued) :now (constantly issued-at))
     (request conn)
     "same-code")
    (is (= {:status pending
            :generation 7
            :code "same-code"
            :expiry expires-at}
           (invitation conn member-id)))
    (is (= [{:member-id member-id :code "same-code"}]
           @queued))))

(deftest reissue-rotates-the-bearer-and-queues-the-new-code-test
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-reissue-effect")
        queued (atom [])]
    (seed-member! conn member-id)
    (seed-invitation! conn member-id
                      {:code "old-code"
                       :expiry #inst "2026-07-16T09:59:59.000-00:00"
                       :status pending
                       :generation 3})
    (is (= "generated-code"
           (try
             (effects/reissue-invitation!
              (fake-deps queued)
              (request conn)
              "old-code")
             (catch Exception _
               ::threw))))
    (is (= {:status pending
            :generation 4
            :code "generated-code"
            :expiry expires-at}
           (invitation conn member-id)))
    (is (= [{:member-id member-id
             :code "generated-code"}]
           @queued))))

(deftest replayed-reissue-cannot-rotate-the-newer-bearer-test
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-reissue-replay")
        queued          (atom [])
        generated-codes (atom ["new-code" "replayed-code"])
        deps            (assoc (fake-deps queued)
                               :random-code
                               #(let [code (first @generated-codes)]
                                  (swap! generated-codes rest)
                                  code))
        reissue         #(try
                           (effects/reissue-invitation!
                            deps
                            (request conn)
                            %)
                           (catch Exception _
                             ::threw))]
    (seed-member! conn member-id)
    (seed-invitation! conn member-id
                      {:code "observed-code"
                       :expiry #inst "2026-07-16T09:59:59.000-00:00"
                       :status pending
                       :generation 3})
    (is (= ["new-code" nil]
           [(reissue "observed-code")
            (reissue "observed-code")]))
    (is (= {:status pending
            :generation 4
            :code "new-code"
            :expiry expires-at}
           (invitation conn member-id)))
    (is (= [{:member-id member-id
             :code "new-code"}]
           @queued))))

(deftest revoked-reissue-creates-a-new-bearer-and-queues-one-email-test
  (let [{:keys [conn member-id]} (tc/new-system
                                  "member-invitation-reissue-revoked-effect")
        queued (atom [])]
    (seed-member! conn member-id)
    (seed-invitation! conn member-id
                      {:status revoked
                       :generation 5})
    (if-let [reissue-revoked!
             (ns-resolve 'app.members.effects
                         'reissue-revoked-invitation!)]
      (do
        (is (= "generated-code"
               (reissue-revoked!
                (fake-deps queued)
                (request conn)
                member-id
                5)))
        (is (= {:status pending
                :generation 6
                :code "generated-code"
                :expiry expires-at}
               (invitation conn member-id)))
        (is (= [{:member-id member-id
                 :code "generated-code"}]
               @queued)))
      (is false "The revoked invitation reissue effect is not implemented"))))

(deftest revoked-reissue-stale-generation-is-a-no-op-test
  (let [{:keys [conn member-id]} (tc/new-system
                                  "member-invitation-reissue-revoked-stale")
        queued (atom [])]
    (seed-member! conn member-id)
    (seed-invitation! conn member-id
                      {:status revoked
                       :generation 7})
    (if-let [reissue-revoked!
             (ns-resolve 'app.members.effects
                         'reissue-revoked-invitation!)]
      (do
        (is (nil? (reissue-revoked!
                   (fake-deps queued)
                   (request conn)
                   member-id
                   6)))
        (is (= {:status revoked
                :generation 7
                :code nil
                :expiry nil}
               (invitation conn member-id)))
        (is (empty? @queued)))
      (is false "The revoked invitation reissue effect is not implemented"))))

(deftest reissue-emails-use-the-post-transition-member-profile-test
  (doseq [[case-name seed! reissue!]
          [["expired pending"
            #(seed-invitation! %1 %2
                               {:code "old-code"
                                :expiry #inst "2026-07-16T09:59:59.000-00:00"
                                :status pending
                                :generation 3})
            (fn [deps req _member-id]
              (effects/reissue-invitation! deps req "old-code"))]
           ["revoked"
            #(seed-invitation! %1 %2
                               {:status revoked
                                :generation 5})
            #(effects/reissue-revoked-invitation! %1 %2 %3 5)]]]
    (testing case-name
      (let [{:keys [conn member-id]}
            (tc/new-system (str "member-invitation-reissue-profile-"
                                (name (keyword case-name))))
            queued   (atom [])
            new-email (str member-id "@new.example.test")
            deps     (assoc
                      (fake-deps queued)
                      :random-code
                      (fn []
                        @(d/transact
                          conn
                          [[:db/add
                            [:member/member-id member-id]
                            :member/email
                            new-email]])
                        "generated-code")
                      :build-new-user-invite
                      (fn [_email-system member code]
                        {:email (:member/email member)
                         :code code}))]
        (seed-member! conn member-id)
        (seed! conn member-id)
        (is (= "generated-code"
               (reissue! deps (request conn) member-id)))
        (is (= [{:email new-email
                 :code "generated-code"}]
               @queued))))))

(deftest concurrent-revoked-reissue-has-one-transition-and-one-email-test
  (let [{:keys [conn member-id]} (tc/new-system
                                  "member-invitation-reissue-revoked-race")
        queued          (atom [])
        ready           (CountDownLatch. 2)
        release         (CountDownLatch. 1)
        code-sequence   (atom 0)
        deps            (assoc (fake-deps queued)
                               :random-code
                               (fn []
                                 (.countDown ready)
                                 (.await release 5 TimeUnit/SECONDS)
                                 (str "racing-code-" (swap! code-sequence inc))))]
    (seed-member! conn member-id)
    (seed-invitation! conn member-id
                      {:status revoked
                       :generation 9})
    (if-let [reissue-revoked!
             (ns-resolve 'app.members.effects
                         'reissue-revoked-invitation!)]
      (let [reissue #(try
                       (reissue-revoked!
                        deps
                        (request conn)
                        member-id
                        9)
                       (catch Throwable exception
                         exception))
            left    (future (reissue))
            right   (future (reissue))]
        (is (.await ready 5 TimeUnit/SECONDS))
        (.countDown release)
        (let [results [@left @right]
              state   (invitation conn member-id)]
          (is (= 1 (count (filter string? results))))
          (is (= 1 (count (filter nil? results))))
          (is (= pending (:status state)))
          (is (= 10 (:generation state)))
          (is (= expires-at (:expiry state)))
          (is (= [{:member-id member-id
                   :code (:code state)}]
                 @queued))))
      (is false "The revoked invitation reissue effect is not implemented"))))

(deftest reissue-refuses-a-current-unexpired-invitation-test
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-reissue-unexpired")
        queued  (atom [])
        reissue #(try
                   (effects/reissue-invitation!
                    (fake-deps queued)
                    (request conn)
                    %)
                   (catch Exception _
                     ::threw))]
    (seed-member! conn member-id)
    (seed-invitation! conn member-id
                      {:code "current-code"
                       :expiry expires-at
                       :status pending
                       :generation 8})
    (is (nil? (reissue "current-code")))
    (is (= {:status pending
            :generation 8
            :code "current-code"
            :expiry expires-at}
           (invitation conn member-id)))
    (is (empty? @queued))))

(deftest resend-refuses-expired-or-in-flight-invitations-test
  (doseq [[case-name state]
          [["expired" {:code "expired"
                       :expiry #inst "2026-07-16T09:59:59.000-00:00"
                       :status pending
                       :generation 1}]
           ["accepting" {:code "accepting"
                         :expiry expires-at
                         :status accepting
                         :generation 2}]]]
    (testing case-name
      (let [{:keys [conn member-id]} (tc/new-system
                                      (str "member-invitation-resend-" case-name))
            queued (atom [])]
        (seed-member! conn member-id)
        (seed-invitation! conn member-id state)
        (is (nil? (effects/resend-invitation!
                   (fake-deps queued)
                   (request conn)
                   (:code state))))
        (is (empty? @queued))))))

(deftest delete-invitation-revokes-only-matching-pending-state-test
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-delete")
        queued (atom [])]
    (seed-member! conn member-id)
    (seed-invitation! conn member-id
                      {:code "delete-code"
                       :expiry expires-at
                       :status pending
                       :generation 4})
    (is (= {:outcome :revoked :generation 5}
           (effects/delete-invitation!
            (fake-deps queued)
            (request conn)
            "delete-code")))
    (is (= {:status revoked
            :generation 5
            :code nil
            :expiry nil}
           (invitation conn member-id)))
    (is (nil? (effects/delete-invitation!
               (fake-deps queued)
               (request conn)
               "delete-code")))))
