(ns app.members.invite.actions
  (:require
   [app.form :as form]
   [app.members.domain :as members.domain]
   [app.nexus.actions :as support]
   [app.util :as util]
   [clojure.string :as str]
   [datomic.api :as d]))

(defn- normalize-form [member-invite]
  (let [email-raw (form/trim-value (:email member-invite))
        phone-raw (form/trim-value (:phone member-invite))]
    {:member-id     (some-> (:member-id member-invite) str)
     :name          (form/trim-value (:name member-invite))
     :nick          (form/trim-value (:nick member-invite))
     :email         (some-> email-raw members.domain/clean-email)
     :username      (some-> (:username member-invite) members.domain/clean-username)
     :phone         (cond-> phone-raw
                      (and (seq phone-raw) (members.domain/phone-valid? phone-raw))
                      members.domain/clean-phone-number)
     :section-name  (form/trim-value (:section-name member-invite))
     :active        (form/normalize-bool (:active member-invite) true)
     :create-sno-id (form/normalize-bool (:create-sno-id member-invite) true)}))

(defn- existing-entity? [db lookup-ref]
  (boolean
   (when db
     (d/entity db lookup-ref))))

(defn- duplicate-errors [db tr {:keys [email username nick phone]}]
  (merge
   (when (and (seq email) (existing-entity? db [:member/email email]))
     {:email {:error (tr [:error/member-unique-email])}})
   (when (and (seq username) (existing-entity? db [:member/username username]))
     {:username {:error (tr [:error/member-unique-username])}})
   (when (and (seq nick) (existing-entity? db [:member/nick nick]))
     {:nick {:error (tr [:error/member-unique-nick])}})
   (when (and (seq phone) (existing-entity? db [:member/phone phone]))
     {:phone {:error (tr [:error/member-unique-phone])}})))

(defn- required-error [tr label]
  {:error (tr [:error/is-required] [label])})

(defn- validation-errors [{:keys [db tr]} {:keys [name nick email username phone section-name]}]
  (merge
   (when (str/blank? name)
     {:name (required-error tr (tr [:member/name]))})
   (when (str/blank? email)
     {:email (required-error tr (tr [:Email]))})
   (when (str/blank? username)
     {:username (required-error tr (tr [:member/username]))})
   (when (str/blank? phone)
     {:phone (required-error tr (tr [:Phone]))})
   (when (str/blank? section-name)
     {:section-name (required-error tr (tr [:section]))})
   (when (and (seq username) (not (re-matches members.domain/username-regex username)))
     {:username {:error (tr [:error/member-username-format])}})
   (when (and (seq phone) (not (members.domain/phone-valid? phone)))
     {:phone {:error (tr [:error/member-phone-format])}})
   (when (and (seq section-name) (not (existing-entity? db [:section/name section-name])))
     {:section-name {:error (tr [:error/member-section-invalid])}})
   (duplicate-errors db tr {:email email :username username :nick nick :phone phone})))

(defn- member-tx [{:keys [member-id name nick email username phone section-name active]}]
  (cond-> {:db/id            "new-member"
           :member/member-id member-id
           :member/name      name
           :member/email     email
           :member/username  username
           :member/phone     phone
           :member/section   [:section/name section-name]
           :member/active?   active}
    (seq nick) (assoc :member/nick nick)))

(defn- ledger-tx []
  {:db/id            "new-ledger"
   :ledger/ledger-id :db/gen-uuid
   :ledger/owner     "new-member"
   :ledger/balance   0})

(defn submit-member-invite-action
  [{:keys [db current-member-id tr]} {:keys [member-invite]}]
  (let [tr            (or tr (fn [path & _] (name (last path))))
        member-invite (normalize-form member-invite)
        errors        (validation-errors {:db db :tr tr} member-invite)]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:member-invite]
        (assoc member-invite :error errors)]]
      (let [member-id (util/ensure-uuid! (:member-id member-invite))
            member-invite (assoc member-invite :member-id member-id)]
        (cond-> [[:db/transact
                  (support/with-audit
                    [(member-tx member-invite)
                     (ledger-tx)]
                    current-member-id)
                  {:transact-w-nils? false}]]
          (:create-sno-id member-invite)
          (conj [:app.members/send-user-invitation member-id])

          true
          (conj [:app.datastar/redirect (str "/member/" member-id)]))))))

(def actions
  {::submit-member-invite #'submit-member-invite-action})
