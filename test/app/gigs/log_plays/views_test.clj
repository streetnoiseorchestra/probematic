(ns app.gigs.log-plays.views-test
  (:require
   [app.gigs.log-plays.views :as views]
   [app.gigs.view-test-support :as support]
   [clojure.test :refer [deftest is testing]]))

(deftest log-plays-page-surface
  (testing "Log Plays keeps the gig as context and offers one Done action."
    (let [{:keys [conn]} (support/new-system "gig-log-plays-surface")
          gig-id         (random-uuid)]
      (support/seed-gig! conn gig-id)
      (is (= {:width       :wide
              :breadcrumbs [:gigs/title "Summer Concert" :gigs/log-plays]
              :mobile      {:label "Summer Concert"
                            :href  (str "/gig/" gig-id)}
              :actions     [{:label      :action/done
                             :href       (str "/gig/" gig-id)
                             :appearance "filled"
                             :variant    "brand"}]
              :overflow    []}
             (-> conn
                 (support/request {:path-params {:gig/gig-id gig-id}})
                 views/page
                 support/page-contract))))))
