(ns app.frame-loop-test
  (:require
   [app.datastar :as ds]
   [app.game-loop :as game]
   [app.ig]
   [app.nexus :as nexus]
   [app.routes.datastar :as routes]
   [app.settings.teams.actions :as teams]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is use-fixtures]]
   [datomic.api :as d]
   [integrant.core :as ig])
  (:import [java.util.concurrent ConcurrentHashMap]))

(use-fixtures :each tc/with-released-test-connections)

(defn request [system member-id tab-id action signals]
  {:system      system
   :tr          (constantly "Something went wrong")
   :app/session {:session/member {:member/member-id member-id}
                 :session/roles  #{:Mitglieder}}
   :parameters  {:query {:ns (namespace action) :kw (name action)}}
   :body-params (assoc signals :tab-id tab-id)})

(deftest accepted-edit-commits-before-both-tabs-render
  (let [{:keys [conn member-id]} (tc/new-system "frame-team-edit")
        team-id                  (random-uuid)
        tab-id                   (str (random-uuid))
        token                    (Object.)
        system                   {:datomic {:conn conn} :nexus (nexus/nexus)}
        runtime                  (ig/init-key :app.ig/frame-loop (assoc system :profile :dev :enabled? true :queue-capacity 2))
        system                   (assoc system :frame-loop runtime)
        entered                  (promise)
        release                  (promise)
        views                    [(promise) (promise)]
        edit-request             (request system member-id tab-id ::teams/update-team
                                          {:team {:team-id (str team-id) :team-name "After" :team-type ""}})]
    (try
      @(d/transact conn [{:team/team-id team-id :team/name "Before"}])
      (swap! ds/!page-state assoc tab-id {::ds/state-token token})
      (swap! (:clients runtime) assoc tab-id
             {:token token :member-id member-id :revision 0 :events [] :close! (fn [])})
      (.put ^ConcurrentHashMap (::game/conns runtime) :barrier
            (fn [_] (deliver entered true) @release))
      (is (= true (deref entered 5000 :timeout)))
      (doseq [[id view] (map-indexed vector views)]
        (.put ^ConcurrentHashMap (::game/conns runtime) id
              (fn [frame]
                (when (= "After" (:team/name (d/entity (:db frame) [:team/team-id team-id])))
                  (deliver view frame)))))
      (is (= 409 (:status (routes/act-handler
                           (assoc-in edit-request [:app/session :session/member :member/member-id] (random-uuid))))))
      (is (= 204 (:status (routes/act-handler
                           (request system member-id tab-id ::teams/open-team-edit {:targetid (str team-id)})))))
      (is (= 204 (:status (routes/act-handler edit-request))))
      (is (= 503 (:status (routes/act-handler edit-request))))
      (is (= "Before" (:team/name (d/entity (d/db conn) [:team/team-id team-id]))))
      (deliver release true)
      (let [[a b] (mapv #(deref % 5000 :timeout) views)]
        (is (map? a))
        (is (map? b))
        (is (identical? (:db a) (:db b)))
        (is (false? (get-in a [:page-state tab-id :team])))
        (is (= [1 2] (mapv first (get-in a [:clients tab-id :events])))))
      (is (= {:audit/action ::teams/update-team :audit/origin :app.origin/browser}
             (d/q '[:find (pull ?tx [:audit/action :audit/origin]) .
                    :where [_ :team/name "After" ?tx]] (d/db conn))))
      (is (= "After" (ds/render-in-frame! runtime #(-> (:db %) (d/entity [:team/team-id team-id]) :team/name))))
      (ig/halt-key! :app.ig/frame-loop runtime)
      (is (= 503 (:status (routes/act-handler edit-request))))
      (finally
        (deliver release true)
        (ig/halt-key! :app.ig/frame-loop runtime)
        (swap! ds/!page-state dissoc tab-id)))))

(deftest feature-action-preserves-rejected-input
  (let [{:keys [conn member-id]} (tc/new-system "frame-team-error")
        team-id                  (random-uuid)
        other-id                 (random-uuid)
        tab-id                   (str (random-uuid))
        token                    (Object.)
        system                   {:datomic {:conn conn} :nexus (nexus/nexus)}
        runtime                  {:clients (atom {tab-id {:token token :revision 0 :events []}})}]
    (try
      @(d/transact conn [{:team/team-id team-id :team/name "Before"}
                         {:team/team-id other-id :team/name "Taken"}])
      (swap! ds/!page-state assoc tab-id {::ds/state-token token :team {:team-id team-id}})
      (doseq [name ["Taken" " "]]
        (let [signals {:team-create false :team {:team-id (str team-id) :team-name name :team-type ""}}
              req     (assoc (request system member-id tab-id ::teams/update-team signals) ::ds/state-token token)]
          (nexus/process-queued! (:nexus system) system runtime {:request req :actions [[::teams/update-team signals]]})
          (is (= name (get-in @ds/!page-state [tab-id :team :team-name])))
          (is (string? (get-in @ds/!page-state [tab-id :team :error :team-name :error])))))
      (is (= 2 (get-in @(:clients runtime) [tab-id :revision])))
      (let [signals {:team {:team-id (str team-id) :team-name "Retry name" :team-type ""}}
            req     (assoc (request system (random-uuid) tab-id ::teams/update-team signals)
                           ::ds/state-token token)]
        ;; A missing audit member makes the real transaction fail after input preservation.
        (nexus/process-queued! (:nexus system) system runtime
                               {:request req :actions [[::teams/update-team signals]]})
        (is (= "Retry name" (get-in @ds/!page-state [tab-id :team :team-name])))
        (is (= [:app.datastar.sse/merge-signals :app.datastar.sse/execute-script]
               (mapv first (second (last (get-in @(:clients runtime) [tab-id :events])))))))
      (is (= "Before" (:team/name (d/entity (d/db conn) [:team/team-id team-id]))))
      (finally (swap! ds/!page-state dissoc tab-id)))))

(deftest arbitrary-actions-and-sse-plans-use-the-shared-path
  (let [{:keys [conn member-id]} (tc/new-system "frame-generic-action")
        tab-id                   (str (random-uuid))
        token                    (Object.)
        events                   [[:app.datastar.sse/merge-signals {:loading false}]
                                  [:app.datastar.sse/execute-script "console.log('done')"]
                                  [:app.datastar.sse/redirect "/"]]
        config                   (-> (nexus/nexus)
                                     (assoc-in [:nexus/actions ::arbitrary]
                                               (fn [state _]
                                                 [[:app.datastar/respond-sse
                                                   (assoc-in events [0 1 :loading]
                                                             (not (get-in state [:env :frame-test?])))]]))
                                     (assoc-in [:nexus/actions ::rejected]
                                               (fn [_ _] (throw (ex-info "Expected action rejection" {})))))
        system                   {:datomic {:conn conn} :nexus config}
        runtime                  (ig/init-key :app.ig/frame-loop (assoc system :profile :dev :enabled? true))
        system                   (assoc system :frame-loop runtime :env {:frame-test? true})
        delivered                (promise)]
    (try
      (swap! (:clients runtime) assoc tab-id
             {:token token :member-id member-id :revision 0 :events [] :close! (fn [])})
      (.put ^ConcurrentHashMap (::game/conns runtime) :observe
            (fn [frame]
              (when (= 2 (get-in frame [:clients tab-id :revision]))
                (deliver delivered (get-in frame [:clients tab-id :events])))))
      (is (= 204 (:status (routes/act-handler (request system member-id tab-id ::rejected {})))))
      (is (= 204 (:status (routes/act-handler (request system member-id tab-id ::arbitrary {})))))
      (let [responses (deref delivered 5000 :timeout)]
        (is (= [2 events] (last responses)))
        (is (= [:app.datastar.sse/merge-signals :app.datastar.sse/execute-script]
               (mapv first (second (first responses))))))
      (finally (ig/halt-key! :app.ig/frame-loop runtime)))))

(deftest retired-tab-cannot-change-replacement-state
  (let [tab-id    (str (random-uuid))
        old-token (Object.)
        new-token (Object.)]
    (try
      (swap! ds/!page-state assoc tab-id {::ds/state-token new-token :team false})
      (ds/state-transact! {:body-params {:tab-id tab-id} ::ds/state-token old-token}
                          #(assoc % :team {:team-name "Old"}))
      (is (false? (get-in @ds/!page-state [tab-id :team])))
      (swap! ds/!page-state dissoc tab-id)
      (ds/state-transact! {:body-params {:tab-id tab-id} ::ds/state-token old-token}
                          #(assoc % :team {:team-name "Old"}))
      (is (not (contains? @ds/!page-state tab-id)))
      (finally (swap! ds/!page-state dissoc tab-id)))))
