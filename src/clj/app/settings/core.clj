(ns app.settings.core
  (:require [app.auth :as auth]
            [app.datastar :as d*]
            [app.layout :as layout]
            [app.settings.routes :as routes]
            [app.settings.views :as view]
            [ctmx.core :as ctmx]))

(defn settings-route []
  (ctmx/make-routes
   "/band-settings"
   (fn [req]
     (layout/app-shell req
                       (view/settings-page req)))))

(defn render [req]
  (view/settings-page (assoc req :request-method :get)))

(defn shim [req]
  (layout/app-shell req nil))

(defn routes []
  ["" {:app.route/name :app/band-settings
       :app.auth/roles #{:Mitglieder}
       :interceptors   [auth/roles-authorization-interceptor]}
   ["/band-settings" {:name routes/index}
    ["" {:get  shim
         :post (d*/render-handler render)}]
    ["/team" {:name routes/teams}
     ["" {:post   {:handler view/teams-create-handler}
          :delete {:handler view/teams-delete-handler}}]
     ["/command-delete-team-member" {:name routes/command-delete-team-member
                                     :post {:handler    view/teams-remove-member-handler
                                            :parameters {:body {:team {:remove-member-id :uuid
                                                                       :team-id          :uuid}}}}}]
     ["/command-add-team-member" {:name routes/command-add-team-member
                                  :post {:handler    view/teams-add-member-handler
                                         :parameters {:body {:team {:team-id   :uuid
                                                                    :member-id :uuid}}}}}]

     ["/command-update-team" {:name routes/command-update-team
                              :post {:handler    view/teams-update-handler
                                     :parameters {:body {:team {:team-name :string
                                                                :team-type :string
                                                                :team-id   :uuid}}}}}]

     ["/edit" {:name   routes/teams-form
               :post   {:handler    view/teams-edit-form-handler
                        :parameters {:body {:current-edit-id :uuid}}}
               :delete {:handler    view/close-teams-edit-form-handler
                        :parameters {:body {:current-edit-id :uuid}}}}]]]

   #_(settings-route)])

(comment
  (d*/refresh-all!)
  ;;
  )
