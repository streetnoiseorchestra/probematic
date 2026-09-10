(ns app.account.routes
  (:require
   [app.account.http :as http]
   [app.account.avatar :as avatar]
   [app.account.break.views :as break.views]
   [app.account.index.views :as index.views]
   [app.account.notifications.views :as notifications.views]
   [app.account.preferences.views :as preferences.views]
   [app.account.profile.views :as profile.views]
   [app.nexus :as nexus]
   [app.routes.datastar :as ds]
   [reitit.ring.malli :as reitit.ring.malli]))

(defn routes [system]
  ["" {:app.route/name :app/account-settings}
   (ds/page-routes {:page-name ::index
                    :path      "/account-settings"
                    :page      #'index.views/page})
   (ds/page-routes {:page-name ::profile
                    :path      "/account-settings/profile"
                    :page      #'profile.views/page})
   (ds/page-routes {:page-name ::preferences
                    :path      "/account-settings/preferences"
                    :page      #'preferences.views/page})
   (ds/page-routes {:page-name ::notifications
                    :path      "/account-settings/notifications"
                    :page      #'notifications.views/page})
   (ds/page-routes {:page-name ::on-a-break
                    :path      "/account-settings/on-a-break"
                    :page      #'break.views/page})
   ["/account-settings/profile/save"
    {:name         ::save-profile
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
      :handler    (partial http/save-profile system)}}]
   ["/member-avatar/{member-id}/{size}"
    {:name ::member-avatar
     :get
     {:parameters {:path [:map [:member-id :uuid] [:size :int]]}
      :handler    avatar/member-avatar-handler}}]])
