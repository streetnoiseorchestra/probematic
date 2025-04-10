(ns app.members.controller2
  (:require [app.datomic :as d]
            [app.datomic.shim :as datomic]
            [app.email :as email]
            [app.keycloak :as keycloak]
            [app.queries :as q]
            [app.secret-box :as secret-box]
            [taoensso.carmine :as redis]))

;; ---------
;;; TODO move these fns to another ns
;;; They are generic helpers for validating forms and returning errors

(defn unique-attr-available? [attr db value]
  (nil? (d/find-by db attr value [attr])))

;; ---------
;;  Member Controller

(defn keycloak-attrs-changed? [before-m after-m]
  (let [ks [:member/email :member/username :member/active? :member/name]]
    (not=
     (select-keys before-m ks)
     (select-keys after-m ks))))

(defn transact-member!
  ([{:keys [datomic-conn system]} member-id tx-data]
   (let [tx-result     (datomic/transact datomic-conn {:tx-data tx-data})
         before-member (q/retrieve-member (:db-before tx-result) member-id)
         after-member  (q/retrieve-member (:db-after tx-result) member-id)]
     (when
         (and (keycloak-attrs-changed? before-member after-member) (:member/keycloak-id after-member))
       ;; TODO: rollback datomic tx if keycloak update fails
       (keycloak/update-user-meta! (:keycloak system) after-member))
     {:member after-member})))

(defn generate-invite-code! [{:keys [system] :as _req} member]
  (let [invite-code (secret-box/random-str 32)
        key         (str "invite:" invite-code)]
    (redis/wcar (:redis system)
                (redis/setex key (* 60 60 24 30) (:member/member-id member)))
    invite-code))

(def email-available? (partial unique-attr-available? :member/email))
(def username-available? (partial unique-attr-available? :member/username))
(def nick-available? (partial unique-attr-available? :member/nick))
(def phone-available? (partial unique-attr-available? :member/phone))

(defn gen-unique-constraints [{:keys [db tr]}]
  [:map
   [:email    [:fn {:error/message (tr [:error/member-unique-email])} #(email-available? db %1)]]
   [:username [:fn {:error/message (tr [:error/member-unique-username])} #(username-available? db %1)]]
   [:nick [:fn {:error/message (tr [:error/member-unique-nick])} #(nick-available? db %1)]]
   [:phone [:fn {:error/message (tr [:error/member-unique-phone])} #(phone-available? db %1)]]])

(defn unique-conflict-error [{:keys [tr human-id]} e]
  (cond
    (re-find #".*:member/phone.*" (ex-message e))    {:phone (tr [:error/member-unique-phone])}
    (re-find #".*:member/username.*" (ex-message e)) {:username (tr [:error/member-unique-username])}
    (re-find #".*:member/email.*" (ex-message e))    {:email (tr [:error/member-unique-email])}
    (re-find #".*:member/nick.*" (ex-message e))     {:nick (tr [:error/member-unique-nick])}
    :else                                            {:_top (str (tr [:error/unknown-form-error]) " " human-id)}))

(defn send-user-invitation! [req new-member]
  (email/send-new-user-email! req new-member (generate-invite-code! req new-member)))
