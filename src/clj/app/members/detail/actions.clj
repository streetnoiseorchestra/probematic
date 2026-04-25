(ns app.members.detail.actions
  (:require
   [app.members.domain :as members.domain]
   [app.queries :as q]
   [app.settings.action-support :as support]
   [app.util :as util]
   [clojure.string :as str]
   [datomic.api :as d]))

(def clear-contact
  [:app.datastar/assoc-state [:member-detail :contact] false])

(defn- tr-fn [tr]
  (or tr (fn [path & _] (name (last path)))))

(defn- normalize-bool [v default]
  (cond
    (true? v) true
    (false? v) false
    (string? v) (= "true" (str/lower-case v))
    (nil? v) default
    :else (boolean v)))

(defn- clean-phone [phone]
  (let [phone (some-> phone str/trim)]
    (cond-> phone
      (and (seq phone) (members.domain/phone-valid? phone))
      members.domain/clean-phone-number)))

(defn- normalize-contact [contact]
  (let [email-raw (some-> (:email contact) str/trim)]
    {:member-id    (some-> (:member-id contact) str)
     :name         (some-> (:name contact) str/trim)
     :nick         (some-> (:nick contact) str/trim)
     :email        (some-> email-raw members.domain/clean-email)
     :phone        (clean-phone (:phone contact))
     :section-name (some-> (:section-name contact) str/trim)
     :active       (normalize-bool (:active contact) true)}))

(defn- section-exists? [db section-name]
  (boolean
   (when (and db (seq section-name))
     (d/entity db [:section/name section-name]))))

(defn- required-error [tr label]
  {:error (tr [:error/is-required] [label])})

(defn- duplicate-errors [db tr member-ref {:keys [email nick phone]}]
  (merge
   (when (and (seq email)
              (support/lookup-taken-by-other? db [:member/email email] member-ref))
     {:email {:error (tr [:error/member-unique-email])}})
   (when (and (seq nick)
              (support/lookup-taken-by-other? db [:member/nick nick] member-ref))
     {:nick {:error (tr [:error/member-unique-nick])}})
   (when (and (seq phone)
              (support/lookup-taken-by-other? db [:member/phone phone] member-ref))
     {:phone {:error (tr [:error/member-unique-phone])}})))

(defn- validation-errors [{:keys [db tr member-ref]} {:keys [name email phone section-name] :as contact}]
  (merge
   (when (str/blank? name)
     {:name (required-error tr (tr [:member/name]))})
   (when (str/blank? email)
     {:email (required-error tr (tr [:Email]))})
   (when (str/blank? phone)
     {:phone (required-error tr (tr [:Phone]))})
   (when (str/blank? section-name)
     {:section-name (required-error tr (tr [:section]))})
   (when (and (seq phone) (not (members.domain/phone-valid? phone)))
     {:phone {:error (tr [:error/member-phone-format])}})
   (when (and (seq section-name) (not (section-exists? db section-name)))
     {:section-name {:error (tr [:error/member-section-invalid])}})
   (duplicate-errors db tr member-ref contact)))

(defn- member->contact-form [member]
  {:member-id    (str (:member/member-id member))
   :name         (:member/name member)
   :nick         (or (:member/nick member) "")
   :email        (:member/email member)
   :phone        (:member/phone member)
   :section-name (get-in member [:member/section :section/name])
   :active       (boolean (:member/active? member))
   :error        {}})

(defn- contact-tx [member-id {:keys [name nick email phone section-name active]}]
  {:db/id            [:member/member-id member-id]
   :member/name      name
   :member/nick      (when (seq nick) nick)
   :member/email     email
   :member/phone     phone
   :member/section   [:section/name section-name]
   :member/active?   active})

(defn- keycloak-sync-needed? [current-member contact]
  (and (:member/keycloak-id current-member)
       (not=
        {:member/name    (:member/name current-member)
         :member/email   (:member/email current-member)
         :member/active? (:member/active? current-member)}
        {:member/name    (:name contact)
         :member/email   (:email contact)
         :member/active? (:active contact)})))

(defn open-contact-edit-action
  [{:keys [db]} {:keys [targetid]}]
  (let [member-id (util/ensure-uuid! targetid)
        member    (q/retrieve-member db member-id)]
    [support/clear-loading
     [:app.datastar/assoc-state
      [:member-detail :contact]
      (member->contact-form member)]]))

(defn close-contact-edit-action [_state _signals]
  [support/clear-loading clear-contact])

(defn update-contact-action
  [{:keys [db current-member-id tr]} {:keys [member-detail]}]
  (let [tr             (tr-fn tr)
        contact        (normalize-contact (:contact member-detail))
        member-id      (util/ensure-uuid! (:member-id contact))
        member-ref     [:member/member-id member-id]
        current-member (q/retrieve-member db member-id)
        errors         (validation-errors {:db db :tr tr :member-ref member-ref} contact)]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:member-detail :contact]
        (assoc contact :error errors)]]
      (cond-> [[:db/transact
                (support/with-audit [(contact-tx member-id contact)]
                  current-member-id)
                {:transact-w-nils? true}]]
        (keycloak-sync-needed? current-member contact)
        (conj [:app.members/update-keycloak-meta member-id])

        true
        (conj support/clear-loading clear-contact)))))

(def actions
  {::open-contact-edit  #'open-contact-edit-action
   ::close-contact-edit #'close-contact-edit-action
   ::update-contact     #'update-contact-action})
