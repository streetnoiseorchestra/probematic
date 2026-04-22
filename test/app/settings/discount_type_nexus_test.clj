(ns app.settings.discount-type-nexus-test
  (:require
   [app.datastar :as datastar]
   [app.nexus :as app-nexus]
   [app.queries :as q]
   [app.settings.actions :as settings.actions]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(def discount-type-schema
  [{:db/ident       :travel.discount.type/discount-type-id
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :travel.discount.type/discount-type-name
    :db/unique      :db.unique/value
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :travel.discount.type/enabled?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident       :member/member-id
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :audit/user
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

(defn new-system []
  (let [uri (str "datomic:mem://discount-type-nexus-" (random-uuid))]
    (d/create-database uri)
    (let [conn      (d/connect uri)
          member-id (random-uuid)]
      @(d/transact conn discount-type-schema)
      @(d/transact conn [{:member/member-id member-id}])
      {:conn conn
       :member-id member-id})))

(defn seed-discount-type! [conn {:keys [discount-type-id discount-type-name enabled?]}]
  @(d/transact conn [{:travel.discount.type/discount-type-id   discount-type-id
                      :travel.discount.type/discount-type-name discount-type-name
                      :travel.discount.type/enabled?           enabled?}]))

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

(deftest create-discount-type-action-creates-the-discount-type-and-clears-the-form
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))
        resp                      (with-redefs [datastar/respond-signals (capture-signals calls)]
                                    (act-dispatch! system
                                                   {:discount-type-create {:discount-type-name "Klimaticket"}}
                                                   tab-id
                                                   ::settings.actions/create-discount-type))]
    (is (= {:status 200
            :body   {:remove ["discount-type-create"]}}
           resp))
    (is (= [{:remove ["discount-type-create"]}]
           @calls))
    (is (= [{:travel.discount.type/discount-type-name "Klimaticket"
             :travel.discount.type/enabled?           true}]
           (mapv #(select-keys % [:travel.discount.type/discount-type-name
                                  :travel.discount.type/enabled?])
                 (q/retrieve-all-discount-types (d/db conn)))))))

(deftest create-discount-type-action-merges-validation-errors-without-writing-to-datomic
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))
        resp                      (with-redefs [datastar/respond-signals (capture-signals calls)]
                                    (act-dispatch! system
                                                   {:discount-type-create {:discount-type-name ""}}
                                                   tab-id
                                                   ::settings.actions/create-discount-type))]
    (is (= {:status 200
            :body   {:merge {:discount-type-create
                             {:error {:discount-type-name "Discount type name is required."}}}}}
           resp))
    (is (= [{:merge {:discount-type-create
                     {:error {:discount-type-name "Discount type name is required."}}}}]
           @calls))
    (is (empty? (q/retrieve-all-discount-types (d/db conn))))))

(deftest create-discount-type-action-reports-duplicates-without-writing-to-datomic
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))]
    (seed-discount-type! conn {:discount-type-id   (random-uuid)
                               :discount-type-name "Klimaticket"
                               :enabled?           true})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {:discount-type-create {:discount-type-name "Klimaticket"}}
                                tab-id
                                ::settings.actions/create-discount-type))]
      (is (= {:status 200
              :body   {:merge {:discount-type-create
                               {:error {:discount-type-name "Discount type named 'Klimaticket' already exists."}}}}}
             resp))
      (is (= [{:merge {:discount-type-create
                       {:error {:discount-type-name "Discount type named 'Klimaticket' already exists."}}}}]
             @calls))
      (is (= 1 (count (q/retrieve-all-discount-types (d/db conn))))))))

(deftest update-discount-type-action-updates-the-entity-and-closes-the-form
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        discount-type-id          (random-uuid)
        calls                     (atom [])
        tab-id                    (str (random-uuid))]
    (seed-discount-type! conn {:discount-type-id   discount-type-id
                               :discount-type-name "Old Name"
                               :enabled?           true})
    (swap! datastar/!page-state assoc tab-id {:form {:current {:discount-type {:discount-type-id discount-type-id}}}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {:discount-type {:discount-type-id      (str discount-type-id)
                                                 :discount-type-name    "New Name"
                                                 :discount-type-enabled false}}
                                tab-id
                                ::settings.actions/update-discount-type))]
      (is (= {:status 200
              :body   {:remove ["discount-type"]}}
             resp))
      (is (= [{:remove ["discount-type"]}]
             @calls))
      (is (= {:travel.discount.type/discount-type-name "New Name"
              :travel.discount.type/enabled?           false}
             (-> (q/retrieve-discount-type (d/db conn) discount-type-id)
                 (select-keys [:travel.discount.type/discount-type-name
                               :travel.discount.type/enabled?]))))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :discount-type :discount-type-id]))))))

(deftest delete-discount-type-action-retracts-the-entity-and-closes-the-form
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        discount-type-id          (random-uuid)
        calls                     (atom [])
        tab-id                    (str (random-uuid))]
    (seed-discount-type! conn {:discount-type-id   discount-type-id
                               :discount-type-name "Delete Me"
                               :enabled?           true})
    (swap! datastar/!page-state assoc tab-id {:form {:current {:discount-type {:discount-type-id discount-type-id}}}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {:discount-type {:discount-type-id (str discount-type-id)}}
                                tab-id
                                ::settings.actions/delete-discount-type))]
      (is (= {:status 200
              :body   {:remove ["discount-type"]}}
             resp))
      (is (= [{:remove ["discount-type"]}]
             @calls))
      (is (nil? (q/retrieve-discount-type (d/db conn) discount-type-id)))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :discount-type :discount-type-id]))))))

(deftest open-discount-type-edit-action-opens-the-form-for-the-selected-row
  (reset! datastar/!page-state {})
  (let [system            (new-system)
        discount-type-id  (random-uuid)
        calls             (atom [])
        tab-id            (str (random-uuid))
        resp              (with-redefs [datastar/respond-signals (capture-signals calls)]
                            (act-dispatch! system
                                           {:discount-type {:discount-type-id (str discount-type-id)}}
                                           tab-id
                                           ::settings.actions/open-discount-type-edit))]
    (is (= {:status 200
            :body   {:merge {:discount-type {:open true}}}}
           resp))
    (is (= [{:merge {:discount-type {:open true}}}]
           @calls))
    (is (= discount-type-id
           (get-in @datastar/!page-state [tab-id :form :current :discount-type :discount-type-id])))))

(deftest close-discount-type-edit-action-closes-the-form-and-clears-the-current-edit-id
  (reset! datastar/!page-state {})
  (let [system            (new-system)
        discount-type-id  (random-uuid)
        calls             (atom [])
        tab-id            (str (random-uuid))]
    (swap! datastar/!page-state assoc tab-id {:form {:current {:discount-type {:discount-type-id discount-type-id}}}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {}
                                tab-id
                                ::settings.actions/close-discount-type-edit))]
      (is (= {:status 200
              :body   {:remove ["discount-type"]}}
             resp))
      (is (= [{:remove ["discount-type"]}]
             @calls))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :discount-type :discount-type-id]))))))
