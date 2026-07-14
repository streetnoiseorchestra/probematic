(ns app.account.actions
  (:require
   [app.form :as form]
   [app.members.domain :as members.domain]
   [app.nexus.actions :as support]
   [app.account.queries :as account.queries]
   [clojure.string :as str]
   [datomic.api :as d])
  (:import
   [java.time DateTimeException Instant LocalDate LocalTime ZoneId]
   [java.util Date]))

(def max-avatar-size (* 5 1024 1024))

(def allowed-avatar-types
  #{"image/jpeg" "image/png" "image/webp"})

(defn- tr-node
  ([id]
   [:i18n/tr id])
  ([id data]
   [:i18n/tr id data]))

(defn- error [id]
  {:error (tr-node id)})

(defn- trimmed [value]
  (some-> value str form/trim-value))

(defn- valid-date? [value]
  (or (str/blank? value)
      (try
        (LocalDate/parse value)
        true
        (catch DateTimeException _exception
          false))))

(defn- clean-phone [value]
  (let [value (trimmed value)]
    (cond
      (str/blank? value) ""
      (members.domain/phone-valid? value) (members.domain/clean-phone-number value)
      :else value)))

(defn- normalize-avatar [avatar]
  (when (map? avatar)
    (cond-> {:filename  (or (trimmed (:filename avatar)) "")
             :mime-type (or (trimmed (:mime-type avatar)) "")
             :size      (:size avatar)}
      (:staged? avatar) (assoc :staged? true))))

(defn- normalize-profile [profile]
  {:name           (or (trimmed (:name profile)) "")
   :nick           (or (trimmed (:nick profile)) "")
   :email          (some-> (:email profile) trimmed members.domain/clean-email)
   :username       (some-> (:username profile) trimmed members.domain/clean-username)
   :phone          (clean-phone (:phone profile))
   :current-status (or (trimmed (:current-status profile)) "")
   :date-of-birth  (or (trimmed (:date-of-birth profile)) "")
   :avatar-removed? (true? (:avatar-removed? profile))
   :avatar         (normalize-avatar (:avatar profile))})

(defn- email-valid? [email]
  (boolean
   (and (seq email)
        (re-matches #"(?i)^[^@\s]+@[^@\s]+\.[^@\s]+$" email))))

(defn- profile-format-errors [{:keys [name email username phone date-of-birth]}]
  (merge
   (when (str/blank? name)
     {:name (error :account-settings/error-name-required)})
   (cond
     (str/blank? email)
     {:email (error :account-settings/error-email-required)}

     (not (email-valid? email))
     {:email (error :account-settings/error-email-invalid)})
   (cond
     (str/blank? username)
     {:username (error :account-settings/error-username-required)}

     (not (re-matches members.domain/username-regex username))
     {:username (error :account-settings/error-username-invalid)})
   (when (and (seq phone) (not (members.domain/phone-valid? phone)))
     {:phone (error :account-settings/error-phone-invalid)})
   (when-not (valid-date? date-of-birth)
     {:date-of-birth (error :account-settings/error-date-invalid)})))

(defn- duplicate-profile-errors [db member-ref {:keys [nick email username phone]}]
  (when db
    (merge
     (when (and (seq nick)
                (support/lookup-taken-by-other? db [:member/nick nick] member-ref))
       {:nick (error :account-settings/error-nick-taken)})
     (when (and (seq email)
                (support/lookup-taken-by-other? db [:member/email email] member-ref))
       {:email (error :account-settings/error-email-taken)})
     (when (and (seq username)
                (support/lookup-taken-by-other? db [:member/username username] member-ref))
       {:username (error :account-settings/error-username-taken)})
     (when (and (seq phone)
                (support/lookup-taken-by-other? db [:member/phone phone] member-ref))
       {:phone (error :account-settings/error-phone-taken)}))))

(defn- profile-errors [db member-id profile]
  (let [errors (merge (profile-format-errors profile)
                      (duplicate-profile-errors
                       db
                       [:member/member-id member-id]
                       profile))]
    (cond-> errors
      (seq errors) (assoc :_top (error :account-settings/error-profile-invalid)))))

(defn validate-profile-field-action
  [{:keys [db current-member-id]} {:keys [account-profile]}]
  (let [field   (some-> (:validate-field account-profile) keyword)
        profile (normalize-profile account-profile)
        errors  (profile-errors db current-member-id profile)]
    (cond-> [[:app.datastar/merge-state [:account-profile] profile]]
      field (conj [:app.datastar/assoc-state
                   [:account-profile :_error field]
                   (get errors field)]))))

(defn- size-value [value]
  (cond
    (integer? value) value
    (string? value)  (try
                       (Long/parseLong value)
                       (catch NumberFormatException _exception
                         nil))
    :else            nil))

(defn- normalize-avatar-upload [avatar]
  (when (map? avatar)
    {:filename (or (trimmed (:filename avatar)) "")
     :mime-type (or (trimmed (:mime-type avatar)) "")
     :size (size-value (:size avatar))
     :tempfile (:tempfile avatar)}))

(defn- avatar-error [{:keys [filename mime-type size]}]
  (let [size (size-value size)]
    (cond
      (str/blank? filename) (error :account-settings/error-avatar-name)
      (not (contains? allowed-avatar-types mime-type))
      (error :account-settings/error-avatar-type)
      (or (nil? size) (neg? size) (< max-avatar-size size))
      (error :account-settings/error-avatar-size))))

(defn stage-avatar-action [_state {:keys [account-profile]}]
  (let [avatar (normalize-avatar (:avatar account-profile))]
    (if-let [avatar-error (avatar-error avatar)]
      [support/clear-loading
       [:app.datastar/assoc-state [:account-profile :_error :avatar] avatar-error]]
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-profile :avatar]
        (assoc avatar :size (size-value (:size avatar)) :staged? true)]
       [:app.datastar/assoc-state [:account-profile :avatar-removed?] false]
       [:app.datastar/assoc-state [:account-profile :_error :avatar] nil]
       [:app.datastar/assoc-state
        [:account-profile :_feedback]
        (tr-node :account-settings/avatar-staged-feedback)]])))

(defn remove-avatar-action [_state _signals]
  [support/clear-loading
   [:app.datastar/assoc-state [:account-profile :avatar] nil]
   [:app.datastar/assoc-state [:account-profile :avatar-removed?] true]
   [:app.datastar/assoc-state
    [:account-profile :_feedback]
    (tr-node :account-settings/avatar-removed-feedback)]])

(defn- keycloak-sync-required? [member]
  (boolean (:member/keycloak-id member)))

(defn- profile-error-effects [profile errors]
  [support/clear-loading
   [:app.datastar/assoc-state
    [:account-profile]
    (assoc profile :_error errors :_saved? false)]])

(defn- discard-upload [effects avatar-upload]
  (cond-> effects
    (:tempfile avatar-upload)
    (conj [:app.account/discard-upload (:tempfile avatar-upload)])))

(defn save-profile-action
  [{:keys [db current-member-id]}
   {:keys [account-profile avatar-upload]}]
  (let [profile       (normalize-profile account-profile)
        avatar-upload (normalize-avatar-upload avatar-upload)
        member        (when (and db current-member-id)
                        (d/entity db [:member/member-id current-member-id]))]
    (if-not (:db/id member)
      (discard-upload
       (profile-error-effects
        profile
        {:_top (error :account-settings/error-current-member-missing)})
       avatar-upload)
      (let [errors (cond-> (profile-errors db current-member-id profile)
                     (and avatar-upload (avatar-error avatar-upload))
                     (assoc :avatar (avatar-error avatar-upload)))]
        (if (seq errors)
          (discard-upload (profile-error-effects profile errors) avatar-upload)
          [[:app.account/save-profile
            {:member-id current-member-id
             :profile profile
             :avatar-upload avatar-upload
             :expected-avatar-id
             (some-> (:member/avatar member) :image/image-id)
             :sync-keycloak? (keycloak-sync-required? member)}]])))))

(defn- valid-zone? [value]
  (boolean
   (and (seq value)
        (try
          (ZoneId/of value)
          true
          (catch DateTimeException _exception
            false)))))

(defn- persisted-effects [member-id tx-data path transient-state feedback]
  [[:db/transact
    (support/with-audit tx-data member-id)
    {:transact-w-nils? true}]
   support/clear-loading
   [:app.datastar/assoc-state
    path
    (assoc transient-state
           :_error {}
           :_saved? true
           :_feedback (tr-node feedback))]])

(def week-start-ident
  {"monday" :week-start/monday
   "sunday" :week-start/sunday})

(def clock-format-ident
  {"12-hour" :clock-format/hour-12
   "24-hour" :clock-format/hour-24})

(defn save-date-time-preferences-action
  [{:keys [current-member-id]} {:keys [account-preferences]}]
  (let [preferences (select-keys account-preferences
                                 [:time-zone :week-start :time-format])
        errors      (merge
                     (when-not (valid-zone? (:time-zone preferences))
                       {:time-zone (error :account-settings/error-time-zone-invalid)})
                     (when-not (contains? week-start-ident
                                          (:week-start preferences))
                       {:week-start (error :account-settings/error-week-start-invalid)})
                     (when-not (contains? clock-format-ident
                                          (:time-format preferences))
                       {:time-format (error :account-settings/error-time-format-invalid)}))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-preferences]
        (assoc preferences :_error errors :_saved? false)]]
      (persisted-effects
       current-member-id
       [{:db/id [:member/member-id current-member-id]
         :member/timezone (:time-zone preferences)
         :member/week-start (week-start-ident (:week-start preferences))
         :member/clock-format (clock-format-ident (:time-format preferences))}]
       [:account-preferences]
       {}
       :account-settings/preferences-saved-feedback))))

(def allowed-browser-permissions
  #{"default" "denied" "granted"})

(def notification-scope-ident
  {"everything" :notify.scope/everything
   "gigs" :notify.scope/gigs})

(def notification-unread-ident
  {"numbered" :notify.unread-style/numbered
   "unnumbered" :notify.unread-style/unnumbered})

(def notification-schedule-ident
  {"right-away" :notify.schedule/right-away
   "daily-batch" :notify.schedule/daily-batch})

(defn- local-time? [value]
  (boolean
   (and (string? value)
        (re-matches #"(?:[01]\d|2[0-3]):[0-5]\d" value)
        (try
          (LocalTime/parse value)
          true
          (catch DateTimeException _exception
            false)))))

(defn- notification-errors
  [{:keys [what unread-style batch-time delivery] :as notifications}]
  (let [delivery-time (:when notifications)]
    (merge
     (when-not (contains? notification-scope-ident what)
       {:what (error :account-settings/error-notification-what-invalid)})
     (when-not (contains? notification-unread-ident unread-style)
       {:unread-style (error :account-settings/error-unread-style-invalid)})
     (when-not (contains? notification-schedule-ident delivery-time)
       {:when (error :account-settings/error-notification-when-invalid)})
     (when (and (= "daily-batch" delivery-time)
                (not (local-time? batch-time)))
       {:batch-time (error :account-settings/error-batch-time-invalid)})
     (when-not (contains? allowed-browser-permissions (:browser-permission delivery))
       {:browser-permission
        (error :account-settings/error-browser-permission-invalid)}))))

(defn- notification-tx [member-id notifications]
  {:db/id [:member/member-id member-id]
   :member.notify/enabled? (boolean (:enabled? notifications))
   :member.notify/scope (notification-scope-ident (:what notifications))
   :member.notify/attendance-reminders?
   (boolean (get-in notifications [:reminders :attendance?]))
   :member.notify/poll-reminders?
   (boolean (get-in notifications [:reminders :polls?]))
   :member.notify/email? (boolean (get-in notifications [:delivery :email?]))
   :member.notify/browser? (boolean (get-in notifications [:delivery :browser?]))
   :member.notify/unread-style
   (notification-unread-ident (:unread-style notifications))
   :member.notify/schedule
   (notification-schedule-ident (:when notifications))
   :member.notify/batch-time (:batch-time notifications)})

(defn- notification-error-effects [notifications errors]
  [support/clear-loading
   [:app.datastar/assoc-state
    [:account-notifications]
    (assoc notifications :_error errors :_saved? false)]])

(defn- persist-notifications [member-id notifications]
  (let [notifications (select-keys notifications
                                   [:enabled? :what :reminders :delivery
                                    :unread-style :when :batch-time])
        errors (notification-errors notifications)]
    (if (seq errors)
      (notification-error-effects notifications errors)
      (persisted-effects
       member-id
       [(notification-tx member-id notifications)]
       [:account-notifications]
       {:delivery
        (select-keys (:delivery notifications)
                     [:browser-capable? :browser-permission])}
       :account-settings/notifications-saved-feedback))))

(defn toggle-notifications-action
  [{:keys [current-member-id]} {:keys [account-notifications]}]
  (persist-notifications current-member-id account-notifications))

(defn enable-browser-notifications-action
  [{:keys [current-member-id]} {:keys [account-notifications]}]
  (let [{:keys [browser-capable? browser-permission]}
        (:delivery account-notifications)]
    (cond
      (not browser-capable?)
      (notification-error-effects
       account-notifications
       {:browser (error :account-settings/error-browser-unsupported)})

      (not (contains? allowed-browser-permissions browser-permission))
      (notification-error-effects
       account-notifications
       {:browser-permission
        (error :account-settings/error-browser-permission-invalid)})

      :else
      (persist-notifications
       current-member-id
       (assoc-in account-notifications [:delivery :browser?] true)))))

(defn update-notification-settings-action
  [{:keys [current-member-id]} {:keys [account-notifications]}]
  (persist-notifications current-member-id account-notifications))

(defn- ->instant ^Instant [value]
  (cond
    (instance? Instant value) value
    (instance? Date value)    (.toInstant ^Date value)
    :else
    (throw (ex-info "Account break actions require an injected :now instant"
                    {:now value}))))

(defn- parse-date ^LocalDate [value]
  (when (seq value)
    (try
      (LocalDate/parse value)
      (catch DateTimeException _exception
        nil))))

(defn- normalize-break [break-state]
  (merge (select-keys break-state
                      [:active :start-choice :start-date :end-date])
         {:start-date (or (trimmed (:start-date break-state)) "")
          :end-date   (or (trimmed (:end-date break-state)) "")}))

(defn- break-errors
  [{:keys [active start-choice end-date]} effective-start-date]
  (let [^LocalDate start (parse-date effective-start-date)
        ^LocalDate end   (parse-date end-date)]
    (merge
     (when-not (boolean? active)
       {:active (error :account-settings/error-break-availability-invalid)})
     (when-not (contains? #{"now" "date"} start-choice)
       {:start-choice (error :account-settings/error-break-start-choice-invalid)})
     (when (and (= "date" start-choice) (nil? start))
       {:start-date (error :account-settings/error-date-invalid)})
     (when (and (seq end-date) (nil? end))
       {:end-date (error :account-settings/error-date-invalid)})
     (when (and start end (.isBefore end start))
       {:end-date (error :account-settings/error-break-date-order)}))))

(defn- member-timezone [db member-id]
  (or (:member/timezone (d/entity db [:member/member-id member-id]))
      "Europe/Berlin"))

(defn- effective-break-start-date
  [{:keys [now]} timezone {:keys [active start-choice start-date]}]
  (when active
    (if (= "now" start-choice)
      (-> (->instant now)
          (.atZone (ZoneId/of timezone))
          .toLocalDate
          str)
      start-date)))

(defn- break-success-effects [state break-state feedback]
  (let [{:keys [db current-member-id]} state
        timezone (member-timezone db current-member-id)
        start-date (effective-break-start-date state timezone break-state)
        end-date (when (:active break-state)
                   (not-empty (:end-date break-state)))]
    (persisted-effects
     current-member-id
     [{:db/id [:member/member-id current-member-id]
       :member.break/start-date start-date
       :member.break/end-date end-date}]
     [:account-break]
     {}
     feedback)))

(defn update-break-settings-action [state {:keys [account-break]}]
  (let [{:keys [db current-member-id]} state
        break-state (normalize-break account-break)
        timezone    (member-timezone db current-member-id)
        errors      (break-errors
                     break-state
                     (effective-break-start-date state timezone break-state))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-break]
        (assoc break-state :_error errors :_saved? false)]]
      (break-success-effects
       state
       break-state
       :account-settings/break-saved-feedback))))

(defn end-break-action [state _signals]
  (break-success-effects
   state
   (select-keys account.queries/default-break
                [:active :start-choice :start-date :end-date])
   :account-settings/break-ended-feedback))

(defn launch-app-action [_state {:keys [account-app]}]
  (let [platform (:platform account-app)]
    (if (contains? #{"ios" "android" "pwa"} platform)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-app]
        {:platform platform
         :_feedback
         (tr-node :account-settings/app-prototype-feedback
                  {:platform platform})}]]
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-app]
        {:platform platform
         :_error {:platform
                  (error :account-settings/error-app-platform-invalid)}}]])))

(def actions
  {::validate-profile-field       #'validate-profile-field-action
   ::stage-avatar                 #'stage-avatar-action
   ::remove-avatar                #'remove-avatar-action
   ::save-profile                 #'save-profile-action
   ::save-date-time-preferences   #'save-date-time-preferences-action
   ::toggle-notifications         #'toggle-notifications-action
   ::enable-browser-notifications #'enable-browser-notifications-action
   ::update-notification-settings #'update-notification-settings-action
   ::update-break-settings        #'update-break-settings-action
   ::end-break                    #'end-break-action
   ::launch-app                   #'launch-app-action})
