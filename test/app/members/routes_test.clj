(ns app.members.routes-test
  (:require
   [app.members.routes :as routes]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(defn seed-vcard-member! [conn member-id]
  @(d/transact conn [{:db/id "section"
                      :section/name "Trumpets"
                      :section/active? true
                      :section/position 1}
                     {:member/member-id member-id
                      :member/name "Casey Example"
                      :member/nick "casey"
                      :member/email "casey@example.com"
                      :member/phone "+43677123456"
                      :member/section "section"}]))

(deftest member-vcard-returns-vcard-download-response
  (let [{:keys [conn member-id]} (tc/new-system "member-vcard")]
    (seed-vcard-member! conn member-id)
    (let [response (routes/member-vcard {:db (d/db conn)
                                         :path-params {:member-id (str member-id)}})]
      (is (= 200 (:status response)))
      (is (= "text/x-vcard" (get-in response [:headers "Content-Type"])))
      (is (str/starts-with? (get-in response [:headers "Content-Disposition"])
                            "attachment;"))
      (is (str/includes? (get-in response [:headers "Content-Disposition"])
                         "casey.vcf"))
      (is (str/includes? (:body response) "BEGIN:VCARD"))
      (is (str/includes? (:body response) (str "UID:" member-id)))
      (is (str/includes? (:body response) "FN:Casey Example"))
      (is (str/includes? (:body response) "NICKNAME:casey"))
      (is (str/includes? (:body response) "TITLE:Trumpets"))
      (is (str/includes? (:body response) "TEL;TYPE=PREF,mobile;VALUE=UNKNOWN:+43677123456"))
      (is (str/includes? (:body response) "EMAIL;TYPE=HOME:casey@example.com")))))
