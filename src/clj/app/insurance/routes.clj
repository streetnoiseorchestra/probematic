(ns app.insurance.routes
  (:require
   [app.datomic.shim :as d]
   [app.insurance.coverage.create.views :as coverage.create.views]
   [app.insurance.coverage.edit.api :as coverage-edit.api]
   [app.insurance.coverage.edit.views :as coverage.edit.views]
   [app.insurance.coverage.views :as coverage.views]
   [app.insurance.index.views :as index.views]
   [app.insurance.policy.dashboard.views :as policy.dashboard.views]
   [app.insurance.policy.review.views :as policy.review.views]
   [app.insurance.policy.settings.views :as policy.settings.views]
   [app.insurance.policy.workbench.views :as policy.workbench.views]
   [app.insurance.public.views :as public]
   [app.insurance.views :as view]
   [app.layout :as layout]
   [app.queries :as q]
   [app.routes.datastar :as ds]
   [ctmx.core :as ctmx]
   [reitit.ring.malli :as reitit.ring.malli]))

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

(defn insurance-survey []
  (ctmx/make-routes
   "/insurance-survey/{policy-id}/"
   (fn [req]
     (layout/app-shell req (view/survey-start-page req)))))

(defn insurance-create []
  (ctmx/make-routes
   "/insurance-new/"
   (fn [req]
     (layout/app-shell req
                       (view/insurance-create-page req)))))

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
   (ds/page-routes {:page-name ::index
                    :path      "/insurance"
                    :page      #'index.views/page})
   ["/instrument-image/{instrument-id}"
    {:post {:summary "Upload an image for an instrument"
            :parameters {:multipart [:map [:file reitit.ring.malli/temp-file-part]]
                         :path [:map [:instrument-id :uuid]]}
            :handler (fn [req] (coverage-edit.api/image-upload-handler req))}}]
   ["/instrument-image-button/"
    {:post {:summary "Upload an image for an instrument from a single button"
            :parameters {:multipart [:map
                                     [:files [:vector {:decode/string (fn [v] (if (vector? v) v [v]))} reitit.ring.malli/temp-file-part]]
                                     [:instrument-id :uuid]]}
            :handler (fn [req] (view/instrument-image-upload-button-handler req))}}]

   ["" {:interceptors [policy-interceptor]}
    (insurance-survey)
    (ds/page-routes {:page-name ::coverage-create-instrument
                     :path      "/insurance-coverage-create/{policy-id}"
                     :page      #'coverage.create.views/instrument-page})
    (insurance-generate-changes)
    (ds/page-routes {:page-name ::policy-review
                     :path      "/insurance-policy/{policy-id}/review"
                     :page      #'policy.review.views/page})
    (ds/page-routes {:page-name ::policy-workbench
                     :path      "/insurance-policy/{policy-id}/workbench"
                     :page      #'policy.workbench.views/page})
    (ds/page-routes {:page-name ::policy-settings
                     :path      "/insurance-policy/{policy-id}/settings"
                     :page      #'policy.settings.views/page})
    (ds/page-routes {:page-name ::policy-dashboard
                     :path      "/insurance-policy/{policy-id}"
                     :page      #'policy.dashboard.views/page})
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
    (ds/page-routes {:page-name ::coverage-create-photos
                     :path      "/insurance-coverage-create2/{policy-id}/{instrument-id}"
                     :page      #'coverage.create.views/photos-page})
    (ds/page-routes {:page-name ::coverage-create-coverage
                     :path      "/insurance-coverage-create3/{policy-id}/{instrument-id}"
                     :page      #'coverage.create.views/coverage-page})]

   ["" {:app.route/name :app/instrument.coverage
        :interceptors [coverage-interceptor]}
    (ds/page-routes {:page-name ::coverage-detail
                     :path      "/insurance-coverage/{coverage-id}/"
                     :page      #'coverage.views/page})
    (ds/page-routes {:page-name ::coverage-edit
                     :path      "/insurance-coverage-edit/{coverage-id}/"
                     :page      #'coverage.edit.views/page})]

   (insurance-create)])

(defn unauthenticated-routes []
  [""
   ["/instrument-public/{instrument-id}"
    [""
     {:get {:summary "The public page for an instrument"
            :parameters {:path [:map [:instrument-id :uuid]]}
            :handler (fn [req]
                       (public/instrument-public-page req (-> req :parameters :path :instrument-id)))}}]
    ["/download-zip" {:get {:summary "Download all photos for an instrument"
                            :parameters {:path [:map [:instrument-id :uuid]]}
                            :handler (fn [req]
                                       (public/instrument-public-page-download-all req (-> req :parameters :path :instrument-id)))}}]]
   ["/instrument-image/{instrument-id}/{image-id}"
    {:get {:summary "Get instrument images"
           :parameters {:path [:map [:instrument-id :uuid] [:image-id :string]]
                        :query [:map [:mode {:optional true} [:enum "full" "thumbnail"]]]}
           :handler (fn [req]
                      (view/image-fetch-handler req))}}]])
