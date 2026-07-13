(ns app.account.actions
  (:require
   [app.form :as form]
   [app.members.domain :as members.domain]
   [app.nexus.actions :as support]
   [app.account.queries :as account.queries]
   [clojure.string :as str]
   [datomic.api :as d])
  (:import
   [java.time DateTimeException Instant LocalDate ZoneId ZonedDateTime]
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

(defn- member-tx [member-id profile]
  {:db/id           [:member/member-id member-id]
   :member/name     (:name profile)
   :member/nick     (not-empty (:nick profile))
   :member/email    (:email profile)
   :member/username (:username profile)
   :member/phone    (not-empty (:phone profile))})

(defn- keycloak-sync-needed? [member profile]
  (and (:member/keycloak-id member)
       (not= (select-keys member [:member/name :member/email :member/username])
             {:member/name     (:name profile)
              :member/email    (:email profile)
              :member/username (:username profile)})))

(defn- profile-error-effects [profile errors]
  [support/clear-loading
   [:app.datastar/assoc-state
    [:account-profile]
    (assoc profile :_error errors :_saved? false)]])

(defn save-profile-action
  [{:keys [db current-member-id]} {:keys [account-profile]}]
  (let [profile (normalize-profile account-profile)
        member  (when (and db current-member-id)
                  (d/entity db [:member/member-id current-member-id]))]
    (if-not (:db/id member)
      (profile-error-effects
       profile
       {:_top (error :account-settings/error-current-member-missing)})
      (let [errors (profile-errors db current-member-id profile)]
        (if (seq errors)
          (profile-error-effects profile errors)
          (cond-> [[:db/transact
                    (support/with-audit [(member-tx current-member-id profile)]
                      current-member-id)
                    {:transact-w-nils? true}]]
            (keycloak-sync-needed? member profile)
            (conj [:app.members/update-keycloak-meta current-member-id])

            true
            (conj support/clear-loading
                  [:app.datastar/assoc-state
                   [:account-profile]
                   (assoc profile
                          :_error {}
                          :_saved? true
                          :_feedback
                          (tr-node :account-settings/profile-saved-feedback))])))))))

(defn- valid-zone? [value]
  (boolean
   (and (seq value)
        (try
          (ZoneId/of value)
          true
          (catch DateTimeException _exception
            false)))))

(defn- save-prototype-effects [path value feedback]
  [support/clear-loading
   [:app.datastar/assoc-state
    path
    (assoc value
           :_error {}
           :_saved? true
           :_feedback (tr-node feedback))]])

(defn save-date-time-preferences-action
  [_state {:keys [account-preferences]}]
  (let [preferences (select-keys account-preferences
                                 [:time-zone :week-start :time-format])
        errors      (merge
                     (when-not (valid-zone? (:time-zone preferences))
                       {:time-zone (error :account-settings/error-time-zone-invalid)})
                     (when-not (contains? #{"monday" "sunday"}
                                          (:week-start preferences))
                       {:week-start (error :account-settings/error-week-start-invalid)})
                     (when-not (contains? #{"12-hour" "24-hour"}
                                          (:time-format preferences))
                       {:time-format (error :account-settings/error-time-format-invalid)}))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-preferences]
        (assoc preferences :_error errors :_saved? false)]]
      (save-prototype-effects
       [:account-preferences]
       preferences
       :account-settings/preferences-saved-feedback))))

(defn toggle-notifications-action [_state {:keys [account-notifications]}]
  [support/clear-loading
   [:app.datastar/merge-state
    [:account-notifications]
    {:enabled? (boolean (:enabled? account-notifications))
     :_error   {}
     :_saved?  true}]])

(def allowed-browser-permissions
  #{"default" "denied" "granted"})

(defn enable-browser-notifications-action
  [_state {:keys [account-notifications]}]
  (let [{:keys [browser-capable? browser-permission]}
        (:delivery account-notifications)]
    (cond
      (not browser-capable?)
      [support/clear-loading
       [:app.datastar/merge-state
        [:account-notifications]
        {:delivery {:browser-capable? false
                    :browser-permission (or browser-permission "default")
                    :browser? false}
         :_error {:browser (error :account-settings/error-browser-unsupported)}}]]

      (not (contains? allowed-browser-permissions browser-permission))
      [support/clear-loading
       [:app.datastar/merge-state
        [:account-notifications]
        {:delivery {:browser-capable? true
                    :browser-permission browser-permission
                    :browser? false}
         :_error {:browser-permission
                  (error :account-settings/error-browser-permission-invalid)}}]]

      :else
      [support/clear-loading
       [:app.datastar/merge-state
        [:account-notifications]
        {:delivery {:browser-capable? true
                    :browser-permission browser-permission
                    :browser? true}
         :_error {}
         :_saved? true}]])))

(defn- notification-errors
  [{:keys [what unread-style batch-time delivery] :as notifications}]
  (let [delivery-time (:when notifications)]
    (merge
     (when-not (contains? #{"everything" "gigs"} what)
       {:what (error :account-settings/error-notification-what-invalid)})
     (when-not (contains? #{"numbered" "unnumbered"} unread-style)
       {:unread-style (error :account-settings/error-unread-style-invalid)})
     (when-not (contains? #{"right-away" "daily-batch"} delivery-time)
       {:when (error :account-settings/error-notification-when-invalid)})
     (when (and (= "daily-batch" delivery-time)
                (not (contains? #{"morning" "afternoon" "evening"} batch-time)))
       {:batch-time (error :account-settings/error-batch-time-invalid)})
     (when-not (contains? allowed-browser-permissions (:browser-permission delivery))
       {:browser-permission
        (error :account-settings/error-browser-permission-invalid)}))))

(defn update-notification-settings-action
  [_state {:keys [account-notifications]}]
  (let [notifications (select-keys account-notifications
                                   [:enabled? :what :reminders :delivery
                                    :unread-style :when :batch-time])
        errors        (notification-errors notifications)]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-notifications]
        (assoc notifications :_error errors :_saved? false)]]
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-notifications]
        (assoc notifications :_error {} :_saved? true)]])))

(defn- ->instant ^Instant [value]
  (cond
    (instance? Instant value) value
    (instance? Date value)    (.toInstant ^Date value)
    :else                     (Instant/now)))

(defn- parse-date ^LocalDate [value]
  (when (seq value)
    (try
      (LocalDate/parse value)
      (catch DateTimeException _exception
        nil))))

(defn- normalize-break [break-state]
  (merge (select-keys break-state
                      [:active :start-choice :start-date :end-date :time-zone])
         {:start-date (or (trimmed (:start-date break-state)) "")
          :end-date   (or (trimmed (:end-date break-state)) "")}))

(defn- break-errors [{:keys [active start-choice start-date end-date time-zone]}]
  (let [^LocalDate start (parse-date start-date)
        ^LocalDate end   (parse-date end-date)]
    (merge
     (when-not (boolean? active)
       {:active (error :account-settings/error-break-availability-invalid)})
     (when-not (contains? #{"now" "date"} start-choice)
       {:start-choice (error :account-settings/error-break-start-choice-invalid)})
     (when-not (valid-zone? time-zone)
       {:time-zone (error :account-settings/error-time-zone-invalid)})
     (when (and (= "date" start-choice) (nil? start))
       {:start-date (error :account-settings/error-date-invalid)})
     (when (and (seq end-date) (nil? end))
       {:end-date (error :account-settings/error-date-invalid)})
     (when (and start end (.isBefore end start))
       {:end-date (error :account-settings/error-break-date-order)}))))

(defn- break-status [now {:keys [active start-choice start-date end-date time-zone]}]
  (if-not active
    "available"
    (let [^ZoneId zone       (ZoneId/of time-zone)
          ^Instant instant   (->instant now)
          ^ZonedDateTime zoned (.atZone instant zone)
          ^LocalDate today   (.toLocalDate zoned)
          ^LocalDate start   (when (= "date" start-choice) (parse-date start-date))
          ^LocalDate end     (parse-date end-date)]
      (cond
        (and end (.isBefore end today)) "ended"
        (and start (.isAfter start today)) "scheduled"
        :else "away"))))

(defn update-break-settings-action [{:keys [now]} {:keys [account-break]}]
  (let [break-state (normalize-break account-break)
        errors      (break-errors break-state)]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-break]
        (assoc break-state :_error errors :_saved? false)]]
      [support/clear-loading
       [:app.datastar/assoc-state
        [:account-break]
        (assoc break-state
               :status (break-status now break-state)
               :_error {}
               :_saved? true)]])))

(defn end-break-action [_state {:keys [account-break]}]
  (let [time-zone (if (valid-zone? (:time-zone account-break))
                    (:time-zone account-break)
                    "Europe/Berlin")]
    (save-prototype-effects
     [:account-break]
     (assoc account.queries/default-break :time-zone time-zone)
     :account-settings/break-ended-feedback)))

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
