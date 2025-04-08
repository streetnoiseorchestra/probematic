(ns app.members.routes
  (:require
   [app.members.index.views]
   [app.members.routes2 :as routes2]
   [app.layout :as layout]
   [app.members.views :as view]
   [app.queries :as q]
   [app.util.http :as http.util]
   [ctmx.core :as ctmx]
   [app.datomic.shim :as d]))

(defn member-vcard-download []
  ["/member-vcard/{member-id}" {:app.route/name :app/member-vcard
                                :get (fn [req]
                                       (view/member-vcard req))}])

(defn members-detail []
  (ctmx/make-routes
   "/member/{member-id}"
   (fn [req]
     (layout/app-shell req
                       (view/members-detail-page req false)))))

(defn members-index []
  (ctmx/make-routes
   "/members-old"
   (fn [req]
     (layout/app-shell req
                       (view/members-index-page req false)))))

(def members-interceptors [{:name ::members--interceptor
                            :enter (fn [ctx]
                                     (let [conn (-> ctx :request :datomic-conn)
                                           db (d/db conn)
                                           member-id (http.util/path-param-uuid! (:request ctx) :member-id)
                                           member (q/retrieve-member db member-id)]
                                       (if member
                                         (assoc-in ctx [:request :member] member)
                                         (throw (ex-info "Member not found" {:app/error-type :app.error.type/not-found
                                                                             :member/member-id member-id})))))}])

(defn routes []
  ["" {:app.route/name :app/members}
   ["" {:interceptors members-interceptors}
    (member-vcard-download)
    (members-detail)]
   (members-index)
   (routes2/routes)])

(defn unauthenticated-routes []
  [""
   ["/invite-accept" {:app.route/name :app/invite-accept
                      :get            (fn [req] (view/invite-accept req))
                      :post           (fn [req] (view/invite-accept-post req))}]])
