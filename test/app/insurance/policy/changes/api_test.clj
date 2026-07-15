(ns app.insurance.policy.changes.api-test
  (:require
   [app.i18n :as i18n]
   [app.insurance.policy.changes.api :as sut]
   [app.insurance.test-support :as insurance-test]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(deftest excel-download-response-test
  (let [{:keys [conn]} (tc/new-system "insurance-policy-changes-excel")
        policy-id      (random-uuid)
        overnight-id   (random-uuid)
        building-id    (random-uuid)]
    (insurance-test/seed-policy!
     conn
     policy-id
     {:coverage-types
      [{:type-id overnight-id :name "Worldwide" :premium-factor 0.2M}
       {:type-id building-id :name "Locked storage" :premium-factor 0.3M}]
      :exporter-id :insurance/exporter-harmonia-v1
      :export-mappings
      [{:role :insurance.exporter.harmonia-v1/overnight-vehicle
        :coverage-type-id overnight-id}
       {:role :insurance.exporter.harmonia-v1/unattended-building
        :coverage-type-id building-id}]})
    (testing "a requested changeset is returned as an Excel attachment"
      (let [response (sut/download-excel
                      {:db (d/db conn)
                       :parameters
                       {:path  {:policy-id policy-id}
                        :query {:attachment-filename "new instruments.xls"
                                :preview-type        "new"}}})
            bytes    (.readAllBytes ^java.io.InputStream (:body response))]
        (is (= {:status              200
                :content-disposition "attachment; filename*=UTF-8''new%20instruments.xls"
                :content-type        "application/vnd.ms-excel"
                :content-length      (str (count bytes))
                :ole-signature       [0xD0 0xCF 0x11 0xE0]}
               {:status              (:status response)
                :content-disposition (get-in response [:headers "Content-Disposition"])
                :content-type        (get-in response [:headers "Content-Type"])
                :content-length      (get-in response [:headers "Content-Length"])
                :ole-signature       (mapv #(bit-and 0xff %) (take 4 bytes))}))))))

(deftest invalid-exporter-excel-download-response-is-localized-test
  (let [languages (i18n/read-langs)
        cases     [{:description "an exporter has not been configured"
                    :policy      {}
                    :messages    {:en (str "Choose and configure an exporter in the policy settings "
                                           "before previewing or sending spreadsheets.")
                                  :de (str "Wähle und konfiguriere in den Policeneinstellungen "
                                           "ein Exportformat, bevor du Tabellen ansiehst oder sendest.")}}
                   {:description "the configured exporter is unknown"
                    :policy      {:insurance.policy/exporter-id :insurance/exporter-unknown}
                    :messages    {:en (str "The policy uses an exporter version this application does not "
                                           "recognize. Choose a supported version in the policy settings.")
                                  :de (str "Die Police verwendet eine unbekannte Exportversion. "
                                           "Wähle in den Policeneinstellungen eine unterstützte Version.")}}
                   {:description "the exporter configuration is incomplete"
                    :policy      {:insurance.policy/exporter-id :insurance/exporter-harmonia-v1}
                    :messages    {:en (str "Map every required exporter role in the policy settings "
                                           "before previewing or sending spreadsheets.")
                                  :de (str "Ordne in den Policeneinstellungen alle erforderlichen "
                                           "Exportrollen zu, bevor du Tabellen ansiehst oder sendest.")}}]]
    (doseq [{:keys [description messages policy]} cases
            locale                              [:en :de]]
      (testing (str description " in " (name locale))
        (is (= {:status  409
                :headers {"Content-Type" "text/plain; charset=utf-8"}
                :body    (messages locale)}
               (sut/download-excel
                {:policy policy
                 :tr     (i18n/tr-with languages [locale])
                 :parameters
                 {:query {:attachment-filename "new instruments.xls"
                          :preview-type        "new"}}})))))))
