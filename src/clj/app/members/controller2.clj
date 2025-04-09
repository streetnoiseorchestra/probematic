(ns app.members.controller2
  (:require [app.datastar :as d*]
            [app.datomic :as d]
            [app.datomic.shim :as datomic]
            [app.email :as email]
            [app.keycloak :as keycloak]
            [app.ledger.domain :as ledger.domain]
            [app.members.domain :as domain]
            [app.queries :as q]
            [app.schemas :as s]
            [app.secret-box :as secret-box]
            [com.yetanalytics.squuid :as sq]
            [medley.core :as medley]
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

(defn validate-new-member [req m]
  (let [unique-schema  (gen-unique-constraints req)
        unique-user?   (->> m (s/explain unique-schema) s/humanize)
        valid-request? (->> m (s/explain (domain/NewMemberForm (:tr req))) s/humanize)]

    ;; if there are multiple errors per-field (because of :and) then we take the first one
    (medley/map-vals first
                     (merge valid-request? unique-user?))))

(defn validate-create-member! [req]
  (let [r      (validate-new-member req (-> req :parameters :body :member))
        errors (apply dissoc r (d*/untouched-fields req :member))]
    (tap> [:errors errors])
    (when errors
      errors)))

(defn unique-conflict-error [{:keys [tr human-id]} e]
  (cond
    (re-find #".*:member/phone.*" (ex-message e))    {:phone (tr [:error/member-unique-phone])}
    (re-find #".*:member/username.*" (ex-message e)) {:username (tr [:error/member-unique-username])}
    (re-find #".*:member/email.*" (ex-message e))    {:email (tr [:error/member-unique-email])}
    (re-find #".*:member/nick.*" (ex-message e))     {:nick (tr [:error/member-unique-nick])}
    :else                                            {:_top (str (tr [:error/unknown-form-error]) " " human-id)}))

(defn new-member-txs [member-id {:keys [phone email active
                                        name nick username section-name]}]
  (let [member-tmpid (d/tempid)]
    (concat [{:member/name      name
              :member/nick      nick
              :member/member-id member-id
              :member/phone     (domain/clean-phone-number phone)
              :member/username  username
              :member/active?   (true? active)
              :member/section   [:section/name section-name]
              :member/email     (domain/clean-email email)
              :db/id            member-tmpid}]
            (ledger.domain/txs-new-member-ledger (d/tempid) (sq/generate-squuid) member-tmpid))))

(defn send-user-invitation! [req new-member]
  (email/send-new-user-email! req new-member (generate-invite-code! req new-member)))

(defn create-member! [{:keys [tr] :as req}]
  (let [params (-> req :parameters :body :member)
        errors (validate-new-member req (-> req :parameters :body :member))
        valid? (empty? errors)]
    (if valid?
      (try
        (let [member-id  (sq/generate-squuid)
              txs        (new-member-txs member-id params)
              new-member (:member (transact-member! req member-id txs))]

          (when (:create-sno-id params)
            (send-user-invitation! req new-member))

          [new-member nil])

        (catch Exception e
          (cond
            (= :db.error/unique-conflict (-> e .getCause ex-data :db/error))
            [nil (unique-conflict-error req e)]

            (= :invalid-phone (-> e .getCause ex-data :type))
            [nil {:phone (tr [:error/phone-number-invalid])}]

            :else [nil (d*/unhandled-form-error req e)])))
      [nil (validate-create-member! req)])))
