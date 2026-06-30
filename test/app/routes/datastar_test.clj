(ns app.routes.datastar-test
  (:require
   [app.dashboard.routes :as dashboard.routes]
   [app.datastar :as datastar]
   [app.gigs.routes :as gigs.routes]
   [app.members.routes :as members.routes]
   [app.insurance.routes :as insurance.routes]
   [app.nexus :as app-nexus]
   [app.probeplan.routes :as probeplan.routes]
   [app.poll.routes :as poll.routes]
   [app.routes.datastar :as dsr]
   [app.settings.routes :as settings.routes]
   [app.songs.routes :as songs.routes]
   [app.stats.routes :as stats.routes]
   [app.test-common :as tc]
   [app.urls :as urls]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [reitit.core :as r]
   [reitit.http :as http]
   [reitit.ring :as ring]))

(def test-req
  {:system  {:env {:ig/system {:app.ig/profile :test}}}
   :tr      pr-str
   :session {:session/member {:member/name "Test Member"
                              :member/nick "Tester"}}})

(defn page [_req]
  [:section {:id "datastar-toggle-fixture"}
   "Datastar toggle fixture"])

(defn response-body-string [response]
  (let [body (:body response)]
    (cond
      (string? body) body
      (instance? java.io.InputStream body) (slurp body)
      :else (str body))))

(defn page-get-response []
  (let [[_path _route-data [_child-path child-data]]
        (dsr/page-routes {:page-name ::toggle-fixture
                          :path      "/toggle-fixture"
                          :view-ns   'app.routes.datastar-test})]
    ((:get child-data) test-req)))

(deftest page-get-can-render-full-page-when-shim-disabled
  (binding [dsr/*use-page-shim?* false]
    (let [response (page-get-response)
          body     (response-body-string response)]
      (is (= {:status         200
              :content-type   "text/html"
              :contains-page? true
              :contains-sse?  true
              :contains-morph? true}
             {:status         (:status response)
              :content-type   (get-in response [:headers "Content-Type"])
              :contains-page? (str/includes? body "Datastar toggle fixture")
              :contains-sse?  (str/includes? body "long-lived-sse")
              :contains-morph? (str/includes? body "id=\"morph\"")})))))

(defn route-signature [route]
  {:path         (first route)
   :name         (get-in route [1 :name])
   :child-routes (into #{} (map (fn [[path data]]
                                  {:path path
                                   :name (:name data)}))
                       (drop 2 route))})

(defn page-name [router path]
  (get-in (r/match-by-path router path) [:data :name]))

(defn app-route-name [router path]
  (get-in (r/match-by-path router path) [:data :app.route/name]))

(defn slash-redirect-summary [router path]
  (let [response ((ring/redirect-trailing-slash-handler)
                  {::r/router router
                   :request-method :get
                   :uri path})]
    {:status   (:status response)
     :location (get-in response [:headers "Location"])}))

(defn assert-slashless-canonical-route [router path slashed-path expected-page-name]
  (is (= expected-page-name
         (page-name router path)))
  (is (nil? (r/match-by-path router slashed-path)))
  (is (= {:status   301
          :location path}
         (slash-redirect-summary router slashed-path))))

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

(deftest probeplan-route-exposes-the-datastar-index
  (let [router (http/router ["" (probeplan.routes/routes)])
        match  (r/match-by-path router "/probeplan")]
    (is (= {:route-name :app/probeplan
            :page-name  :app.probeplan.routes/index}
           {:route-name (get-in match [:data :app.route/name])
            :page-name  (get-in match [:data :name])}))))

(deftest stats-route-exposes-the-datastar-index
  (let [router (http/router ["" (stats.routes/routes)])
        match  (r/match-by-path router "/stats")]
    (is (= {:route-name :app/stats
            :page-name  :app.stats.routes/index}
           {:route-name (get-in match [:data :app.route/name])
            :page-name  (get-in match [:data :name])}))))

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
        req    {:system       {:nexus config}
                :query-params (datastar/action-query-params ::ping)
                :body-params  {:received true}}]
    (is (= [[::ping {:received true}]]
           (dsr/act-handler req)))
    (is (= [[::ping {:received true
                     :query-params {"q" "Wedding"}}]]
           (dsr/act-handler
            (assoc req :query-params (assoc (datastar/action-query-params ::ping)
                                            "q" "Wedding")))))
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

(deftest gigs-routes-expose-the-datastar-index-and-detail-paths
  (let [router (http/router ["" (gigs.routes/routes)])]
    (is (= :app/gigs
           (app-route-name router "/gigs")))
    (is (= :app.gigs.routes/index
           (page-name router "/gigs")))
    (is (= :app/gigs
           (app-route-name router "/gigs/archive")))
    (is (= :app.gigs.routes/archive
           (page-name router "/gigs/archive")))
    (is (= :app.gigs.routes/archive-year
           (page-name router "/gigs/archive/2025")))
    (let [gig-id      (random-uuid)
          detail-path (str "/gig/" gig-id)]
      (is (= :app/gigs
             (app-route-name router detail-path)))
      (assert-slashless-canonical-route router
                                        detail-path
                                        (str detail-path "/")
                                        :app.gigs.routes/detail)
      (is (= :app/gigs
             (app-route-name router (str "/gig/" gig-id "/edit"))))
      (is (= :app.gigs.routes/edit
             (page-name router (str "/gig/" gig-id "/edit"))))
      (is (= :app.gigs.routes/probeplan
             (page-name router (str "/gig/" gig-id "/probeplan"))))
      (is (= :app.gigs.routes/setlist
             (page-name router (str "/gig/" gig-id "/setlist"))))
      (is (= :app.gigs.routes/log-plays
             (page-name router (str "/gig/" gig-id "/log-plays"))))
      (is (= :app/gigs
             (app-route-name router "/gigs/create")))
      (is (= :app.gigs.routes/create
             (page-name router "/gigs/create")))
      (is (nil? (r/match-by-path router (str "/gig/" gig-id "/log-play")))))))

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
    (is (= (str "/gig/" gig-id)
           (urls/link-gig gig-id)))
    (is (= (str "/gig/" gig-id "/edit")
           (urls/link-gig-edit gig-id)))
    (is (= (str "/gig/" gig-id "/probeplan")
           (urls/link-gig-probeplan gig-id)))
    (is (= (str "/gig/" gig-id "/setlist")
           (urls/link-gig-setlist gig-id)))
    (is (= (str "/gig/" gig-id "/log-plays")
           (urls/link-gig-log-plays gig-id)))
    (is (= (str "https://example.test/gig/" gig-id)
           (urls/absolute-link-gig {:app-base-url "https://example.test"} gig-id)))
    (is (= (str "https://example.test/gig/" gig-id "/log-plays")
           (urls/absolute-link-gig-log-plays {:app-base-url "https://example.test"} gig-id)))))

(deftest insurance-routes-expose-policy-review-coverage-detail-and-edit-datastar-pages
  (let [router      (http/router ["" (insurance.routes/routes)])
        policy-id   (random-uuid)
        coverage-id (random-uuid)]
    (is (= :app/insurance
           (app-route-name router (urls/link-policy-review policy-id))))
    (is (= :app.insurance.routes/policy-review
           (page-name router (urls/link-policy-review policy-id))))
    (is (= (str "/insurance-policy/" policy-id "/review?filter=changed&coverage-id=" coverage-id)
           (urls/link-policy-review policy-id {:filter :changed :coverage-id coverage-id})))
    (is (= :app/instrument.coverage
           (app-route-name router (str "/insurance-coverage/" coverage-id "/"))))
    (is (= :app.insurance.routes/coverage-detail
           (page-name router (str "/insurance-coverage/" coverage-id "/"))))
    (is (= :app/instrument.coverage
           (app-route-name router (str "/insurance-coverage-edit/" coverage-id "/"))))
    (is (= :app.insurance.routes/coverage-edit
           (page-name router (str "/insurance-coverage-edit/" coverage-id "/"))))))
(deftest dashboard-routes-expose-the-slashless-calendar-path
  (let [router (http/router ["" (dashboard.routes/routes)])]
    (is (= :app.dashboard.routes/index
           (page-name router "/")))
    (is (= :app/dashboard
           (app-route-name router "/calendar")))
    (assert-slashless-canonical-route router
                                      "/calendar"
                                      "/calendar/"
                                      :app.dashboard.routes/calendar)))

(deftest songs-routes-expose-slashless-index-create-detail-and-edit-paths
  (let [router (http/router ["" (songs.routes/routes)])]
    (is (= :app/songs
           (app-route-name router "/songs")))
    (assert-slashless-canonical-route router
                                      "/songs"
                                      "/songs/"
                                      :app.songs.routes/index)
    (is (= :app.songs.routes/create
           (page-name router "/songs/new")))
    (let [song-id      (random-uuid)
          detail-path  (str "/song/" song-id)
          edit-path    (str detail-path "/edit")]
      (is (= :app/songs
             (app-route-name router detail-path)))
      (assert-slashless-canonical-route router
                                        detail-path
                                        (str detail-path "/")
                                        :app.songs.routes/detail)
      (is (= :app.songs.routes/edit
             (page-name router edit-path))))))

(deftest song-helpers-point-to-slashless-canonical-paths
  (is (= "/songs"
         (urls/link-songs-home)))
  (is (= "/songs/new"
         (urls/link-song-create)))
  (let [song-id (random-uuid)]
    (is (= (str "/song/" song-id)
           (urls/link-song song-id)))
    (is (= (str "/song/" song-id "/edit")
           (urls/link-song-edit song-id)))
    (is (= (str "https://example.test/song/" song-id)
           (urls/absolute-link-song {:app-base-url "https://example.test"} song-id)))))

(deftest polls-routes-expose-the-datastar-index-create-detail-and-edit-paths
  (let [router (http/router ["" (poll.routes/routes)])]
    (is (= :app/polls
           (get-in (r/match-by-path router "/polls") [:data :app.route/name])))
    (is (= :app.poll.routes/index
           (get-in (r/match-by-path router "/polls") [:data :name])))
    (is (= :app.poll.routes/create
           (get-in (r/match-by-path router "/polls/new") [:data :name])))
    (let [poll-id (random-uuid)]
      (is (= :app/polls
             (get-in (r/match-by-path router (str "/poll/" poll-id)) [:data :app.route/name])))
      (is (= :app.poll.routes/detail
             (get-in (r/match-by-path router (str "/poll/" poll-id)) [:data :name])))
      (is (= :app.poll.routes/edit
             (get-in (r/match-by-path router (str "/poll/" poll-id "/edit")) [:data :name]))))))

(deftest poll-helpers-point-to-public-index-create-detail-and-edit
  (is (= "/polls"
         (urls/link-polls-home)))
  (is (= "/polls/new"
         (urls/link-polls-create)))
  (let [poll-id (random-uuid)]
    (is (= (str "/poll/" poll-id)
           (urls/link-poll poll-id)))
    (is (= (str "/poll/" poll-id "/edit")
           (urls/link-poll-edit poll-id)))))

(deftest members-routes-expose-the-datastar-index-invite-and-detail-paths
  (let [router (http/router ["" (members.routes/routes)])]
    (is (= :app/members
           (app-route-name router "/members")))
    (is (= :app.members.routes/index
           (page-name router "/members")))
    (is (= :app/members
           (app-route-name router "/members/invite")))
    (is (= :app.members.routes/invite
           (page-name router "/members/invite")))
    (is (nil? (r/match-by-path router "/members-old")))
    (let [member-id   (random-uuid)
          detail-path (str "/member/" member-id)]
      (is (= :app/members
             (app-route-name router detail-path)))
      (assert-slashless-canonical-route router
                                        detail-path
                                        (str detail-path "/")
                                        :app.members.routes/detail)
      (is (nil? (r/match-by-path router (str "/member-old/" member-id)))))))

(deftest member-and-calendar-helpers-point-to-slashless-canonical-paths
  (is (= "/calendar"
         (urls/link-calendar)))
  (is (= "/insurance"
         (urls/link-insurance)))
  (is (= "/insurance#faq10"
         (urls/link-faq-insurance-team)))
  (let [member-id (random-uuid)]
    (is (= (str "/member/" member-id)
           (urls/link-member member-id)))
    (is (= (str "/member/" member-id "/money")
           (urls/link-member-money member-id)))
    (is (= (str "/member/" member-id "#member-ledger-panel")
           (urls/link-member-ledger member-id)))
    (is (= (str "/member/" member-id "#member-ledger-table")
           (urls/link-member-ledger-table member-id)))
    (is (= (str "https://example.test/member/" member-id)
           (urls/absolute-link-member {:app-base-url "https://example.test"} member-id)))
    (is (= (str "https://example.test/member/" member-id "#member-ledger-table")
           (urls/absolute-link-member-ledger {:app-base-url "https://example.test"} member-id)))))

(deftest gigs-unauthenticated-routes-expose-slashless-answer-link
  (let [router (http/router ["" (gigs.routes/unauthenticated-routes)])]
    (is (= :app/gig-answer-link
           (app-route-name router "/answer-link")))
    (is (nil? (r/match-by-path router "/answer-link/")))
    (is (= {:status   301
            :location "/answer-link"}
           (slash-redirect-summary router "/answer-link/")))
    (is (nil? (r/match-by-path router "/dev/answer-link")))
    (is (nil? (r/match-by-path router "/dev/answer-link/")))))

(deftest gigs-dev-routes-expose-slashless-answer-link-preview-only-in-dev
  (let [dev-system  {:env {:ig/system {:app.ig/profile :dev}}}
        prod-system {:env {:ig/system {:app.ig/profile :prod}}}
        dev-router  (http/router ["" (gigs.routes/unauthenticated-routes dev-system)])
        prod-router (http/router ["" (gigs.routes/unauthenticated-routes prod-system)])]
    (is (= :app/gig-answer-link-dev
           (app-route-name dev-router "/dev/answer-link")))
    (is (nil? (r/match-by-path dev-router "/dev/answer-link/")))
    (is (= {:status   301
            :location "/dev/answer-link"}
           (slash-redirect-summary dev-router "/dev/answer-link/")))
    (is (nil? (r/match-by-path prod-router "/dev/answer-link")))
    (is (nil? (r/match-by-path prod-router "/dev/answer-link/")))))

(deftest members-unauthenticated-routes-expose-invite-accept
  (let [router (http/router ["" (members.routes/unauthenticated-routes)])]
    (is (= :app/invite-accept
           (get-in (r/match-by-path router "/invite-accept") [:data :app.route/name])))))
