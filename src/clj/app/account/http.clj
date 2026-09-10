(ns app.account.http
  (:require
   [app.account.actions :as actions]
   [app.account.effects :as effects]
   [app.form :as form]
   [app.nexus :as nexus]
   [babashka.fs :as fs]))

(defn profile-actions
  "Adapts a server-parsed multipart profile form to the qualified save action."
  [request]
  (let [multipart (get-in request [:parameters :multipart])
        avatar    (:avatar multipart)]
    [[::actions/save-profile
      {:account-profile
       {:name            (:name multipart)
        :nick            (:nick multipart)
        :email           (:email multipart)
        :username        (:username multipart)
        :phone           (:phone multipart)
        :current-status  (:current-status multipart)
        :date-of-birth   (:date-of-birth multipart)
        :avatar-removed? (form/normalize-bool (:avatar-removed? multipart))}
       :avatar-upload   (when (and (map? avatar) (seq (:filename avatar)) (:tempfile avatar))
                          {:filename  (:filename avatar)
                           :mime-type (:content-type avatar)
                           :size      (:size avatar)
                           :tempfile  (:tempfile avatar)})}]]))

(defn save-profile
  "Prepares multipart content before queue admission; retains legacy dispatch when disabled."
  [system request]
  (let [actions (profile-actions request)]
    (if-let [runtime (:frame-loop system)]
      (try
        (if @(:stopped? runtime)
          {:status 503 :headers {} :body ""}
          (let [{:keys [account-profile avatar-upload]} (second (first actions))
                prepared                                (effects/prepare-profile!
                                                         system {:profile       account-profile
                                                                 :avatar-upload (when (and avatar-upload (nil? (actions/avatar-error avatar-upload)))
                                                                                  avatar-upload)})]
            (nexus/queue-actions! runtime
                                  (-> request
                                      (dissoc :params :form-params :multipart-params)
                                      (assoc :system system
                                             :parameters {:multipart {:tab-id (get-in request [:parameters :multipart :tab-id])}}
                                             ::effects/prepared-profile (update prepared :avatar-upload dissoc :tempfile)))
                                  (update-in actions [0 1 :avatar-upload] #(when % (dissoc % :tempfile))))))
        (finally
          (when-let [file (get-in request [:parameters :multipart :avatar :tempfile])]
            (fs/delete-if-exists file))))
      actions)))
