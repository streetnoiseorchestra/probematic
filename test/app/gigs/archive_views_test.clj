(ns app.gigs.archive-views-test
  (:require
   [app.gigs.archive.views :as views]
   [app.gigs.view-test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]))

(deftest gigs-archive-page-surface
  (testing "The gig archive returns to the complete gigs directory."
    (let [{:keys [conn]} (support/new-system "gigs-archive-surface")]
      (is (= {:width       :standard
              :breadcrumbs [:gigs/title :gigs/archive-title]
              :mobile      {:label :gigs/title :href "/gigs"}
              :actions     []
              :overflow    []}
             (-> conn support/request views/page page-shell/page-contract))))))

(deftest gigs-archive-year-page-surface
  (testing "An archive year uses responsive breadcrumb limits and links back to the archive."
    (let [{:keys [conn]} (support/new-system "gigs-archive-year-surface")]
      (is (= {:width                :standard
              :breadcrumbs [:gigs/title :gigs/archive-title "2025"]
              :mobile               {:label :gigs/archive-title :href "/gigs/archive"}
              :actions              []
              :overflow             []}
             (-> conn
                 (support/request {:path-params {:year "2025"}})
                 views/page
                 page-shell/page-contract)))
      (is (= [2 3]
             (-> conn
                 (support/request {:path-params {:year "2025"}})
                 views/page
                 page-shell/breadcrumb-max-items))))))
