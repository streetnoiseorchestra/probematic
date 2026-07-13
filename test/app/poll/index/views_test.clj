(ns app.poll.index.views-test
  (:require
   [app.poll.index.views :as views]
   [app.poll.view-test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]))

(deftest polls-index-page-surface
  (testing "Polls uses a standard collection surface with New Poll as its primary action."
    (let [system (support/new-system "poll-index-surface")]
      (is (= {:contract {:width       :standard
                         :breadcrumbs [:home :polls/title]
                         :mobile      {:label :home :href "/"}
                         :actions     [{:label      :polls/new-poll
                                        :href       "/polls/new"
                                        :appearance "filled"
                                        :variant    "brand"}]
                         :overflow    []}
              :heading  :polls/title
              :subtitle nil
              :form-id  nil
              :last-tag :app.ui2.page-surface/page-surface
              :last-id  nil}
             (-> system support/request views/page page-shell/page-structure))))))
