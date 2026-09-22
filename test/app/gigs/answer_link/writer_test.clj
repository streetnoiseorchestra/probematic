(ns app.gigs.answer-link.writer-test
  (:require
   [app.game-loop :as game]
   [app.gigs.answer-link.actions :as actions]
   [app.gigs.answer-link.service :as service]
   [app.gigs.answer-link.service-test :as answers]
   [app.queries :as q]
   [app.test-common :as tc]
   [app.write-runner :as writer]
   [app.write-runner-test :as fixtures]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [s-exp.drip :as drip]
   [tick.core :as t])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(deftest answer-links-read-current-writer-state-and-commit-integration-intents
  (fixtures/with-runtime
    (fn [runtime client conn]
      (let [{:keys [actor-id gig-id member-id]}
            (writer/call!
             (:write-runner runtime)
             (fn []
               (let [ids      (answers/seed-gig-member! conn (t/>> (t/date) (t/new-period 7 :days)))
                     actor-id (random-uuid)]
                 @(d/transact conn [{:member/member-id actor-id}])
                 (assoc ids :actor-id actor-id))))
            req
            (-> (answers/req conn {:gig/gig-id       gig-id
                                   :member/member-id member-id
                                   :attendance/plan  :plan/definitely})
                (assoc-in [:app/session :session/member] {:member/member-id actor-id})
                (assoc-in [:env :ig/system :app.ig/profile] :prod)
                (assoc-in [:system :frame-loop] runtime))
            entered                             (promise)
            release                             (promise)]
        (writer/call! (:write-runner runtime)
                      #(deref (d/transact conn [{:db/id "new-section" :section/name "saxophones"}
                                                [:db/add [:member/member-id member-id] :member/section "new-section"]])))
        (try
          (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
                (fn [_] (deliver entered true) @release))
          (is (= true (deref entered 5000 ::timeout)))
          (let [result (future (service/submit-answer! req))]
            (is (= ::waiting (deref result 1000 ::waiting)))
            (is (nil? (q/attendance-for-gig (d/db conn) gig-id member-id)))
            (deliver release true)
            (is (= gig-id (get-in (deref result 5000 ::timeout) [:gig :gig/gig-id])))
            (let [db         (d/db conn)
                  attendance (d/entity db [:attendance/gig+member (q/gig+member gig-id member-id)])]
              (is (= "saxophones" (get-in attendance [:attendance/section :section/name])))
              (is (= :plan/definitely (:attendance/plan attendance)))
              (is (= #{[::actions/submit-attendance :app.origin/browser actor-id]}
                     (set (d/q '[:find ?action ?origin ?actor-id
                                 :in $ ?attendance
                                 :where
                                 [?attendance :attendance/plan _ ?tx]
                                 [?tx :audit/action ?action]
                                 [?tx :audit/origin ?origin]
                                 [?tx :audit/user ?actor]
                                 [?actor :member/member-id ?actor-id]]
                               db
                               (:db/id attendance))))))
            (writer/call! (:write-runner runtime) (constantly nil))
            (let [jobs (drip/list-jobs client {})]
              (is (= [{:gig-id gig-id :operation :updated}]
                     (mapv #(select-keys (:args %) [:gig-id :operation]) jobs)))
              (is (= (d/basis-t (d/db conn)) (get-in jobs [0 :args :source-t])))))
          (finally (deliver release true)))))))
