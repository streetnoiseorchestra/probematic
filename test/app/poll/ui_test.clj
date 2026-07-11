(ns app.poll.ui-test
  (:require
   [app.poll.ui :as poll.ui]
   [app.ui2.card :as card]
   [clojure.test :refer [deftest is]]))

(deftest poll-section-uses-native-card-chassis
  (let [view      (poll.ui/poll-section
                   {}
                   {:empty-message "No polls"
                    :polls         []
                    :title         "Running polls"})
        card-view (last view)]
    (is (= {:tag   card/Card
            :attrs {:class "polls-list-card"}
            :body  [:div {:class "polls-empty"} "No polls"]}
           {:tag   (first card-view)
            :attrs (second card-view)
            :body  (nth card-view 2)}))))
