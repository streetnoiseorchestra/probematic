(ns app.insurance.policy.changes.api-test
  (:require
   [app.insurance.policy.changes.api :as sut]
   [app.insurance.test-support :as insurance-test]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(deftest excel-download-response-test
  (let [{:keys [conn]} (tc/new-system "insurance-policy-changes-excel")
        policy-id      (random-uuid)]
    (insurance-test/seed-policy! conn policy-id)
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
