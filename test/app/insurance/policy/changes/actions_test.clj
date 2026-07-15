(ns app.insurance.policy.changes.actions-test
  (:require
   [app.insurance.policy.changes.actions :as actions]
   [app.insurance.test-support :as insurance-test]
   [app.test-common :as tc]
   [app.urls :as urls]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn tr [[key] & _]
  (name key))

(defn fixture
  ([]
   (fixture :complete))
  ([exporter-status]
   (let [{:keys [conn member-id]} (tc/new-system "insurance-policy-changes-action")
         policy-id                (random-uuid)
         overnight-id             (random-uuid)
         building-id              (random-uuid)
         coverage-types
         [{:type-id        overnight-id
           :name           "Worldwide"
           :premium-factor 0.2M}
          {:type-id        building-id
           :name           "Locked storage"
           :premium-factor 0.3M}]
         exporter-opts
         (case exporter-status
           :complete
           {:coverage-types coverage-types
            :exporter-id    :insurance/exporter-harmonia-v1
            :export-mappings
            [{:role             :overnight-vehicle
              :coverage-type-id overnight-id}
             {:role             :unattended-building
              :coverage-type-id building-id}]}

           :incomplete
           {:coverage-types coverage-types
            :exporter-id    :insurance/exporter-harmonia-v1
            :export-mappings
            [{:role             :overnight-vehicle
              :coverage-type-id overnight-id}]}

           :unknown
           {:coverage-types coverage-types
            :exporter-id    :insurance/exporter-harmonia-v2}

           :not-configured
           {:coverage-types coverage-types})]
     (insurance-test/seed-policy! conn policy-id exporter-opts)
     {:member-id member-id
      :policy-id policy-id
      :state     {:current-member-id member-id
                  :db                (d/db conn)
                  :tr                tr}})))

(defn signals [policy-id]
  {:insurance-policy-changes
   {:policy-id                   (str policy-id)
    :recipient                   "Insurer <insurance@example.test>"
    :subject                     "Policy update"
    :body                        "Please find the updates attached."
    :attachment-filename-new     "new.xls"
    :attachment-filename-changes "changes.xls"}})

(deftest confirm-changes-action-test
  (testing "confirmation activates the policy and returns to its dashboard"
    (let [{:keys [member-id policy-id state]} (fixture)
          [[_ tx-data opts] redirect]         (actions/confirm-changes-action
                                               state
                                               (signals policy-id))]
      (is (= {} opts))
      (is (= :insurance.policy.status/active
             (:insurance.policy/status (first tx-data))))
      (is (= [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]
             (last tx-data)))
      (is (= [:app.datastar/redirect (urls/link-policy policy-id)] redirect)))))

(deftest send-and-confirm-changes-action-test
  (testing "email delivery is one ordered effect with confirmation as its continuation"
    (let [{:keys [policy-id state]} (fixture)
          [effect]                  (actions/send-and-confirm-changes-action
                                     state
                                     (signals policy-id))
          [_ payload]               effect]
      (is (= :app.insurance/send-policy-changes (first effect)))
      (is (= {:policy-id                   policy-id
              :recipient                   "Insurer <insurance@example.test>"
              :subject                     "Policy update"
              :body                        "Please find the updates attached."
              :attachment-filename-new     "new.xls"
              :attachment-filename-changes "changes.xls"
              :on-success                  [[::actions/confirm-sent
                                             {:insurance-policy-changes
                                              {:policy-id (str policy-id)}}]]
              :redirect                    (urls/link-policy policy-id)}
             payload)))))

(deftest exporter-configuration-guards-delivery-actions-test
  (testing "send and preview reject absent, unknown, and incomplete exporters"
    (is (= [{:status :not-configured
             :message "exporter-not-configured-guidance"}
            {:status :unknown
             :message "exporter-unknown-guidance"}
            {:status :incomplete
             :message "exporter-incomplete-guidance"}]
           (mapv
            (fn [status]
              (let [{:keys [policy-id state]} (fixture status)
                    send-effects
                    (actions/send-and-confirm-changes-action
                     state
                     (signals policy-id))
                    preview-effects
                    (actions/preview-attachment-action
                     state
                     (assoc-in (signals policy-id)
                               [:insurance-policy-changes :preview-type]
                               "new"))
                    send-error (get-in (second send-effects)
                                       [2 :_error :_top :error])
                    preview-error (get-in (second preview-effects)
                                          [2 :_error :_top :error])]
                {:status  status
                 :message (when (= send-error preview-error)
                            send-error)}))
            [:not-configured :unknown :incomplete])))))
