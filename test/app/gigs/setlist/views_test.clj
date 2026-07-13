(ns app.gigs.setlist.views-test
  (:require
   [app.gigs.setlist.views :as views]
   [app.gigs.view-test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]))

(deftest gig-setlist-page-surface
  (testing "Make Setlist keeps the gig as context and offers one Done action."
    (let [{:keys [conn]} (support/new-system "gig-setlist-surface")
          gig-id         (random-uuid)]
      (support/seed-gig! conn gig-id)
      (is (= {:width       :wide
              :breadcrumbs [:gigs/title "Summer Concert" :gigs/setlist]
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
                 page-shell/page-contract))))))
