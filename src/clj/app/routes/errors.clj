(ns app.routes.errors
  (:require
   [app.email :as email]
   [app.errors :as error.util]
   [app.render :as render]
   [app.ui :as ui]))

(defn unauthorized-error [ex req]
  (assert (map? req))
  (error.util/log-error! req ex)
  (error.util/send-event! req ex)
  (if (:htmx? req)
    (ui/error-page-response-fragment ex req 401)
    (ui/error-page-response ex req 401)))

(defn validation-error [ex req]
  (assert (map? req))
  (error.util/log-error! req ex)
  (error.util/send-event! req ex)
  (if (:htmx? req)
    (ui/error-page-response-fragment ex req 400)
    (ui/error-page-response ex req 400)))

(defn not-found-error [ex req]
  (assert (map? req))
  (error.util/log-error! req ex)
  (error.util/send-event! req ex)
  (if (:htmx? req)
    (ui/error-page-response-fragment ex req 404)
    (ui/error-page-response ex req 404)))

(defn unknown-error [ex req]
  (assert (map? req))
  (error.util/log-error! req ex)
  (error.util/send-event! req ex)
  (if (:htmx? req)
    (ui/error-page-response-fragment ex req 500)
    (ui/error-page-response ex req 500)))

(defn notify-admin [req]
  (let [human-id (-> req :params :human-id)
        member (-> req :session :session/member)]
    (email/send-admin-email! req member human-id)
    (render/snippet-response
     [:span {:class "text-sno-orange-600"} "Notification sent!"])))

(defn routes []
  [""
   ["/notify-admin" {:post (fn [req]
                             (notify-admin req))}]])
