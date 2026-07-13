(ns app.gigs.index.views-test
  (:require
   [app.gigs.index.views :as views]
   [app.gigs.view-test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]))

(deftest gigs-index-page-surface
  (testing "The complete gigs directory has collection context and creation actions."
    (let [{:keys [conn]} (support/new-system "gigs-index-surface")]
      (is (= {:width       :wide
              :breadcrumbs [:gigs/dashboard :gigs/title]
              :mobile      {:label :gigs/dashboard :href "/"}
              :actions     [{:label      :gigs/new-gig
                             :href       "/gigs/create"
                             :appearance "filled"
                             :variant    "brand"}]
              :overflow    [{:label :gigs/view-archive
                             :value "/gigs/archive"}]}
             (-> conn support/request views/page page-shell/page-contract))))))
