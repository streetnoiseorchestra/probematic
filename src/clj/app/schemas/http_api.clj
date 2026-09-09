(ns app.schemas.http-api)

(def registry
  {:api.response/users           [:map [:users [:vector :doc/user]]]
   :api.request.path/user        [:map [:user-id :user/id]]
   :api.response/user            [:map [:user :doc/user]]
   :api.request.body/user-update [:map [:id :user/id] [:username string?]]})
