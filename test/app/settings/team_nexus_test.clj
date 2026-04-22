(ns app.settings.team-nexus-test
  (:require
   [app.datastar :as datastar]
   [app.nexus :as app-nexus]
   [app.settings.actions :as settings.actions]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(def team-schema
  [{:db/ident       :team/name
    :db/unique      :db.unique/value
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :team/team-id
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :team/members
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident       :team/team-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :member/member-id
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :audit/user
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

(defn new-system []
  (let [uri (str "datomic:mem://team-nexus-" (random-uuid))]
    (d/create-database uri)
    (let [conn      (d/connect uri)
          member-id (random-uuid)]
      @(d/transact conn team-schema)
      @(d/transact conn [{:member/member-id member-id}])
      {:conn conn
       :member-id member-id})))

(defn dispatch-with-nexus [handler nexus-config system req]
  (let [interceptor (app-nexus/nexus-interceptor nexus-config system)
        ctx         ((:enter interceptor) {:request req})
        response    (handler (:request ctx))]
    (:response ((:leave interceptor) (assoc ctx :response response)))))

(defn act-dispatch! [system body tab-id action]
  (let [nexus-config (app-nexus/nexus)
        req          {:db           (d/db (:conn system))
                      :datomic-conn (:conn system)
                      :parameters   {:query (datastar/action-query-params action)}
                      :body-params  (assoc body :tab-id tab-id)
                      :session      {:session/member {:member/member-id (:member-id system)}}
                      :system       (assoc system :nexus nexus-config)}]
    (dispatch-with-nexus (requiring-resolve 'app.routes.datastar/act-handler)
                         nexus-config
                         system
                         req)))

(defn capture-signals [calls]
  (fn [_req & {:keys [merge remove execute]}]
    (let [payload (cond-> {}
                    merge (assoc :merge merge)
                    remove (assoc :remove remove)
                    execute (assoc :execute execute))]
      (swap! calls conj payload)
      {:status 200
       :body   payload})))

(defn seed-team! [conn {:keys [team-id team-name team-type]}]
  @(d/transact conn [(cond-> {:team/team-id team-id
                              :team/name    team-name}
                       team-type (assoc :team/team-type team-type))]))

(defn seed-member! [conn member-id]
  @(d/transact conn [{:member/member-id member-id}]))

(defn team-member-ids [db team-id]
  (->> (:team/members (d/entity db [:team/team-id team-id]))
       (mapv :member/member-id)
       set))

(deftest create-team-action-creates-the-team-and-clears-the-form
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))
        resp                      (with-redefs [datastar/respond-signals (capture-signals calls)]
                                    (act-dispatch! system
                                                   {:team-create {:team-name "Booking"}}
                                                   tab-id
                                                   ::settings.actions/create-team))]
    (is (= {:status 200
            :body   {:remove ["team-create"]}}
           resp))
    (is (= [{:remove ["team-create"]}]
           @calls))
    (is (= "Booking"
           (:team/name (d/entity (d/db conn) [:team/name "Booking"]))))))

(deftest create-team-action-merges-validation-errors-without-writing-to-datomic
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))
        resp                      (with-redefs [datastar/respond-signals (capture-signals calls)]
                                    (act-dispatch! system
                                                   {:team-create {:team-name ""}}
                                                   tab-id
                                                   ::settings.actions/create-team))]
    (is (= {:status 200
            :body   {:merge {:team-create
                             {:error {:team-name "Team name is required."}}}}}
           resp))
    (is (= [{:merge {:team-create
                     {:error {:team-name "Team name is required."}}}}]
           @calls))
    (is (nil? (d/entity (d/db conn) [:team/name ""])))))

(deftest update-team-action-updates-the-team-and-closes-the-form
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        team-id                   (random-uuid)
        calls                     (atom [])
        tab-id                    (str (random-uuid))]
    (seed-team! conn {:team-id   team-id
                      :team-name "Old Team"
                      :team-type :team.type/insurance})
    (swap! datastar/!page-state assoc tab-id {:form {:current {:team {:team-id team-id}}}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {:team {:team-id   (str team-id)
                                        :team-name " New Team "
                                        :team-type nil}}
                                tab-id
                                ::settings.actions/update-team))
          team (d/entity (d/db conn) [:team/team-id team-id])]
      (is (= {:status 200
              :body   {:remove ["team"]}}
             resp))
      (is (= [{:remove ["team"]}]
             @calls))
      (is (= "New Team" (:team/name team)))
      (is (nil? (:team/team-type team)))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :team :team-id]))))))

(deftest delete-team-action-retracts-the-team-and-closes-the-form
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        team-id                   (random-uuid)
        calls                     (atom [])
        tab-id                    (str (random-uuid))]
    (seed-team! conn {:team-id   team-id
                      :team-name "Delete Me"})
    (swap! datastar/!page-state assoc tab-id {:form {:current {:team {:team-id team-id}}}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {:team {:team-id (str team-id)}}
                                tab-id
                                ::settings.actions/delete-team))]
      (is (= {:status 200
              :body   {:remove ["team"]}}
             resp))
      (is (= [{:remove ["team"]}]
             @calls))
      (is (nil? (d/entity (d/db conn) [:team/team-id team-id])))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :team :team-id]))))))

(deftest remove-team-member-action-retracts-the-member-and-returns-no-content
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        team-id                   (random-uuid)
        member-id                 (random-uuid)
        tab-id                    (str (random-uuid))]
    (seed-member! conn member-id)
    (seed-team! conn {:team-id   team-id
                      :team-name "Members"})
    @(d/transact conn [[:db/add [:team/team-id team-id] :team/members [:member/member-id member-id]]])
    (let [resp (act-dispatch! system
                              {:team {:team-id          (str team-id)
                                      :remove-member-id (str member-id)}}
                              tab-id
                              ::settings.actions/remove-team-member)]
      (is (= {:status 204
              :headers {}
              :body ""}
             resp))
      (is (empty? (team-member-ids (d/db conn) team-id))))))

(deftest add-team-member-action-adds-the-member-and-clears-the-picker-signal
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        team-id                   (random-uuid)
        member-id                 (random-uuid)
        calls                     (atom [])
        tab-id                    (str (random-uuid))]
    (seed-member! conn member-id)
    (seed-team! conn {:team-id   team-id
                      :team-name "Members"})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {:team {:team-id   (str team-id)
                                        :member-id (str member-id)}}
                                tab-id
                                ::settings.actions/add-team-member))]
      (is (= {:status 200
              :body   {:merge {:team {:member-id ""}}}}
             resp))
      (is (= [{:merge {:team {:member-id ""}}}]
             @calls))
      (is (= #{member-id}
             (team-member-ids (d/db conn) team-id))))))

(deftest open-team-edit-action-opens-the-form-for-the-selected-row
  (reset! datastar/!page-state {})
  (let [system  (new-system)
        team-id (random-uuid)
        calls   (atom [])
        tab-id  (str (random-uuid))
        resp    (with-redefs [datastar/respond-signals (capture-signals calls)]
                  (act-dispatch! system
                                 {:team {:team-id (str team-id)}}
                                 tab-id
                                 ::settings.actions/open-team-edit))]
    (is (= {:status 200
            :body   {:merge {:team {:open true}}}}
           resp))
    (is (= [{:merge {:team {:open true}}}]
           @calls))
    (is (= team-id
           (get-in @datastar/!page-state [tab-id :form :current :team :team-id])))))

(deftest close-team-edit-action-closes-the-form-and-clears-the-current-edit-id
  (reset! datastar/!page-state {})
  (let [system  (new-system)
        team-id (random-uuid)
        calls   (atom [])
        tab-id  (str (random-uuid))]
    (swap! datastar/!page-state assoc tab-id {:form {:current {:team {:team-id team-id}}}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {}
                                tab-id
                                ::settings.actions/close-team-edit))]
      (is (= {:status 200
              :body   {:remove ["team"]}}
             resp))
      (is (= [{:remove ["team"]}]
             @calls))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :team :team-id]))))))
