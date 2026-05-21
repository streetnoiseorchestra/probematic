(ns app.routes
  (:require
   [app.auth :as auth]
   [app.dashboard.routes :as dashboard]
   [app.datastar :as ds]
   [app.file-browser.routes :as file-browser]
   [app.gigs.routes :as gigs]
   [app.insurance.routes :as insurance]
   [app.interceptors :as interceptors]
   [app.interceptors.compression :as compression]
   [app.members.routes :as members]
   [app.nextcloud :as nextcloud]
   [app.poll.routes :as polls]
   [app.probeplan.routes :as probeplan]
   [app.routes.datastar :as datastar-routes]
   [app.routes.errors :as errors]
   [app.settings.routes :as settings]
   [app.songs.routes :as songs]
   [app.stats.routes :as stats]
   [reitit.coercion :as coercion]
   [reitit.http :as http]
   [reitit.interceptor.sieppari :as sieppari]
   [reitit.ring :as ring]))

(defn routes [system]
  ["" {:coercion     (interceptors/default-coercion)
       :muuntaja     interceptors/formats-instance
       :interceptors (into [] (concat (interceptors/default-reitit-interceptors system)
                                      [(auth/session-interceptor system)
                                       (interceptors/system-interceptor system)
                                       (interceptors/datomic-interceptor system)
                                       (interceptors/filestore-interceptor system)
                                       (interceptors/current-user-interceptor system)
                                       (ds/datastar-refresh-interceptor system)]))}

   (auth/routes system)
   (gigs/unauthenticated-routes system)
   (insurance/unauthenticated-routes)
   (members/unauthenticated-routes)

   ["" {:interceptors [(interceptors/webdav-interceptor system)]}
    (songs/unauthenticated-routes)]

   ["" {:interceptors [auth/require-authenticated-user
                       (interceptors/webdav-interceptor system)]}

    (datastar-routes/act-route system)
    (dashboard/routes)
    (settings/routes)
    (file-browser/routes)
    (gigs/routes)
    (insurance/routes)
    (members/routes)
    (polls/routes)
    (stats/routes)
    (nextcloud/routes)
    (probeplan/routes)
    (songs/routes)
    (errors/routes)]])

(def parameter-coercion
  "This lets us do the following in our handlers
               :get  {:handler    view/view-fn
                      :parameters {:datastar {:team-id :uuid}}}
  "
  (assoc coercion/default-parameter-coercion
         :datastar (coercion/->ParameterCoercion :datastar-params :string true true)))

(defn default-handler [system]
  (http/ring-handler
   (http/router (routes system) {::coercion/parameter-coercion parameter-coercion

                                 #_#_:reitit.interceptor/transform diff/print-context-diffs})
   (ring/routes
    (ring/create-resource-handler {:path "/"})
    (ring/redirect-trailing-slash-handler)
    (ring/create-default-handler))
   {:executor     sieppari/executor
    :interceptors [compression/compress-response-interceptor
                   interceptors/cache-control-interceptor]}))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (require '[reitit.core :as r])
    (require '[reitit.http :as http])
    (require '[app.urls :as url])
    (def _routes (-> state/system :app.ig.router/routes :routes))
    (def _router (-> state/system :app.ig.router/routes :router))
    (def env (-> state/system :app.ig/env))
    (tap> _routes)) ;; rcf
  (-> state/system :app.ig/handler)
  ;;
  )
