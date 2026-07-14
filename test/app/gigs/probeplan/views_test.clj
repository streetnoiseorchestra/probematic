(ns app.gigs.probeplan.views-test
  (:require
   [app.gigs.probeplan.views :as views]
   [app.gigs.view-test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]))

(deftest gig-probeplan-page-surface
  (testing "Make Probeplan keeps the rehearsal as context and offers one Done action."
    (let [{:keys [conn]} (support/new-system "gig-probeplan-surface")
          gig-id         (random-uuid)]
      (support/seed-gig! conn gig-id {:gig/gig-type :gig.type/probe})
      (is (= {:width       :wide
              :breadcrumbs [:gigs/title "Summer Concert 7/15/26" :gigs/probeplan]
              :mobile      {:label "Summer Concert 7/15/26"
                            :href  (str "/gig/" gig-id)}
              :actions     [{:label      :action/done
                             :href       (str "/gig/" gig-id)
                             :appearance "filled"
                             :variant    "brand"}]
              :overflow    []}
             (-> conn
                 (support/request {:path-params {:gig/gig-id gig-id}})
                 views/page
                 page-shell/page-contract)))
      (is (= [2 3]
             (-> conn
                 (support/request {:path-params {:gig/gig-id gig-id}})
                 views/page
                 page-shell/breadcrumb-max-items))))))
