(ns app.insurance.exporters-test
  (:require
   [app.insurance.exporters :as exporters]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io ByteArrayOutputStream]))

(def overnight-role
  :insurance.exporter.harmonia-v1/overnight-vehicle)

(def building-role
  :insurance.exporter.harmonia-v1/unattended-building)

(def harmonia-exporter-id
  :insurance/exporter-harmonia-v1)

(defn coverage-type
  [type-id name]
  {:insurance.coverage.type/type-id type-id
   :insurance.coverage.type/name    name})

(defn policy
  [exporter-id coverage-types role->type-id]
  (let [type-id->coverage-type
        (into {}
              (map (juxt :insurance.coverage.type/type-id identity))
              coverage-types)]
    {:insurance.policy/exporter-id exporter-id
     :insurance.policy/coverage-types coverage-types
     :insurance.policy/export-mappings
     (mapv (fn [[role type-id]]
             {:insurance.export.mapping/role role
              :insurance.export.mapping/coverage-type
              (get type-id->coverage-type type-id)})
           role->type-id)}))

(defn coverage
  [coverage-types]
  {:instrument.coverage/value      1500M
   :instrument.coverage/insurer-id "H-42"
   :instrument.coverage/item-count 2
   :instrument.coverage/types      coverage-types
   :instrument.coverage/instrument
   {:instrument/name             "Renamed touring instrument"
    :instrument/make             "Yamaha"
    :instrument/model            "Xeno"
    :instrument/serial-number    "SN-42"
    :instrument/build-year       "2020"
    :instrument/description      "Gold lacquer"
    :instrument/category         {:instrument.category/name "Brass"}
    :instrument/owner            {:member/name "Ada"}
    :instrument/images-share-url "https://example.test/images/42"}})

(defn configuration-summary
  [policy]
  (some-> (exporters/policy-configuration policy)
          (select-keys [:exporter-id
                        :status
                        :missing-roles
                        :role->coverage-type-id])))

(defn export-error
  [policy coverage]
  (try
    (exporters/coverage->row policy coverage)
    nil
    (catch clojure.lang.ExceptionInfo error
      (select-keys (ex-data error)
                   [:type :exporter-id :status :missing-roles]))))

(deftest harmonia-v1-registry-test
  (testing "the current provider format is an immutable versioned descriptor"
    (is (= [{:exporter-id harmonia-exporter-id
             :label-key
             :insurance/exporter-harmonia-v1
             :template-resource "insurance/exporters/harmonia-v1.xls"
             :sheet-name        "Inventar"
             :roles
             [{:role      overnight-role
               :label-key
               :insurance/exporter-role-overnight-vehicle
               :required? true}
              {:role      building-role
               :label-key
               :insurance/exporter-role-unattended-building
               :required? true}]}]
           (mapv #(select-keys % [:exporter-id
                                  :label-key
                                  :template-resource
                                  :sheet-name
                                  :roles])
                 (exporters/descriptors))))
    (is (= (first (exporters/descriptors))
           (exporters/descriptor harmonia-exporter-id)))
    (is (fn? (:generator (exporters/descriptor harmonia-exporter-id))))
    (is (nil? (exporters/descriptor
               :insurance/exporter-harmonia-v2)))))

(deftest policy-exporter-configuration-test
  (let [overnight-id (random-uuid)
        building-id  (random-uuid)
        types        [(coverage-type overnight-id "Renamed worldwide cover")
                      (coverage-type building-id "Renamed storage cover")]]
    (testing "v1 roles resolve through coverage-type references"
      (is (= {:exporter-id harmonia-exporter-id
              :status      :complete
              :missing-roles []
              :role->coverage-type-id
              {overnight-role overnight-id
               building-role  building-id}}
             (configuration-summary
              (policy harmonia-exporter-id
                      types
                      {overnight-role overnight-id
                       building-role  building-id})))))

    (testing "exporter selection and mappings remain independent per policy"
      (let [first-policy  (policy harmonia-exporter-id
                                  types
                                  {overnight-role overnight-id
                                   building-role  building-id})
            second-policy (policy harmonia-exporter-id
                                  types
                                  {overnight-role building-id
                                   building-role  overnight-id})
            item          (coverage [(first types)])]
        (is (= [["x" ""] ["" "x"]]
               (mapv #(->> (exporters/coverage->row % item)
                           (drop 9)
                           (take 2)
                           vec)
                     [first-policy second-policy])))))

    (testing "unconfigured, unknown, and incomplete policies are explicit"
      (let [unconfigured (policy nil types {})
            unknown      (policy :insurance/exporter-harmonia-v2
                                 types
                                 {})
            incomplete   (policy harmonia-exporter-id
                                 types
                                 {overnight-role overnight-id})]
        (is (= [{:exporter-id nil
                 :status      :not-configured
                 :missing-roles []
                 :role->coverage-type-id {}}
                {:exporter-id :insurance/exporter-harmonia-v2
                 :status      :unknown
                 :missing-roles []
                 :role->coverage-type-id {}}
                {:exporter-id harmonia-exporter-id
                 :status      :incomplete
                 :missing-roles [building-role]
                 :role->coverage-type-id {overnight-role overnight-id}}]
               (mapv configuration-summary
                     [unconfigured unknown incomplete])))
        (is (= [{:type        :insurance.exporter/configuration-error
                 :exporter-id nil
                 :status      :not-configured
                 :missing-roles []}
                {:type        :insurance.exporter/configuration-error
                 :exporter-id :insurance/exporter-harmonia-v2
                 :status      :unknown
                 :missing-roles []}
                {:type        :insurance.exporter/configuration-error
                 :exporter-id harmonia-exporter-id
                 :status      :incomplete
                 :missing-roles [building-role]}]
               (mapv #(export-error % (coverage []))
                     [unconfigured unknown incomplete])))))))

(deftest harmonia-v1-row-generation-is-rename-safe-test
  (let [overnight-id   (random-uuid)
        building-id    (random-uuid)
        overnight-type (coverage-type overnight-id "Nothing like the legacy label")
        building-type  (coverage-type building-id "Another administrator rename")
        policy         (policy harmonia-exporter-id
                               [overnight-type building-type]
                               {overnight-role overnight-id
                                building-role  building-id})]
    (testing "semantic role columns depend on mapped references, not names"
      (is (= [2
              "Renamed touring instrument"
              "Yamaha"
              "Xeno"
              "SN-42"
              "2020"
              "Brass; Gold lacquer"
              1500M
              3000M
              "x"
              ""
              ""
              ""
              "Ada"
              "H-42"
              "https://example.test/images/42"]
             (exporters/coverage->row
              policy
              (coverage [overnight-type])))))))

(deftest harmonia-v1-workbook-generation-test
  (let [overnight-id (random-uuid)
        building-id  (random-uuid)
        types        [(coverage-type overnight-id "Renamed worldwide cover")
                      (coverage-type building-id "Renamed storage cover")]
        policy       (assoc (policy harmonia-exporter-id
                                    types
                                    {overnight-role overnight-id
                                     building-role  building-id})
                            :insurance.policy/covered-instruments [])
        output       (ByteArrayOutputStream.)]
    (testing "the registered generator preserves the v1 Excel file format"
      (is (identical? output
                      (exporters/generate-changeset!
                       #{:instrument.coverage.change/new}
                       policy
                       output)))
      (is (= [0xD0 0xCF 0x11 0xE0]
             (mapv #(bit-and 0xff %)
                   (take 4 (.toByteArray output))))))))
