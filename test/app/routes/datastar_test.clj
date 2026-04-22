(ns app.routes.datastar-test
  (:require
   [app.datastar :as datastar]
   [app.nexus :as app-nexus]
   [app.queries :as q]
   [app.routes.datastar :as dsr]
   [app.settings.routes :as settings.routes]
   [app.settings.views :as settings.views]
   [app.test-common :as tc]
   [clojure.string :as str]
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

(deftest settings-routes-preserve-the-settings-route-shape
  (let [route (last (settings.routes/routes))]
    (is (= {:path         "/band-settings"
            :name         :app.settings.routes/band-settings
            :child-routes #{{:path ""
                             :name nil}}}
           (route-signature route)))))

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
    (is (= "@post('/act?ns=app.routes.datastar-test&kw=ping')"
           (datastar/act req ::ping)))))

(deftest settings-page-renders-command-urls-through-the-act-route
  (let [config {:nexus/system->state identity
                :nexus/actions       {}}
        router (http/router ["" (dsr/act-route {:nexus config})])
        req    {::r/router router
                :tr       (fn [k & _] (pr-str k))
                :db       nil
                :page-state {}}]
    (with-redefs [q/retrieve-all-teams (constantly [])
                  q/retrieve-all-discount-types (constantly [])
                  q/retrieve-sections (constantly [])]
      (let [html (settings.views/page req)]
        (is (str/includes? html "/act?ns=app.settings.actions"))
        (is (str/includes? html "create-team"))
        (is (str/includes? html "create-discount-type"))
        (is (str/includes? html "create-section"))))))
