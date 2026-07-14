(ns app.account.routes
  (:require
   [app.account.actions :as actions]
   [app.account.avatar :as avatar]
   [app.account.break.views :as break.views]
   [app.account.index.views :as index.views]
   [app.account.notifications.views :as notifications.views]
   [app.account.preferences.views :as preferences.views]
   [app.account.profile.views :as profile.views]
   [app.form :as form]
   [app.nexus :as nexus]
   [app.routes.datastar :as ds]
   [reitit.ring.malli :as reitit.ring.malli]))

(defn- avatar-upload [file-part]
  (when (and (map? file-part)
             (seq (:filename file-part))
             (:tempfile file-part))
    {:filename (:filename file-part)
     :mime-type (:content-type file-part)
     :size (:size file-part)
     :tempfile (:tempfile file-part)}))

(defn profile-save-handler
  "Adapts a multipart profile form to the qualified account save action."
  [request]
  (let [multipart (get-in request [:parameters :multipart])]
    [[::actions/save-profile
      {:account-profile
       {:name (:name multipart)
        :nick (:nick multipart)
        :email (:email multipart)
        :username (:username multipart)
        :phone (:phone multipart)
        :current-status (:current-status multipart)
        :date-of-birth (:date-of-birth multipart)
        :avatar-removed? (form/normalize-bool (:avatar-removed? multipart))}
       :avatar-upload (avatar-upload (:avatar multipart))}]]))

(defn routes [system]
  ["" {:app.route/name :app/account-settings}
   (ds/page-routes {:page-name ::index
                    :path "/account-settings"
                    :page #'index.views/page})
   (ds/page-routes {:page-name ::profile
                    :path "/account-settings/profile"
                    :page #'profile.views/page})
   (ds/page-routes {:page-name ::preferences
                    :path "/account-settings/preferences"
                    :page #'preferences.views/page})
   (ds/page-routes {:page-name ::notifications
                    :path "/account-settings/notifications"
                    :page #'notifications.views/page})
   (ds/page-routes {:page-name ::on-a-break
                    :path "/account-settings/on-a-break"
                    :page #'break.views/page})
   ["/account-settings/profile/save"
    {:name ::save-profile
     :interceptors [(nexus/nexus-interceptor (:nexus system) system)]
     :post
     {:parameters
      {:multipart
       [:map
        [:tab-id :string]
        [:name :string]
        [:nick {:optional true} :string]
        [:email :string]
        [:username :string]
        [:phone {:optional true} :string]
        [:current-status {:optional true} :string]
        [:date-of-birth {:optional true} :string]
        [:avatar-removed? {:optional true} :string]
        [:avatar {:optional true} reitit.ring.malli/temp-file-part]]}
      :handler profile-save-handler}}]
   ["/member-avatar/{member-id}/{size}"
    {:name ::member-avatar
     :get
     {:parameters {:path [:map [:member-id :uuid] [:size :int]]}
      :handler avatar/member-avatar-handler}}]])
