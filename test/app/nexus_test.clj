(ns app.nexus-test
  (:require
   [app.ig]
   [app.nexus :as app-nexus]
   [app.system]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is]]
   [integrant.core :as ig]))

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
                   :app.members.invite.actions/submit-member-invite))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.edit.actions/update-gig))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.edit.actions/create-gig))
    (is (contains? (:nexus/actions nexus-config)
                   :app.gigs.edit.actions/delete-gig))
    (is (contains? (:nexus/effects nexus-config) :db/transact))
    (is (contains? (:nexus/effects nexus-config) :app.datastar/redirect))
    (is (contains? (:nexus/effects nexus-config) :app.gigs/trigger-gig-details-edited))
    (is (contains? (:nexus/effects nexus-config) :app.gigs/trigger-gig-created))
    (is (contains? (:nexus/effects nexus-config) :app.gigs/trigger-gig-deleted))
    (is (contains? (:nexus/effects nexus-config) :app.members/send-user-invitation))
    (is (contains? (:nexus/effects nexus-config) :app.members.index/resend-invitation))
    (is (contains? (:nexus/effects nexus-config) :app.members.index/delete-invitation))))

(deftest system-config-wires-nexus-into-the-handler-system
  (let [cfg (app.system/system-config {:profile :test})]
    (is (= (ig/ref :app.ig/nexus)
           (get-in cfg [:app.ig/handler :nexus])))))

(deftest system->state-includes-current-member-id-and-roles-from-request
  (let [{:keys [conn]} (tc/new-system "nexus-state")
        member-id      (random-uuid)
        state          (app-nexus/system->state
                        {:system  {:datomic {:conn conn}}
                         :request {:session {:session/member {:member/member-id member-id}
                                             :session/roles  #{:admin}}}})]
    (is (= member-id (:current-member-id state)))
    (is (= #{:admin} (:current-user-roles state)))))

(deftest db-transact-fx-dispatches-on-success-actions
  (let [{:keys [conn]} (tc/new-system "nexus-db-transact-on-success")
        team-id        (random-uuid)
        dispatched_    (atom nil)
        result         (app-nexus/db-transact-fx
                        {:dispatch (fn [actions dispatch-data]
                                     (reset! dispatched_ [actions dispatch-data]))}
                        {:system {:datomic {:conn conn}}}
                        [[[{:team/team-id team-id
                            :team/name    "On Success Test"}]
                          {:on-success [[:test/on-success team-id]]}]])]
    (is (some? (:db-after result)))
    (is (= [[:test/on-success team-id]]
           (first @dispatched_)))
    (is (= result
           (-> @dispatched_ second :tx-result)))))

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
    (is (instance? java.time.Instant (:now-a tx)))
    (is (= (:now-a tx) (:now-b tx)))))
