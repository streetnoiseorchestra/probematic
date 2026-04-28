(ns app.routes.datastar-test
  (:require
   [app.datastar :as datastar]
   [app.gigs.routes :as gigs.routes]
   [app.members.routes :as members.routes]
   [app.nexus :as app-nexus]
   [app.routes.datastar :as dsr]
   [app.settings.routes :as settings.routes]
   [app.urls :as urls]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is]]
   [reitit.core :as r]
   [reitit.http :as http]))

(defn route-signature [route]
  {:path         (first route)
   :name         (get-in route [1 :name])
   :child-routes (into #{} (map (fn [[path data]]
                                  {:path path
                                   :name (:name data)}))
                       (drop 2 route))})

(deftest settings-routes-expose-the-band-settings-index-and-subpages
  (let [routes (drop 2 (settings.routes/routes))]
    (is (= #{{:path         "/band-settings"
              :name         :app.settings.routes/index
              :child-routes #{{:path ""
                               :name nil}}}
             {:path         "/band-settings/teams"
              :name         :app.settings.routes/teams
              :child-routes #{{:path ""
                               :name nil}}}
             {:path         "/band-settings/travel-discounts"
              :name         :app.settings.routes/travel-discounts
              :child-routes #{{:path ""
                               :name nil}}}
             {:path         "/band-settings/sections"
              :name         :app.settings.routes/sections
              :child-routes #{{:path ""
                               :name nil}}}}
           (into #{} (map route-signature) routes)))))

(deftest settings-subpages-share-the-band-settings-navigation-route
  (let [router (http/router ["" (settings.routes/routes)])]
    (is (= :app/band-settings
           (get-in (r/match-by-path router "/band-settings") [:data :app.route/name])))
    (is (= :app.settings.routes/index
           (get-in (r/match-by-path router "/band-settings") [:data :name])))
    (is (= :app/band-settings
           (get-in (r/match-by-path router "/band-settings/teams") [:data :app.route/name])))
    (is (= :app.settings.routes/teams
           (get-in (r/match-by-path router "/band-settings/teams") [:data :name])))
    (is (= :app.settings.routes/travel-discounts
           (get-in (r/match-by-path router "/band-settings/travel-discounts") [:data :name])))
    (is (= :app.settings.routes/sections
           (get-in (r/match-by-path router "/band-settings/sections") [:data :name])))))

(deftest act-handler-dispatches-the-registered-action-from-query-params
  (let [calls  (atom [])
        config {:nexus/system->state identity
                :nexus/effects       {::record  (fn [_ctx _system data]
                                                  (swap! calls conj [:record data])
                                                  nil)
                                      ::respond (fn [_ctx _system data]
                                                  (swap! calls conj [:respond data])
                                                  {:status 200
                                                   :body   data})}
                :nexus/actions       {::ping (fn [_state body]
                                               [[::record body]
                                                [::respond {:ok true}]])}}
        req    {:system  {:nexus config}
                :query-params (datastar/action-query-params ::ping)
                :body-params {:received true}}]
    (is (= [[::ping {:received true}]]
           (dsr/act-handler req)))
    (is (= {:status 200
            :body   {:ok true}}
           (tc/dispatch-with-nexus dsr/act-handler config (:system req) req)))
    (is (= [[:record {:received true}]
            [:respond {:ok true}]]
           @calls))))

(deftest act-route-installs-nexus-as-route-interceptor
  (let [config {:nexus/system->state identity
                :nexus/actions       {}}
        [_path route-data] (dsr/act-route {:nexus config})]
    (is (= :app.routes.datastar/act (:name route-data)))
    (is (nil? (:middleware route-data)))
    (is (= [::app-nexus/nexus-interceptor]
           (mapv :name (:interceptors route-data))))))

(deftest act-helper-builds-url-from-the-named-act-route
  (let [config {:nexus/system->state identity
                :nexus/actions       {}}
        router (http/router ["" (dsr/act-route {:nexus config})])
        req    {::r/router router}]
    (is (= "/act?ns=app.routes.datastar-test&kw=ping"
           (datastar/act req ::ping)))))

(deftest gigs-routes-expose-the-datastar-index-and-legacy-compatibility-paths
  (let [router (http/router ["" (gigs.routes/routes)])]
    (is (= :app/gigs
           (get-in (r/match-by-path router "/gigs") [:data :app.route/name])))
    (is (= :app.gigs.routes/index
           (get-in (r/match-by-path router "/gigs") [:data :name])))
    (is (= :app/gigs
           (get-in (r/match-by-path router "/gigs/archive") [:data :app.route/name])))
    (is (= :app.gigs.routes/archive
           (get-in (r/match-by-path router "/gigs/archive") [:data :name])))
    (is (= :app.gigs.routes/archive-year
           (get-in (r/match-by-path router "/gigs/archive/2025") [:data :name])))
    (let [gig-id (random-uuid)]
      (is (= :app/gigs
             (get-in (r/match-by-path router (str "/gig/" gig-id)) [:data :app.route/name])))
      (is (= :app.gigs.routes/detail
             (get-in (r/match-by-path router (str "/gig/" gig-id)) [:data :name])))
      (is (= :app/gigs
             (get-in (r/match-by-path router (str "/gig/" gig-id "/")) [:data :app.route/name])))
      (is (= :app.gigs.routes/detail-trailing-slash
             (get-in (r/match-by-path router (str "/gig/" gig-id "/")) [:data :name])))
      (is (= :app/gigs
             (get-in (r/match-by-path router (str "/gig/" gig-id "/edit")) [:data :app.route/name])))
      (is (= :app.gigs.routes/edit
             (get-in (r/match-by-path router (str "/gig/" gig-id "/edit")) [:data :name])))
      (is (= :app.gigs.routes/probeplan
             (get-in (r/match-by-path router (str "/gig/" gig-id "/probeplan")) [:data :name])))
      (is (= :app.gigs.routes/setlist
             (get-in (r/match-by-path router (str "/gig/" gig-id "/setlist")) [:data :name])))
      (is (= :app.gigs.routes/log-plays
             (get-in (r/match-by-path router (str "/gig/" gig-id "/log-plays")) [:data :name])))
      (is (= :app/gigs
             (get-in (r/match-by-path router "/gigs/create") [:data :app.route/name])))
      (is (= :app.gigs.routes/create
             (get-in (r/match-by-path router "/gigs/create") [:data :name])))
      (is (seq (get-in (r/match-by-path router (str "/gig/" gig-id "/edit")) [:data :extra-head])))
      (is (= :app/gigs
             (get-in (r/match-by-path router (str "/gig-legacy/" gig-id "/")) [:data :app.route/name])))
      (is (= :app/gigs
             (get-in (r/match-by-path router (str "/gig/" gig-id "/log-play")) [:data :app.route/name]))))
    (is (= :app/gigs
           (get-in (r/match-by-path router "/gigs-legacy") [:data :app.route/name])))
    (is (= :app/gigs
           (get-in (r/match-by-path router "/gigs-legacy/new") [:data :app.route/name])))
    (is (= :app/gigs
           (get-in (r/match-by-path router "/gigs-legacy/archive") [:data :app.route/name])))))

(deftest gig-helpers-point-to-public-index-archive-and-create
  (is (= "/gigs"
         (urls/link-gigs-home)))
  (is (= "/gigs/create"
         (urls/link-gig-create)))
  (is (= "/gigs/archive"
         (urls/link-gig-archive)))
  (is (= "/gigs/archive/2025"
         (urls/link-gig-archive-year 2025)))
  (let [gig-id (random-uuid)]
    (is (= (str "/gig/" gig-id "/")
           (urls/link-gig gig-id)))
    (is (= (str "/gig/" gig-id "/edit")
           (urls/link-gig-edit gig-id)))
    (is (= (str "/gig/" gig-id "/probeplan")
           (urls/link-gig-probeplan gig-id)))
    (is (= (str "/gig/" gig-id "/setlist")
           (urls/link-gig-setlist gig-id)))
    (is (= (str "/gig/" gig-id "/log-plays")
           (urls/link-gig-log-plays gig-id)))))

(deftest members-routes-expose-the-datastar-index-invite-and-detail-paths
  (let [router (http/router ["" (members.routes/routes)])]
    (is (= :app/members
           (get-in (r/match-by-path router "/members") [:data :app.route/name])))
    (is (= :app.members.routes/index
           (get-in (r/match-by-path router "/members") [:data :name])))
    (is (= :app/members
           (get-in (r/match-by-path router "/members/invite") [:data :app.route/name])))
    (is (= :app.members.routes/invite
           (get-in (r/match-by-path router "/members/invite") [:data :name])))
    (is (nil? (r/match-by-path router "/members-old")))
    (let [member-id (random-uuid)]
      (is (= :app/members
             (get-in (r/match-by-path router (str "/member/" member-id)) [:data :app.route/name])))
      (is (= :app.members.routes/detail
             (get-in (r/match-by-path router (str "/member/" member-id)) [:data :name])))
      (is (= :app/members
             (get-in (r/match-by-path router (str "/member/" member-id "/")) [:data :app.route/name])))
      (is (= :app.members.routes/detail-trailing-slash
             (get-in (r/match-by-path router (str "/member/" member-id "/")) [:data :name])))
      (is (nil? (r/match-by-path router (str "/member-old/" member-id)))))))

(deftest members-unauthenticated-routes-expose-invite-accept
  (let [router (http/router ["" (members.routes/unauthenticated-routes)])]
    (is (= :app/invite-accept
           (get-in (r/match-by-path router "/invite-accept") [:data :app.route/name])))))
