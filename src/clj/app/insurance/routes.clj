(ns app.insurance.routes
  (:require
   [app.insurance.index.views]
   [app.insurance.views :as view]
   [app.layout :as layout]
   [app.queries :as q]
   [app.routes.datastar :as ds]
   [ctmx.core :as ctmx]
   [app.datomic.shim :as d]
   [reitit.ring.malli :as reitit.ring.malli]))

(defn insurance-detail []
  [""
   (ctmx/make-routes
    "/insurance-policy/{policy-id}/"
    (fn [req]
      (layout/app-shell req
                        (view/insurance-detail-page req))))])

(defn insurance-notification []
  [""
   (ctmx/make-routes
    "/insurance-policy-notify/{policy-id}/"
    (fn [req]
      (layout/app-shell req
                        (view/insurance-notify-page req))))])

(defn insurance-generate-changes []
  (ctmx/make-routes
   "/insurance-policy-changes/{policy-id}"
   (fn [req]
     (layout/app-shell req
                       (view/insurance-policy-changes-review req)))))

(defn insurance-coverage-detail []
  (ctmx/make-routes
   "/insurance-coverage/{coverage-id}/"
   (fn [req]
     (layout/app-shell req
                       (view/insurance-coverage-detail-page req)))))
(defn insurance-coverage-detail-edit []
  (ctmx/make-routes
   "/insurance-coverage-edit/{coverage-id}/"
   (fn [req]
     (layout/app-shell req
                       (view/insurance-coverage-detail-page-rw req)))))

(defn insurance-coverage-create []
  (ctmx/make-routes
   "/insurance-coverage-create/{policy-id}/"
   (fn [req]
     (layout/app-shell req
                       (view/insurance-coverage-create-page req)))))

(defn insurance-survey []
  (ctmx/make-routes
   "/insurance-survey/{policy-id}/"
   (fn [req]
     (layout/app-shell req (view/survey-start-page req)))))

(defn insurance-coverage-create2 []
  (ctmx/make-routes
   "/insurance-coverage-create2/{policy-id}/{instrument-id}"
   (fn [req]
     (layout/app-shell req
                       (view/image-upload req)))))
(defn insurance-coverage-create3 []
  (ctmx/make-routes
   "/insurance-coverage-create3/{policy-id}/{instrument-id}"
   (fn [req]
     (layout/app-shell req
                       (view/insurance-coverage-create-page3 req)))))

(defn instrument-detail []
  (ctmx/make-routes
   "/instrument/{instrument-id}/"
   (fn [req]
     (layout/app-shell req
                       (view/instrument-detail-page req false)))))

(defn insurance-create []
  (ctmx/make-routes
   "/insurance-new/"
   (fn [req]
     (layout/app-shell req
                       (view/insurance-create-page req)))))

(defn insurance-index []
  (ds/page-routes {:page-name ::index
                   :path      "/insurance"
                   :view-ns   'app.insurance.index.views}))

(defn instrument-create []
  (ctmx/make-routes
   "/instrument-new/"
   (fn [req]
     (layout/app-shell req
                       (view/instrument-create-page req)))))

(def policy-interceptor {:name ::insurance-policy--interceptor
                         :enter (fn [ctx]
                                  (let [conn (-> ctx :request :datomic-conn)
                                        db (d/db conn)]
                                    (try
                                      (let [policy (q/retrieve-policy db (-> ctx :request :path-params :policy-id parse-uuid))]
                                        (assoc-in ctx [:request :policy] policy))
                                      (catch Exception e
                                        (throw (ex-info "Policy not found" {:app/error-type :app.error.type/not-found
                                                                            :policy-id (-> ctx :request :path-params :policy-id)
                                                                            :exception e}))))))})

(def coverage-interceptor {:name ::insurance-coverage--interceptor
                           :enter (fn [ctx]
                                    (let [coverage-id (-> ctx :request :path-params :coverage-id)]
                                      (if-let  [coverage (q/retrieve-coverage (-> ctx :request :datomic-conn d/db) (parse-uuid coverage-id))]
                                        (assoc-in ctx  [:request :coverage] coverage)
                                        (throw (ex-info "Instrument Coverage not found" {:app/error-type :app.error.type/not-found
                                                                                         :instrument.coverage/coverage-id coverage-id})))))})

(def instrument-interceptor {:name ::instrument--interceptor
                             :enter (fn [ctx]
                                      (let [conn (-> ctx :request :datomic-conn)
                                            db (d/db conn)
                                            instrument-id (-> ctx :request :path-params :instrument-id)]
                                        (cond-> ctx
                                          instrument-id (assoc-in  [:request :instrument] (q/retrieve-instrument db (parse-uuid instrument-id))))))})

(defn routes []
  ["" {:app.route/name :app/insurance}
   (insurance-index)
   ["/instrument-image/{instrument-id}"
    {:post {:summary "Upload an image for an instrument"
            :parameters {:multipart [:map [:file reitit.ring.malli/temp-file-part]]
                         :path [:map [:instrument-id :uuid]]}
            :handler (fn [req] (view/image-upload-handler req))}}]
   ["/instrument-image-button/"
    {:post {:summary "Upload an image for an instrument from a single button"
            :parameters {:multipart [:map
                                     [:files [:vector {:decode/string (fn [v] (if (vector? v) v [v]))} reitit.ring.malli/temp-file-part]]
                                     [:instrument-id :uuid]]}
            :handler (fn [req] (view/instrument-image-upload-button-handler req))}}]

   ["" {:interceptors [policy-interceptor]}
    (insurance-survey)
    (insurance-coverage-create)
    (insurance-generate-changes)
    (insurance-detail)
    (insurance-notification)

    ["/insurance-changes-excel-download/{policy-id}/"
     {:get {:summary "Download the changes excel file"
            :parameters {:query [:map
                                 [:preview-type [:enum "new" "changes"]]
                                 [:attachment-filename :string]]}
            :handler (fn [req]
                       (view/insurance-policy-changes-excel-download req))}}]

    ["/insurance-changes-excel/{policy-id}/"
     {:post {:summary "Get the changes excel file"
             :parameters {}
             :handler (fn [req]
                        (view/insurance-policy-changes-file req))}}]]

   ["" {:interceptors [policy-interceptor instrument-interceptor]}
    (insurance-coverage-create2)
    (insurance-coverage-create3)]

   ["" {:app.route/name :app/instrument.coverage
        :interceptors [coverage-interceptor]}
    (insurance-coverage-detail)
    (insurance-coverage-detail-edit)]

   (insurance-create)
   (instrument-create)
   ["" {:app.route/name :app/insurance2
        :interceptors [instrument-interceptor]}

    (instrument-detail)]])

(defn unauthenticated-routes []
  [""
   ["/instrument-public/{instrument-id}"
    [""
     {:get {:summary "The public page for an instrument"
            :parameters {:path [:map [:instrument-id :uuid]]}
            :handler (fn [req]
                       (view/instrument-public-page req (-> req :parameters :path :instrument-id)))}}]
    ["/download-zip" {:get {:summary "Download all photos for an instrument"
                            :parameters {:path [:map [:instrument-id :uuid]]}
                            :handler (fn [req]
                                       (view/instrument-public-page-download-all req (-> req :parameters :path :instrument-id)))}}]]
   ["/instrument-image/{instrument-id}/{image-id}"
    {:get {:summary "Get instrument images"
           :parameters {:path [:map [:instrument-id :uuid] [:image-id :string]]
                        :query [:map [:mode {:optional true} [:enum "full" "thumbnail"]]]}
           :handler (fn [req]
                      (view/image-fetch-handler req))}}]])
