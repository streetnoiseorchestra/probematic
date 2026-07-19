(ns app.members.routes
  (:require
   [app.datomic.shim :as d]
   [app.members.detail.views :as detail.views]
   [app.members.domain :as members.domain]
   [app.members.index.views :as index.views]
   [app.members.invite.views :as invite.views]
   [app.queries :as q]
   [app.routes.datastar :as ds]
   [app.sardine :as sardine]
   [app.util.http :as http.util]))

(defn member-vcard [{:keys [db] :as req}]
  (let [member-id (http.util/path-param-uuid! req :member-id)
        member    (q/retrieve-member db member-id)
        nick      (:member/nick member)
        vcard     (members.domain/generate-vcard member)]
    {:status  200
     :headers {"Content-Disposition" (sardine/content-disposition-filename "attachment" (str nick ".vcf"))
               "Content-Type"        "text/x-vcard"}
     :body    vcard}))

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
   (ds/page-routes {:page-name ::index
                    :path      "/members"
                    :page      #'index.views/page})
   (ds/page-routes {:page-name ::invite
                    :path      "/members/invite"
                    :page      #'invite.views/page})
   ["" {:interceptors members-interceptors}
    ["/member-vcard/{member-id}" {:app.route/name :app/member-vcard
                                  :get            member-vcard}]
    (ds/page-routes {:page-name ::detail
                     :path      "/member/{member-id}"
                     :page      #'detail.views/page})
    (ds/page-routes {:page-name ::detail-tab
                     :path      "/member/{member-id}/{member-detail-tab}"
                     :page      #'detail.views/page})]])

(defn unauthenticated-routes []
  [""
   ["/invite-accept" {:app.route/name :app/invite-accept
                      :get            invite.views/invite-accept
                      :post           invite.views/invite-accept-post}]])
