(ns app.stats.state-test
  (:require
   [app.stats.state :as state]
   [clojure.test :refer [deftest is]]))

(deftest selected-timespan-id-is-url-driven-with-safe-default
  (is (= ["last-three-months" "last-six-months" "last-three-months"]
         [(state/selected-timespan-id {:query-params {}})
          (state/selected-timespan-id {:query-params {"timespan" "last-six-months"}})
          (state/selected-timespan-id {:query-params {"timespan" "not-a-timespan"}})])))

(deftest sort-spec-defaults-and-parses-known-fields
  (is (= [[{:field :member/name :order :asc}]
          [{:field :gigs-attended :order :desc}]
          [{:field :member/name :order :asc}]]
         [(state/sort-spec {:query-params {}})
          (state/sort-spec {:query-params {"sort" "gigs-attended:desc"}})
          (state/sort-spec {:query-params {"sort" "unknown:desc"}})])))

(deftest state-links-preserve-relevant-query-state
  (let [req {:query-params {"timespan" "last-six-months"
                            "sort"     "name:asc"}}]
    (is (= {:timespan-last-year "/stats?sort=name%3Aasc&timespan=last-one-year"
            :sort-name          "/stats?sort=name%3Adesc&timespan=last-six-months"
            :sort-probes        "/stats?sort=probes-attended%3Adesc&timespan=last-six-months"}
           {:timespan-last-year (state/timespan-url req "last-one-year")
            :sort-name          (state/sort-url req :member/name)
            :sort-probes        (state/sort-url req :probes-attended)}))))
