(ns app.account.actions
  (:require
   [app.form :as form]
   [app.members.domain :as members.domain]
   [app.nexus.actions :as support]
   [app.account.queries :as account.queries]
   [cljc.java-time.local-time :as jt.local-time]
   [clojure.string :as str]
   [datomic.api :as d]
   [tick.core :as t]))

(def max-avatar-size (* 5 1024 1024))

(def allowed-avatar-types
  #{"image/jpeg" "image/png" "image/webp"})

(defn- normalize-avatar [avatar]
  (when (map? avatar)
    (cond-> {:filename  (or (form/trim-value (:filename avatar)) "")
             :mime-type (or (form/trim-value (:mime-type avatar)) "")
             :size      (:size avatar)}
      (:staged? avatar) (assoc :staged? true))))

(defn- normalize-profile [profile]
  (let [phone (form/trim-value (:phone profile))]
    {:name           (or (form/trim-value (:name profile)) "")
     :nick           (or (form/trim-value (:nick profile)) "")
     :email          (some-> (:email profile)
                             form/trim-value
                             members.domain/clean-email)
     :username       (some-> (:username profile)
                             form/trim-value
                             members.domain/clean-username)
     :phone          (or (cond-> phone
                           (and (seq phone)
                                (members.domain/phone-valid? phone))
                           members.domain/clean-phone-number)
                         "")
     :current-status (or (form/trim-value (:current-status profile)) "")
     :date-of-birth  (or (form/trim-value (:date-of-birth profile)) "")
     :avatar-removed? (true? (:avatar-removed? profile))
     :avatar         (normalize-avatar (:avatar profile))}))

(defn- profile-format-errors [{:keys [name email username phone date-of-birth]}]
  (let [date-valid?
        (or (str/blank? date-of-birth)
            (try
              (form/date-value date-of-birth)
              true
              (catch Exception _exception
                false)))]
    (cond-> {}
      (str/blank? name)
      (assoc :name
             {:error [:i18n/tr :account-settings/error-name-required]})

      (str/blank? email)
      (assoc :email
             {:error [:i18n/tr :account-settings/error-email-required]})

      (and (seq email) (not (str/includes? email "@")))
      (assoc :email
             {:error [:i18n/tr :account-settings/error-email-invalid]})

      (str/blank? username)
      (assoc :username
             {:error [:i18n/tr :account-settings/error-username-required]})

      (and (seq username)
           (not (re-matches members.domain/username-regex username)))
      (assoc :username
             {:error [:i18n/tr :account-settings/error-username-invalid]})

      (and (seq phone) (not (members.domain/phone-valid? phone)))
      (assoc :phone
             {:error [:i18n/tr :account-settings/error-phone-invalid]})

      (not date-valid?)
      (assoc :date-of-birth
             {:error [:i18n/tr :account-settings/error-date-invalid]}))))

(defn- duplicate-profile-errors [db member-ref {:keys [nick email username phone]}]
  (cond-> {}
    (and db
         (seq nick)
         (support/lookup-taken-by-other? db [:member/nick nick] member-ref))
    (assoc :nick {:error [:i18n/tr :account-settings/error-nick-taken]})

    (and db
         (seq email)
         (support/lookup-taken-by-other? db [:member/email email] member-ref))
    (assoc :email {:error [:i18n/tr :account-settings/error-email-taken]})

    (and db
         (seq username)
         (support/lookup-taken-by-other? db [:member/username username] member-ref))
    (assoc :username
           {:error [:i18n/tr :account-settings/error-username-taken]})

    (and db
         (seq phone)
         (support/lookup-taken-by-other? db [:member/phone phone] member-ref))
    (assoc :phone {:error [:i18n/tr :account-settings/error-phone-taken]})))

(defn- profile-errors [db member-id profile]
  (let [errors (merge (profile-format-errors profile)
                      (duplicate-profile-errors
                       db
                       [:member/member-id member-id]
                       profile))]
    (cond-> errors
      (seq errors)
      (assoc :_top
             {:error [:i18n/tr :account-settings/error-profile-invalid]}))))

(defn validate-profile-field-action
  [{:keys [db current-member-id]} {:keys [account-profile]}]
  (let [field   (some-> (:validate-field account-profile) keyword)
        profile (normalize-profile account-profile)
        errors  (profile-errors db current-member-id profile)]
    (cond-> [[:app.datastar/merge-state [:account-profile] profile]]
      field (conj [:app.datastar/assoc-state
                   [:account-profile :_error field]
                   (get errors field)]))))

(defn- normalize-avatar-upload [avatar]
  (when (map? avatar)
    {:filename (or (form/trim-value (:filename avatar)) "")
     :mime-type (or (form/trim-value (:mime-type avatar)) "")
     :size (:size avatar)
     :tempfile (:tempfile avatar)}))

(defn- avatar-error [{:keys [filename mime-type size]}]
  (cond
    (str/blank? filename)
    {:error [:i18n/tr :account-settings/error-avatar-name]}

    (not (contains? allowed-avatar-types mime-type))
    {:error [:i18n/tr :account-settings/error-avatar-type]}

    (or (not (integer? size)) (neg? size) (< max-avatar-size size))
    {:error [:i18n/tr :account-settings/error-avatar-size]}))

(defn stage-avatar-action [_state {:keys [account-profile]}]
  (let [avatar (normalize-avatar (:avatar account-profile))]
    (if-let [avatar-error (avatar-error avatar)]
      [support/clear-loading
       [:app.datastar/assoc-state [:account-profile :_error :avatar] avatar-error]]
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-profile :avatar]
        (assoc avatar :staged? true)]
       [:app.datastar/assoc-state [:account-profile :avatar-removed?] false]
       [:app.datastar/assoc-state [:account-profile :_error :avatar] nil]
       [:app.datastar/assoc-state
        [:account-profile :_feedback]
        [:i18n/tr :account-settings/avatar-staged-feedback]]])))

(defn remove-avatar-action [_state _signals]
  [support/clear-loading
   [:app.datastar/assoc-state [:account-profile :avatar] nil]
   [:app.datastar/assoc-state [:account-profile :avatar-removed?] true]
   [:app.datastar/assoc-state
    [:account-profile :_feedback]
    [:i18n/tr :account-settings/avatar-removed-feedback]]])

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

(def ^:private saved-profile-state
  {:avatar nil
   :avatar-removed? false
   :_error {}
   :_saved? true
   :_feedback [:i18n/tr :account-settings/profile-saved-feedback]})

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
        {:_top
         {:error [:i18n/tr :account-settings/error-current-member-missing]}})
       avatar-upload)
      (let [avatar-error (when avatar-upload (avatar-error avatar-upload))
            errors (cond-> (profile-errors db current-member-id profile)
                     avatar-error (assoc :avatar avatar-error))]
        (if (seq errors)
          (discard-upload (profile-error-effects profile errors) avatar-upload)
          [[:app.account/save-profile
            {:member-id current-member-id
             :profile profile
             :avatar-upload avatar-upload
             :sync-keycloak? (keycloak-sync-required? member)}]
           [:app.datastar/assoc-state
            [:account-profile]
            saved-profile-state]
           [:app.datastar/respond-sse
            [[:app.datastar.sse/merge-signals
              {:account-profile (merge profile saved-profile-state)}]
             [:app.datastar.sse/execute-script
              "window.StreetnoiseAccountAvatar.saved();"]]]])))))

(defn- valid-zone? [value]
  (boolean
   (and (seq value)
        (try
          (t/zone value)
          true
          (catch Exception _exception
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
           :_feedback [:i18n/tr feedback])]])

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
        errors
        (cond-> {}
          (not (valid-zone? (:time-zone preferences)))
          (assoc :time-zone
                 {:error
                  [:i18n/tr :account-settings/error-time-zone-invalid]})

          (not (contains? week-start-ident (:week-start preferences)))
          (assoc :week-start
                 {:error
                  [:i18n/tr :account-settings/error-week-start-invalid]})

          (not (contains? clock-format-ident (:time-format preferences)))
          (assoc :time-format
                 {:error
                  [:i18n/tr :account-settings/error-time-format-invalid]}))]
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
        (try
          (jt.local-time/parse value)
          true
          (catch Exception _exception
            false)))))

(defn- notification-errors
  [{:keys [what unread-style batch-time delivery] :as notifications}]
  (let [delivery-time (:when notifications)]
    (cond-> {}
      (not (contains? notification-scope-ident what))
      (assoc :what
             {:error
              [:i18n/tr :account-settings/error-notification-what-invalid]})

      (not (contains? notification-unread-ident unread-style))
      (assoc :unread-style
             {:error
              [:i18n/tr :account-settings/error-unread-style-invalid]})

      (not (contains? notification-schedule-ident delivery-time))
      (assoc :when
             {:error
              [:i18n/tr :account-settings/error-notification-when-invalid]})

      (and (= "daily-batch" delivery-time)
           (not (local-time? batch-time)))
      (assoc :batch-time
             {:error
              [:i18n/tr :account-settings/error-batch-time-invalid]})

      (not (contains? allowed-browser-permissions
                      (:browser-permission delivery)))
      (assoc :browser-permission
             {:error
              [:i18n/tr
               :account-settings/error-browser-permission-invalid]}))))

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
       {:browser
        {:error [:i18n/tr :account-settings/error-browser-unsupported]}})

      (not (contains? allowed-browser-permissions browser-permission))
      (notification-error-effects
       account-notifications
       {:browser-permission
        {:error
         [:i18n/tr :account-settings/error-browser-permission-invalid]}})

      :else
      (persist-notifications
       current-member-id
       (assoc-in account-notifications [:delivery :browser?] true)))))

(defn update-notification-settings-action
  [{:keys [current-member-id]} {:keys [account-notifications]}]
  (persist-notifications current-member-id account-notifications))

(defn- ->instant [value]
  (cond
    (t/instant? value) value
    (inst? value)       (t/instant value)
    :else
    (throw (ex-info "Account break actions require an injected :now instant"
                    {:now value}))))

(defn- parse-date [value]
  (when (seq value)
    (try
      (t/date value)
      (catch Exception _exception
        nil))))

(defn- normalize-break [break-state]
  (merge (select-keys break-state
                      [:active :start-choice :start-date :end-date])
         {:start-date (or (form/trim-value (:start-date break-state)) "")
          :end-date   (or (form/trim-value (:end-date break-state)) "")}))

(defn- break-errors
  [{:keys [active start-choice end-date]} effective-start-date]
  (let [start (parse-date effective-start-date)
        end   (parse-date end-date)]
    (cond-> {}
      (not (boolean? active))
      (assoc :active
             {:error
              [:i18n/tr :account-settings/error-break-availability-invalid]})

      (not (contains? #{"now" "date"} start-choice))
      (assoc :start-choice
             {:error
              [:i18n/tr :account-settings/error-break-start-choice-invalid]})

      (and (= "date" start-choice) (nil? start))
      (assoc :start-date
             {:error [:i18n/tr :account-settings/error-date-invalid]})

      (and (seq end-date) (nil? end))
      (assoc :end-date
             {:error [:i18n/tr :account-settings/error-date-invalid]})

      (and start end (t/< end start))
      (assoc :end-date
             {:error [:i18n/tr :account-settings/error-break-date-order]}))))

(defn- member-timezone [db member-id]
  (or (:member/timezone (d/entity db [:member/member-id member-id]))
      "Europe/Berlin"))

(defn- effective-break-start-date
  [{:keys [now]} timezone {:keys [active start-choice start-date]}]
  (when active
    (if (= "now" start-choice)
      (-> (->instant now)
          (t/in timezone)
          t/date
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
         [:i18n/tr :account-settings/app-prototype-feedback
          {:platform platform}]}]]
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-app]
        {:platform platform
         :_error {:platform
                  {:error
                   [:i18n/tr
                    :account-settings/error-app-platform-invalid]}}}]])))

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
