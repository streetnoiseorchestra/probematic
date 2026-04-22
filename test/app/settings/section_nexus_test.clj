(ns app.settings.section-nexus-test
  (:require
   [app.datastar :as datastar]
   [app.nexus :as app-nexus]
   [app.settings.actions :as settings.actions]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(def section-schema
  [{:db/ident       :section/name
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :section/active?
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident       :section/position
    :db/unique      :db.unique/value
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident       :member/member-id
    :db/unique      :db.unique/identity
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :audit/user
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

(defn new-system []
  (let [uri (str "datomic:mem://section-nexus-" (random-uuid))]
    (d/create-database uri)
    (let [conn      (d/connect uri)
          member-id (random-uuid)]
      @(d/transact conn section-schema)
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

(defn seed-section! [conn {:keys [section-name active? position]}]
  @(d/transact conn [(cond-> {:section/name    section-name
                              :section/active? active?}
                       position (assoc :section/position position))]))

(deftest create-section-action-creates-the-section-and-clears-the-form
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))
        resp                      (with-redefs [datastar/respond-signals (capture-signals calls)]
                                    (act-dispatch! system
                                                   {:section-create {:section-name "Trumpets"}}
                                                   tab-id
                                                   ::settings.actions/create-section))
        section                   (d/entity (d/db conn) [:section/name "Trumpets"])]
    (is (= {:status 200
            :body   {:remove ["section-create"]}}
           resp))
    (is (= [{:remove ["section-create"]}]
           @calls))
    (is (= "Trumpets" (:section/name section)))
    (is (true? (:section/active? section)))))

(deftest create-section-action-merges-validation-errors-without-writing-to-datomic
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))
        resp                      (with-redefs [datastar/respond-signals (capture-signals calls)]
                                    (act-dispatch! system
                                                   {:section-create {:section-name ""}}
                                                   tab-id
                                                   ::settings.actions/create-section))]
    (is (= {:status 200
            :body   {:merge {:section-create
                             {:error {:section-name "Section name is required."}}}}}
           resp))
    (is (= [{:merge {:section-create
                     {:error {:section-name "Section name is required."}}}}]
           @calls))
    (is (nil? (d/entity (d/db conn) [:section/name ""])))))

(deftest update-section-action-updates-the-section-and-closes-the-form
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))]
    (seed-section! conn {:section-name "Old Section"
                         :active?      true})
    (swap! datastar/!page-state assoc tab-id {:form {:current {:section {:section-id "Old Section"}}}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {:section {:section-old-name "Old Section"
                                           :section-name     "New Section"
                                           :section-enabled  false}}
                                tab-id
                                ::settings.actions/update-section))
          section (d/entity (d/db conn) [:section/name "New Section"])]
      (is (= {:status 200
              :body   {:remove ["section"]}}
             resp))
      (is (= [{:remove ["section"]}]
             @calls))
      (is (= "New Section" (:section/name section)))
      (is (false? (:section/active? section)))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :section :section-id]))))))

(deftest open-section-edit-action-opens-the-form-for-the-selected-row
  (reset! datastar/!page-state {})
  (let [system     (new-system)
        section-id "Trumpets"
        calls      (atom [])
        tab-id     (str (random-uuid))
        resp       (with-redefs [datastar/respond-signals (capture-signals calls)]
                     (act-dispatch! system
                                    {:section {:section-id section-id}}
                                    tab-id
                                    ::settings.actions/open-section-edit))]
    (is (= {:status 200
            :body   {:merge {:section {:open true}}}}
           resp))
    (is (= [{:merge {:section {:open true}}}]
           @calls))
    (is (= section-id
           (get-in @datastar/!page-state [tab-id :form :current :section :section-id])))))

(deftest close-section-edit-action-closes-the-form-and-clears-the-current-edit-id
  (reset! datastar/!page-state {})
  (let [system     (new-system)
        section-id "Trumpets"
        calls      (atom [])
        tab-id     (str (random-uuid))]
    (swap! datastar/!page-state assoc tab-id {:form {:current {:section {:section-id section-id}}}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {}
                                tab-id
                                ::settings.actions/close-section-edit))]
      (is (= {:status 200
              :body   {:remove ["section"]}}
             resp))
      (is (= [{:remove ["section"]}]
             @calls))
      (is (nil? (get-in @datastar/!page-state [tab-id :form :current :section :section-id]))))))

(deftest open-section-reorder-action-opens-reordering-in-page-state
  (reset! datastar/!page-state {})
  (let [system (new-system)
        tab-id (str (random-uuid))
        resp   (act-dispatch! system
                              {}
                              tab-id
                              ::settings.actions/open-section-reorder)]
    (is (= {:status 204
            :headers {}
            :body ""}
           resp))
    (is (true? (get-in @datastar/!page-state [tab-id :section-reorder :open])))))

(deftest close-section-reorder-action-closes-reordering-in-page-state-and-signals
  (reset! datastar/!page-state {})
  (let [system (new-system)
        calls  (atom [])
        tab-id (str (random-uuid))]
    (swap! datastar/!page-state assoc tab-id {:section-reorder {:open true}})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {}
                                tab-id
                                ::settings.actions/close-section-reorder))]
      (is (= {:status 200
              :body   {:merge {:section-reorder {:open false}}}}
             resp))
      (is (= [{:merge {:section-reorder {:open false}}}]
             @calls))
      (is (false? (get-in @datastar/!page-state [tab-id :section-reorder :open]))))))

(deftest update-section-order-action-updates-positions-and-closes-reordering-signal
  (reset! datastar/!page-state {})
  (let [{:keys [conn] :as system} (new-system)
        calls                     (atom [])
        tab-id                    (str (random-uuid))]
    (seed-section! conn {:section-name "Trumpets"
                         :active?      true
                         :position     10})
    (seed-section! conn {:section-name "Trombones"
                         :active?      true
                         :position     20})
    (let [resp (with-redefs [datastar/respond-signals (capture-signals calls)]
                 (act-dispatch! system
                                {:section {:order {:Trumpets  1
                                                   :Trombones 0}}}
                                tab-id
                                ::settings.actions/update-section-order))]
      (is (= {:status 200
              :body   {:merge {:section-reorder {:open false}}}}
             resp))
      (is (= [{:merge {:section-reorder {:open false}}}]
             @calls))
      (is (= 1 (:section/position (d/entity (d/db conn) [:section/name "Trumpets"]))))
      (is (= 0 (:section/position (d/entity (d/db conn) [:section/name "Trombones"])))))))
