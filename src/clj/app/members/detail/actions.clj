(ns app.members.detail.actions
  (:require
   [app.members.domain :as members.domain]
   [app.queries :as q]
   [app.settings.action-support :as support]
   [app.util :as util]
   [clojure.string :as str]
   [datomic.api :as d]
   [tick.core :as t]))

(def clear-contact
  [:app.datastar/assoc-state [:member-detail :contact] false])

(def clear-travel-discount-create
  [:app.datastar/assoc-state [:member-detail :travel-discount-create] false])

(def clear-travel-discount-edit
  [:app.datastar/assoc-state [:member-detail :travel-discount] false])

(def allowed-tabs
  #{"discounts" "ledger" "insurance" "activity"})

(defn- normalize-active-tab [active-tab]
  (if (contains? allowed-tabs active-tab)
    active-tab
    "discounts"))

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

(defn- phone-error [tr phone]
  (cond
    (str/blank? phone)
    (required-error tr (tr [:Phone]))

    (not (members.domain/phone-valid? phone))
    {:error (tr [:error/member-phone-format])}))

(defn- validation-errors [{:keys [db tr member-ref]} {:keys [name nick email phone section-name] :as contact}]
  (merge
   (when (str/blank? name)
     {:name (required-error tr (tr [:member/name]))})
   (when (str/blank? nick)
     {:nick (required-error tr (tr [:member/nick]))})
   (when (str/blank? email)
     {:email (required-error tr (tr [:Email]))})
   (when-let [error (phone-error tr phone)]
     {:phone error})
   (when (str/blank? section-name)
     {:section-name (required-error tr (tr [:section]))})
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
   :_error       {}})

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

(defn validate-contact-field-action
  [{:keys [db tr]} {:keys [member-detail]}]
  (let [raw        (:contact member-detail)
        field      (some-> (:validate-field raw) keyword)
        contact    (normalize-contact raw)
        member-id  (util/ensure-uuid! (:member-id contact))
        member-ref [:member/member-id member-id]
        error      (get (validation-errors {:db db :tr tr :member-ref member-ref} contact) field)]
    (tap> [:field field :error error :raw raw])
    (cond-> [[:app.datastar/merge-state [:member-detail :contact] contact]]
      field (conj [:app.datastar/assoc-state
                   [:member-detail :contact :_error field]
                   error]))))

(defn update-contact-action
  [{:keys [db current-member-id tr]} {:keys [member-detail]}]
  (let [contact        (normalize-contact (:contact member-detail))
        member-id      (util/ensure-uuid! (:member-id contact))
        member-ref     [:member/member-id member-id]
        current-member (q/retrieve-member db member-id)
        errors         (validation-errors {:db db :tr tr :member-ref member-ref} contact)]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:member-detail :contact]
        (assoc contact :_error errors)]]
      (cond-> [[:db/transact
                (support/with-audit [(contact-tx member-id contact)]
                  current-member-id)
                {:transact-w-nils? true}]]
        (keycloak-sync-needed? current-member contact)
        (conj [:app.members/update-keycloak-meta member-id])

        true
        (conj support/clear-loading clear-contact)))))

(defn- trim-string [v]
  (some-> v str str/trim))

(defn- parse-date [value]
  (when (seq (trim-string value))
    (try
      (t/date (trim-string value))
      (catch Exception _
        nil))))

(defn- date->db-inst [date]
  (t/inst (t/in (t/at date (t/midnight)) "UTC")))

(defn- expiry-date->db-inst [value]
  (some-> value parse-date date->db-inst))

(defn- expiry-date->form-value [value]
  (some-> value t/date str))

(defn- normalize-travel-discount-create [form]
  {:member-id        (trim-string (:member-id form))
   :discount-type-id (trim-string (:discount-type-id form))
   :expiry-date      (trim-string (:expiry-date form))})

(defn- normalize-travel-discount-edit [form]
  {:discount-id (some-> (:discount-id form) util/ensure-uuid!)
   :expiry-date (trim-string (:expiry-date form))})

(defn- expiry-date-error [expiry-date]
  (cond
    (str/blank? expiry-date)
    {:error "Expiry date is required."}

    (nil? (parse-date expiry-date))
    {:error "Please enter a valid expiry date."}))

(defn- discount-type-exists? [db discount-type-id]
  (boolean
   (when (and db discount-type-id)
     (d/entity db [:travel.discount.type/discount-type-id discount-type-id]))))

(defn- travel-discount-create-errors [db {:keys [member-id discount-type-id expiry-date]}]
  (let [member-uuid        (when (seq member-id)
                             (util/ensure-uuid! member-id))
        discount-type-uuid (when (seq discount-type-id)
                             (util/ensure-uuid! discount-type-id))]
    (merge
     (when-not member-uuid
       {:_top {:error "Member id is missing."}})
     (cond
       (str/blank? discount-type-id)
       {:discount-type-id {:error "Discount type is required."}}

       (not (discount-type-exists? db discount-type-uuid))
       {:discount-type-id {:error "Please choose a valid discount type."}})
     (when-let [error (expiry-date-error expiry-date)]
       {:expiry-date error}))))

(defn- travel-discount-edit-errors [{:keys [discount-id expiry-date]}]
  (merge
   (when-not discount-id
     {:_top {:error "Travel discount id is missing."}})
   (when-let [error (expiry-date-error expiry-date)]
     {:expiry-date error})))

(defn set-active-tab-action
  [_state {:keys [member-detail targetid]}]
  [support/clear-loading
   [:app.datastar/assoc-state
    [:member-detail :active-tab]
    (normalize-active-tab (or targetid (:active-tab member-detail)))]])

(defn open-travel-discount-create-action
  [_state {:keys [targetid]}]
  [support/clear-loading
   [:app.datastar/assoc-state
    [:member-detail :travel-discount-create]
    {:member-id        (str (util/ensure-uuid! targetid))
     :discount-type-id ""
     :expiry-date      ""
     :_error           {}}]])

(defn close-travel-discount-create-action [_state _signals]
  [support/clear-loading clear-travel-discount-create])

(defn add-travel-discount-action
  [{:keys [db current-member-id]} {:keys [member-detail]}]
  (let [form             (normalize-travel-discount-create (:travel-discount-create member-detail))
        member-id        (util/ensure-uuid! (:member-id form))
        discount-type-id (when (seq (:discount-type-id form))
                           (util/ensure-uuid! (:discount-type-id form)))
        errors           (travel-discount-create-errors db form)]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:member-detail :travel-discount-create]
        (assoc form :_error errors)]]
      [[:db/transact
        (support/with-audit
          [{:db/id                         "new-travel-discount"
            :travel.discount/discount-id   :db/gen-uuid
            :travel.discount/discount-type [:travel.discount.type/discount-type-id discount-type-id]
            :travel.discount/expiry-date   (expiry-date->db-inst (:expiry-date form))}
           [:db/add
            [:member/member-id member-id]
            :member/travel-discounts
            "new-travel-discount"]]
          current-member-id)
        {:transact-w-nils? false}]
       support/clear-loading
       clear-travel-discount-create])))

(defn open-travel-discount-edit-action
  [{:keys [db]} {:keys [targetid]}]
  (let [discount-id (util/ensure-uuid! targetid)
        discount    (q/retrieve-travel-discount db discount-id)]
    [support/clear-loading
     [:app.datastar/assoc-state
      [:member-detail :travel-discount]
      {:discount-id discount-id
       :expiry-date (expiry-date->form-value (:travel.discount/expiry-date discount))
       :_error      {}}]]))

(defn close-travel-discount-edit-action [_state _signals]
  [support/clear-loading clear-travel-discount-edit])

(defn update-travel-discount-action
  [{:keys [current-member-id]} {:keys [member-detail]}]
  (let [form   (normalize-travel-discount-edit (:travel-discount member-detail))
        errors (travel-discount-edit-errors form)]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:member-detail :travel-discount]
        (assoc form :_error errors)]]
      [[:db/transact
        (support/with-audit
          [[:db/add
            [:travel.discount/discount-id (:discount-id form)]
            :travel.discount/expiry-date
            (expiry-date->db-inst (:expiry-date form))]]
          current-member-id)
        {:transact-w-nils? false}]
       support/clear-loading
       clear-travel-discount-edit])))

(defn delete-travel-discount-action
  [{:keys [current-member-id]} {:keys [targetid]}]
  (let [discount-id (util/ensure-uuid! targetid)]
    [[:db/transact
      (support/with-audit [[:db/retractEntity [:travel.discount/discount-id discount-id]]]
        current-member-id)
      {:transact-w-nils? false}]
     support/clear-loading
     clear-travel-discount-edit]))

(def actions
  {::open-contact-edit                 #'open-contact-edit-action
   ::close-contact-edit                #'close-contact-edit-action
   ::validate-contact-field            #'validate-contact-field-action
   ::update-contact                    #'update-contact-action
   ::set-active-tab                    #'set-active-tab-action
   ::open-travel-discount-create       #'open-travel-discount-create-action
   ::close-travel-discount-create      #'close-travel-discount-create-action
   ::add-travel-discount               #'add-travel-discount-action
   ::open-travel-discount-edit         #'open-travel-discount-edit-action
   ::close-travel-discount-edit        #'close-travel-discount-edit-action
   ::update-travel-discount            #'update-travel-discount-action
   ::delete-travel-discount            #'delete-travel-discount-action})
