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

(deftest send-user-invitation-issues-datomic-state-before-queueing-test
  (let [{:keys [conn member-id]} (tc/new-system "member-invitation-send")
        queued (atom [])]
    (seed-member! conn member-id)
    (is (= "generated-code"
           (effects/send-user-invitation!
            (fake-deps queued)
            (request conn)
            member-id)))
    (is (= {:status pending
            :generation 1
            :code "generated-code"
            :expiry expires-at}
           (invitation conn member-id)))
    (is (= [{:member-id member-id
             :code "generated-code"}]
           @queued))))

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
