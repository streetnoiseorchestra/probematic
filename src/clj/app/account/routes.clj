(ns app.account.routes
  (:require
   [app.account.avatar :as avatar]
   [app.account.break.views :as break.views]
   [app.account.index.views :as index.views]
   [app.account.notifications.views :as notifications.views]
   [app.account.preferences.views :as preferences.views]
   [app.account.profile.views :as profile.views]
   [app.routes.datastar :as ds]))

(defn routes []
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
   ["/member-avatar/{member-id}/{size}"
    {:name ::member-avatar
     :get
     {:parameters {:path [:map [:member-id :uuid] [:size :int]]}
      :handler avatar/member-avatar-handler}}]])
