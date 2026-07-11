(ns app.ui2.card-test
  (:require
   [app.html :as html]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn resolve-card []
  (try
    (requiring-resolve 'app.ui2.card/Card)
    (catch Throwable _
      nil)))

(defn card-alias []
  (some-> (resolve-card) deref))

(defn card-html [attrs & children]
  (when-let [card (card-alias)]
    (html/->str (into [card attrs] children))))

(deftest card-renders-native-html-with-defaults-and-caller-attributes
  (let [card (card-alias)]
    (is (some? card) "Card alias should exist")
    (when card
      (let [rendered (card-html {:id              "policy-card"
                                 :class           "policy-summary elevated"
                                 :aria-label      "Policy summary"
                                 :data-on:click   "selectCard()"
                                 :style           "--spacing: 2rem"}
                                "Policy details")]
        (is (str/starts-with? rendered "<div"))
        (is (not (str/includes? rendered "<wa-card")))
        (is (str/includes? rendered "class=\"sno-card policy-summary elevated\""))
        (is (str/includes? rendered "appearance=\"outlined\""))
        (is (str/includes? rendered "orientation=\"vertical\""))
        (is (str/includes? rendered "id=\"policy-card\""))
        (is (str/includes? rendered "aria-label=\"Policy summary\""))
        (is (str/includes? rendered "data-on:click=\"selectCard()\""))
        (is (str/includes? rendered "style=\"--spacing: 2rem\""))))))

(deftest card-supports-every-appearance
  (let [card (card-alias)]
    (is (some? card) "Card alias should exist")
    (when card
      (is (= ["accent" "filled" "outlined" "filled-outlined" "plain"]
             (mapv (fn [appearance]
                     (let [rendered (card-html {:appearance appearance} appearance)]
                       (second (re-find #"appearance=\"([^\"]+)\"" rendered))))
                   ["accent" "filled" "outlined" "filled-outlined" "plain"]))))))

(deftest vertical-card-renders-every-vertical-slot-and-documented-part-class
  (let [card (card-alias)]
    (is (some? card) "Card alias should exist")
    (when card
      (let [rendered (card-html {}
                                [:img {:slot "media" :src "/policy.png" :alt "Policy"}]
                                [:h2 {:slot :header :id "card-title"} "Policy"]
                                [:button {:slot "header-actions" :type "button"} "Edit"]
                                [:p {:data-body true} "Policy details"]
                                [:span {:slot "footer" :data-footer true} "Updated today"]
                                [:a {:slot "footer-actions" :href "/policy"} "Open"])
            positions (mapv #(str/index-of rendered %)
                            ["class=\"media\""
                             "class=\"header has-actions\""
                             "class=\"body\""
                             "class=\"footer has-actions\""])]
        (is (every? some? positions))
        (is (apply < positions))
        (is (= ["media" "header" "header-actions" "footer" "footer-actions"]
               (mapv second (re-seq #"slot=\"([^\"]+)\"" rendered))))
        (is (str/includes? rendered "src=\"/policy.png\""))
        (is (str/includes? rendered "id=\"card-title\""))
        (is (str/includes? rendered "type=\"button\""))
        (is (str/includes? rendered "data-body"))
        (is (str/includes? rendered "data-footer"))
        (is (str/includes? rendered "href=\"/policy\""))
        (is (not (str/includes? rendered "part=")))))))

(deftest vertical-card-omits-empty-optional-sections
  (let [card (card-alias)]
    (is (some? card) "Card alias should exist")
    (when card
      (let [rendered (card-html {} nil [:p "Body"] nil)]
        (is (str/includes? rendered "<div class=\"body\"><p>Body</p></div>"))
        (is (not (str/includes? rendered "class=\"media\"")))
        (is (not (str/includes? rendered "class=\"header")))
        (is (not (str/includes? rendered "class=\"footer")))))))

(deftest vertical-card-renders-sections-from-slotted-content
  (let [card (card-alias)]
    (is (some? card) "Card alias should exist")
    (when card
      (let [rendered (card-html {}
                                [:img {:slot "media" :src "/policy.png" :alt ""}]
                                [:h2 {:slot "header"} "Policy"]
                                [:button {:slot "header-actions"} "Edit"]
                                "Body"
                                [:span {:slot "footer"} "Footer"]
                                [:a {:slot "footer-actions" :href "/"} "Open"])]
        (is (= ["media" "header has-actions" "body" "footer has-actions"]
               (mapv second (re-seq #"class=\"(media|header has-actions|body|footer has-actions)\"" rendered))))))))

(deftest horizontal-card-renders-media-body-and-actions-slots
  (let [card (card-alias)]
    (is (some? card) "Card alias should exist")
    (when card
      (let [rendered (card-html {:orientation "horizontal" :appearance "filled"}
                                [:img {:slot "media" :src "/policy.png" :alt "Policy"}]
                                [:h2 {:slot "header"} "Hidden header"]
                                [:p "Policy details"]
                                [:button {:slot "actions" :type "button"} "Open actions"]
                                [:span {:slot "footer"} "Hidden footer"])]
        (is (str/includes? rendered "orientation=\"horizontal\""))
        (is (str/includes? rendered "appearance=\"filled\""))
        (is (str/includes? rendered "class=\"media\""))
        (is (str/includes? rendered "class=\"body\""))
        (is (str/includes? rendered "class=\"actions\""))
        (is (str/includes? rendered "Open actions</button>"))
        (is (not (str/includes? rendered "Hidden header")))
        (is (not (str/includes? rendered "Hidden footer")))
        (is (= ["media" "actions"]
               (mapv second (re-seq #"slot=\"([^\"]+)\"" rendered))))))))

(deftest unsupported-slot-content-falls-back-to-the-card-body
  (let [card (card-alias)]
    (is (some? card) "Card alias should exist")
    (when card
      (let [rendered (card-html {}
                                [:span {:slot "unknown" :data-extra true} "Extra"])]
        (is (str/includes? rendered "<div class=\"body\">"))
        (is (str/includes? rendered "slot=\"unknown\""))
        (is (str/includes? rendered "data-extra"))
        (is (str/includes? rendered ">Extra</span>"))))))
