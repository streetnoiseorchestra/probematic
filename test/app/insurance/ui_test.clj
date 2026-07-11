(ns app.insurance.ui-test
  (:require
   [app.html :as html]
   [app.insurance.ui :as ui]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn tr
  ([path]
   (name (last path)))
  ([path _args]
   (tr path)))

(def coverage
  {:instrument.coverage/status     :instrument.coverage.status/reviewed
   :instrument.coverage/change     :instrument.coverage.change/none
   :instrument.coverage/private?   false
   :instrument.coverage/item-count 1
   :instrument.coverage/value      1000M
   :instrument.coverage/cost       10M
   :instrument.coverage/types      []
   :instrument.coverage/instrument {:instrument/name   "Trumpet"
                                    :instrument/images []
                                    :instrument/owner  {:member/name "Player"}}})

(deftest insurance-cards-render-native-card-bodies
  (let [detail-html   (html/->str
                       (ui/coverage-detail-card
                        {:tr tr :system {:env {}}}
                        {:coverage coverage
                         :policy   {:insurance.policy/currency :EUR}}))
        comments-html (html/->str (ui/comments-card {:tr tr} []))]
    (testing "Coverage details retain their plain appearance and body content."
      (is (str/includes? detail-html "class=\"sno-card\""))
      (is (str/includes? detail-html "appearance=\"plain\""))
      (is (str/includes? detail-html
                         "<div class=\"body\"><div class=\"wa-stack wa-gap-l\">")))
    (testing "Comments retain their plain appearance and body content."
      (is (str/includes? comments-html "class=\"sno-card\""))
      (is (str/includes? comments-html "appearance=\"plain\""))
      (is (str/includes? comments-html
                         "<div class=\"body\"><div class=\"wa-stack\">")))))
