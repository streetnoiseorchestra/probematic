(ns app.settings.discount-type-engine-test
  (:require
   [app.datastar :as datastar]
   [app.engine :as engine]
   [app.queries :as q]
   [app.settings.routes :as settings.routes]
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
  (let [uri (str "datomic:mem://discount-type-engine-" (random-uuid))]
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

(defn request-for [{:keys [conn member-id]} body tab-id]
  {:db           (d/db conn)
   :datomic-conn conn
   :parameters   {:body body}
   :body-params  {:tab-id tab-id}
   :session      {:session/member {:member/member-id member-id}}})

(defn dispatch! [system command body tab-id]
  (engine/dispatch-request-sync (engine/build-env)
                                (request-for system body tab-id)
                                {:command/kind command}))

(defn capture-signals [calls]
  (fn [_req & {:keys [merge remove execute]}]
    (let [payload (cond-> {}
                    merge (assoc :merge merge)
                    remove (assoc :remove remove)
                    execute (assoc :execute execute))]
      (swap! calls conj payload)
      {:status 200
       :body   payload})))

(deftest create-discount-type-command-creates-the-discount-type-and-clears-the-form
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))
        resp                      (with-redefs [datastar/respond-signals (capture-signals calls)]
                                    (dispatch! system
                                               ::settings.routes/create-discount-type
                                               {:discount-type-create {:discount-type-name "Klimaticket"}}
                                               tab-id))]
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

(deftest create-discount-type-command-merges-validation-errors-without-writing-to-datomic
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))
        resp                      (with-redefs [datastar/respond-signals (capture-signals calls)]
                                    (dispatch! system
                                               ::settings.routes/create-discount-type
                                               {:discount-type-create {:discount-type-name ""}}
                                               tab-id))]
    (is (= {:status 200
            :body   {:merge {:discount-type-create
                             {:error {:discount-type-name "Discount type name is required."}}}}}
           resp))
    (is (= [{:merge {:discount-type-create
                     {:error {:discount-type-name "Discount type name is required."}}}}]
           @calls))
    (is (empty? (q/retrieve-all-discount-types (d/db conn))))))

(deftest update-discount-type-command-updates-the-entity-and-closes-the-form
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
                 (dispatch! system
                            ::settings.routes/update-discount-type
                            {:discount-type {:discount-type-id      discount-type-id
                                             :discount-type-name    "New Name"
                                             :discount-type-enabled false}}
                            tab-id))]
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

(deftest delete-discount-type-command-retracts-the-entity-and-closes-the-form
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
                 (dispatch! system
                            ::settings.routes/delete-discount-type
                            {:discount-type {:discount-type-id discount-type-id}}
                            tab-id))]
      (is (= {:status 200
              :body   {:remove ["discount-type"]}}
             resp))
      (is (= [{:remove ["discount-type"]}]
             @calls))
      (is (nil? (q/retrieve-discount-type (d/db conn) discount-type-id)))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :discount-type :discount-type-id]))))))

(deftest open-discount-type-edit-command-opens-the-form-for-the-selected-row
  (reset! datastar/!page-state {})
  (let [system            (new-system)
        discount-type-id  (random-uuid)
        calls             (atom [])
        tab-id            (str (random-uuid))
        resp              (with-redefs [datastar/respond-signals (capture-signals calls)]
                            (dispatch! system
                                       ::settings.routes/open-discount-type-edit
                                       {:discount-type {:discount-type-id discount-type-id}}
                                       tab-id))]
    (is (= {:status 200
            :body   {:merge {:discount-type {:open true}}}}
           resp))
    (is (= [{:merge {:discount-type {:open true}}}]
           @calls))
    (is (= discount-type-id
           (get-in @datastar/!page-state [tab-id :form :current :discount-type :discount-type-id])))))

(deftest close-discount-type-edit-command-closes-the-form-and-clears-the-current-edit-id
  (reset! datastar/!page-state {})
  (let [system            (new-system)
        discount-type-id  (random-uuid)
        calls             (atom [])
        tab-id            (str (random-uuid))]
    (swap! datastar/!page-state assoc tab-id {:form {:current {:discount-type {:discount-type-id discount-type-id}}}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (dispatch! system
                            ::settings.routes/close-discount-type-edit
                            {}
                            tab-id))]
      (is (= {:status 200
              :body   {:remove ["discount-type"]}}
             resp))
      (is (= [{:remove ["discount-type"]}]
             @calls))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :discount-type :discount-type-id]))))))
