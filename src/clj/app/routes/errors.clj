(ns app.routes.errors
  (:require
   [app.email :as email]
   [app.errors :as error.util]
   [app.ui2 :as ui2]))

(defn- error-copy [tr status]
  (condp = status
    401 {:code "401"
         :title (tr [:error/unauthorized-title])
         :message (tr [:error/unauthorized-message])}
    404 {:code "404"
         :title (tr [:error/not-found-title])
         :message (tr [:error/not-found-message])}
    {:code (str (or status 500))
     :title (tr [:error/unknown-title])
     :message (tr [:error/unknown-message])}))

(defn- error-content [{:keys [human-id tr]} status]
  (let [{:keys [code title message]} (error-copy tr status)]
    [[:header {:class (when (= 500 status) "danger")}
      [:p code]
      [:h1 title]]
     [:p message]
     [:p [:code human-id]]
     [:footer
      [:a {:href "/"}
       (tr [:error/go-home])]
      [:form {:method "post"
              :action "/notify-admin"}
       [:input {:type "hidden"
                :name "human-id"
                :value human-id}]
       [:button {:type "submit"}
        (tr [:error/notify])]]]]))

(defn- error-page-response [_cause req status]
  (let [{:keys [title message]} (error-copy (:tr req) status)]
    (apply ui2/standalone-page
           {:status (or status 404)
            :title title
            :description message}
           (error-content req status))))

(defn- handle-error [ex req status]
  (assert (map? req))
  (error.util/log-error! req ex)
  (error.util/send-event! req ex)
  (error-page-response ex req status))

(defn unauthorized-error [ex req]
  (handle-error ex req 401))

(defn validation-error [ex req]
  (handle-error ex req 400))

(defn not-found-error [ex req]
  (handle-error ex req 404))

(defn unknown-error [ex req]
  (handle-error ex req 500))

(defn- notify-human-id [req]
  (or (get-in req [:params :human-id])
      (get-in req [:params "human-id"])
      (get-in req [:form-params :human-id])
      (get-in req [:form-params "human-id"])))

(defn notify-admin [req]
  (let [tr (:tr req)
        human-id (notify-human-id req)
        member (-> req :session :session/member)]
    (email/send-admin-email! req member human-id)
    (ui2/standalone-page
     {:status 200
      :title (tr [:action/done])
      :description (tr [:action/done])}
     [:header
      [:h1 (tr [:action/done])]]
     [:footer
      [:a {:href "/"}
       (tr [:error/go-home])]])))

(defn routes []
  [""
   ["/notify-admin" {:post (fn [req]
                             (notify-admin req))}]])
