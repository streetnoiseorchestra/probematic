(ns app.account.actions-test
  (:require
   [app.account.actions :as actions]
   [app.nexus :as app-nexus]
   [app.nexus.actions :as nexus-actions]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(def max-avatar-size (* 5 1024 1024))

(defn seed-member!
  ([conn member-id]
   (seed-member! conn member-id {}))
  ([conn member-id overrides]
   @(d/transact
     conn
     [(merge {:member/member-id   member-id
              :member/name        "Ada Lovelace"
              :member/nick        "ada"
              :member/email       "ada@example.test"
              :member/username    "ada_l"
              :member/phone       "+436601111111"
              :member/active?     true
              :member/keycloak-id (str member-id)}
             overrides)])))

(defn action-state [{:keys [conn member-id]}]
  {:db                (d/db conn)
   :current-member-id member-id
   :now               #inst "2026-07-13T22:30:00.000-00:00"})

(defn transact-effects! [conn effects]
  (let [transactions (for [[effect tx-data opts] effects
                           :when (= :db/transact effect)]
                       [tx-data opts])]
    (when (seq transactions)
      @(d/transact conn (app-nexus/batch-transactions transactions)))))

(defn entity-value [db member-id attr]
  (get (d/entity db [:member/member-id member-id]) attr))

(defn enum-ident [value]
  (if (keyword? value) value (:db/ident value)))

(def valid-profile
  {:member-id       "00000000-0000-0000-0000-000000000099"
   :name            "  Ada Byron  "
   :nick            "  Countess  "
   :email           "  ADA.BYRON@EXAMPLE.TEST  "
   :username        "  ADA_BYRON  "
   :phone           "+43 660 1234567"
   :current-status  "  Rehearsing tonight  "
   :date-of-birth   "1815-12-10"
   :avatar-removed? false})

(def normalized-profile
  {:name            "Ada Byron"
   :nick            "Countess"
   :email           "ada.byron@example.test"
   :username        "ada_byron"
   :phone           "+436601234567"
   :current-status  "Rehearsing tonight"
   :date-of-birth   "1815-12-10"
   :avatar-removed? false
   :avatar          nil})

(def valid-notifications
  {:enabled? true
   :what "everything"
   :reminders {:attendance? true
               :polls? false}
   :delivery {:email? true
              :browser? true
              :browser-capable? true
              :browser-permission "default"}
   :unread-style "numbered"
   :when "daily-batch"
   :batch-time "13:00"})

(def valid-break
  {:active true
   :start-choice "date"
   :start-date "2026-07-01"
   :end-date "2026-07-20"})

(deftest account-action-map-owns-every-public-action-keyword
  (is (= #{:app.account.actions/validate-profile-field
           :app.account.actions/stage-avatar
           :app.account.actions/remove-avatar
           :app.account.actions/save-profile
           :app.account.actions/save-date-time-preferences
           :app.account.actions/toggle-notifications
           :app.account.actions/enable-browser-notifications
           :app.account.actions/update-notification-settings
           :app.account.actions/update-break-settings
           :app.account.actions/end-break
           :app.account.actions/launch-app}
         (set (keys actions/actions)))))

(deftest validate-profile-field-normalizes-persisted-profile-values
  (is (= [[:app.datastar/merge-state [:account-profile] normalized-profile]
          [:app.datastar/assoc-state [:account-profile :_error :email] nil]]
         (actions/validate-profile-field-action
          {}
          {:account-profile (assoc valid-profile :validate-field "email")})))
  (is (= [:i18n/tr :account-settings/error-date-invalid]
         (-> (actions/validate-profile-field-action
              {}
              {:account-profile (assoc valid-profile
                                       :date-of-birth "1815-02-30"
                                       :validate-field "date-of-birth")})
             second
             last
             :error)))
  (testing "email typo checking only requires an at sign"
    (is (nil? (-> (actions/validate-profile-field-action
                   {}
                   {:account-profile (assoc valid-profile
                                            :email "ada@localhost"
                                            :validate-field "email")})
                  second
                  last)))
    (is (= [:i18n/tr :account-settings/error-email-invalid]
           (-> (actions/validate-profile-field-action
                {}
                {:account-profile (assoc valid-profile
                                         :email "ada.example.test"
                                         :validate-field "email")})
               second
               last
               :error)))))

(deftest avatar-staging-validates-the-file-that-will-be-submitted
  (is (= [nexus-actions/clear-loading
          [:app.datastar/assoc-state
           [:account-profile :avatar]
           {:filename "portrait.webp"
            :mime-type "image/webp"
            :size 4096
            :staged? true}]
          [:app.datastar/assoc-state [:account-profile :avatar-removed?] false]
          [:app.datastar/assoc-state [:account-profile :_error :avatar] nil]
          [:app.datastar/assoc-state
           [:account-profile :_feedback]
           [:i18n/tr :account-settings/avatar-staged-feedback]]]
         (actions/stage-avatar-action
          {}
          {:account-profile
           (assoc valid-profile
                  :avatar {:filename "portrait.webp"
                           :mime-type "image/webp"
                           :size 4096})})))
  (doseq [[metadata error-key]
          [[{:filename "portrait.svg" :mime-type "image/svg+xml" :size 1024}
            :account-settings/error-avatar-type]
           [{:filename "portrait.png" :mime-type "image/png" :size (inc max-avatar-size)}
            :account-settings/error-avatar-size]
           [{:filename "" :mime-type "image/png" :size 1024}
            :account-settings/error-avatar-name]]]
    (let [effects (actions/stage-avatar-action
                   {}
                   {:account-profile (assoc valid-profile :avatar metadata)})]
      (is (= nexus-actions/clear-loading (first effects)))
      (is (= [:i18n/tr error-key]
             (get-in effects [1 2 :error])))))
  (is (= [nexus-actions/clear-loading
          [:app.datastar/assoc-state [:account-profile :avatar] nil]
          [:app.datastar/assoc-state [:account-profile :avatar-removed?] true]
          [:app.datastar/assoc-state
           [:account-profile :_feedback]
           [:i18n/tr :account-settings/avatar-removed-feedback]]]
         (actions/remove-avatar-action {} {}))))

(deftest save-profile-dispatches-one-authenticated-profile-effect
  (let [{:keys [conn member-id] :as system} (tc/new-system "account-profile-effect")
        other-id (random-uuid)]
    (seed-member! conn member-id)
    (seed-member! conn other-id
                  {:member/name "Other Member"
                   :member/nick "other"
                   :member/email "other@example.test"
                   :member/username "other_user"
                   :member/phone "+436602222222"})
    (is (= [[:app.account/save-profile
             {:member-id member-id
              :profile normalized-profile
              :avatar-upload nil
              :expected-avatar-id nil
              :sync-keycloak? true}]]
           (actions/save-profile-action
            (action-state system)
            {:account-profile (assoc valid-profile :member-id (str other-id))})))
    (is (= "Other Member"
           (entity-value (d/db conn) other-id :member/name)))))

(deftest save-profile-retries-keycloak-sync-after-datomic-already-matches
  (let [{:keys [conn member-id] :as system}
        (tc/new-system "account-profile-keycloak-retry")]
    (seed-member! conn member-id
                  {:member/name "Ada Byron"
                   :member/email "ada.byron@example.test"
                   :member/username "ada_byron"})
    (is (true?
         (get-in
          (actions/save-profile-action
           (action-state system)
           {:account-profile valid-profile})
          [0 1 :sync-keycloak?])))))

(deftest save-profile-validates-upload-metadata-before-dispatch
  (let [{:keys [conn] :as system} (tc/new-system "account-profile-upload-validation")]
    (seed-member! conn (:member-id system))
    (doseq [[avatar-upload error-key]
            [[{:filename "avatar.svg"
               :mime-type "image/svg+xml"
               :size 200
               :tempfile (java.io.File. "/tmp/unused-avatar.svg")}
              :account-settings/error-avatar-type]
             [{:filename "avatar.png"
               :mime-type "image/png"
               :size (inc max-avatar-size)
               :tempfile (java.io.File. "/tmp/unused-avatar.png")}
              :account-settings/error-avatar-size]
             [{:filename "avatar.png"
               :mime-type "image/png"
               :size "200"
               :tempfile (java.io.File. "/tmp/unused-avatar.png")}
              :account-settings/error-avatar-size]]]
      (let [effects (actions/save-profile-action
                     (action-state system)
                     {:account-profile valid-profile
                      :avatar-upload avatar-upload})]
        (is (= nexus-actions/clear-loading (first effects)))
        (is (= [:i18n/tr error-key]
               (get-in effects [1 2 :_error :avatar :error])))
        (is (not-any? #(= :app.account/save-profile (first %)) effects))))))

(deftest save-profile-preserves-complete-input-on-validation-errors
  (let [{:keys [conn member-id] :as system} (tc/new-system "account-profile-invalid")
        other-id (random-uuid)]
    (seed-member! conn member-id)
    (seed-member! conn other-id
                  {:member/nick "taken"
                   :member/email "taken@example.test"
                   :member/username "taken_user"
                   :member/phone "+436609999999"})
    (let [submitted (assoc valid-profile
                           :name " "
                           :nick " taken "
                           :email " TAKEN@EXAMPLE.TEST "
                           :username " TAKEN_USER "
                           :phone "+43 660 9999999"
                           :date-of-birth "1815-02-30")
          effects (actions/save-profile-action
                   (action-state system)
                   {:account-profile submitted})
          saved (get-in effects [1 2])]
      (is (= nexus-actions/clear-loading (first effects)))
      (is (= #{:name :nick :email :username :phone :date-of-birth :_top}
             (set (keys (:_error saved)))))
      (is (= "Rehearsing tonight" (:current-status saved)))
      (is (not-any? #(= :app.account/save-profile (first %)) effects)))))

(deftest save-date-time-preferences-persists-enum-refs
  (let [{:keys [conn member-id] :as system} (tc/new-system "account-preferences")
        submitted {:time-zone "Europe/Vienna"
                   :week-start "sunday"
                   :time-format "12-hour"}
        effects (actions/save-date-time-preferences-action
                 (action-state system)
                 {:account-preferences submitted})]
    (transact-effects! conn effects)
    (let [db (d/db conn)]
      (is (= "Europe/Vienna" (entity-value db member-id :member/timezone)))
      (is (= :week-start/sunday
             (enum-ident (entity-value db member-id :member/week-start))))
      (is (= :clock-format/hour-12
             (enum-ident (entity-value db member-id :member/clock-format)))))
    (is (= [:i18n/tr :account-settings/preferences-saved-feedback]
           (get-in effects [2 2 :_feedback])))
    (is (= #{:_error :_saved? :_feedback}
           (set (keys (get-in effects [2 2])))))
    (doseq [[field value error-key]
            [[:time-zone "Mars/Olympus" :account-settings/error-time-zone-invalid]
             [:week-start "friday" :account-settings/error-week-start-invalid]
             [:time-format "decimal" :account-settings/error-time-format-invalid]]]
      (let [invalid-effects
            (actions/save-date-time-preferences-action
             (action-state system)
             {:account-preferences (assoc submitted field value)})]
        (is (= [:i18n/tr error-key]
               (get-in invalid-effects [1 2 :_error field :error])))
        (is (not-any? #(= :db/transact (first %)) invalid-effects))))))

(deftest notification-actions-persist-a-complete-policy
  (let [{:keys [conn member-id] :as system} (tc/new-system "account-notifications")
        state (action-state system)
        effects (actions/update-notification-settings-action
                 state
                 {:account-notifications valid-notifications})]
    (transact-effects! conn effects)
    (let [db (d/db conn)]
      (is (true? (entity-value db member-id :member.notify/enabled?)))
      (is (= :notify.scope/everything
             (enum-ident (entity-value db member-id :member.notify/scope))))
      (is (true? (entity-value db member-id :member.notify/attendance-reminders?)))
      (is (false? (entity-value db member-id :member.notify/poll-reminders?)))
      (is (true? (entity-value db member-id :member.notify/email?)))
      (is (true? (entity-value db member-id :member.notify/browser?)))
      (is (= :notify.unread-style/numbered
             (enum-ident (entity-value db member-id :member.notify/unread-style))))
      (is (= :notify.schedule/daily-batch
             (enum-ident (entity-value db member-id :member.notify/schedule))))
      (is (= "13:00" (entity-value db member-id :member.notify/batch-time))))
    (is (= #{:_error :_saved? :_feedback :delivery}
           (set (keys (get-in effects [2 2])))))
    (is (= {:browser-capable? true
            :browser-permission "default"}
           (get-in effects [2 2 :delivery])))
    (testing "master toggle also writes every policy attribute"
      (let [toggle-effects (actions/toggle-notifications-action
                            (assoc state :db (d/db conn))
                            {:account-notifications
                             (assoc valid-notifications :enabled? false)})]
        (transact-effects! conn toggle-effects)
        (is (false? (entity-value (d/db conn)
                                  member-id
                                  :member.notify/enabled?)))
        (is (= "13:00" (entity-value (d/db conn)
                                     member-id
                                     :member.notify/batch-time)))))
    (testing "browser enable persists the submitted browser preference"
      (let [browser-effects
            (actions/enable-browser-notifications-action
             (assoc state :db (d/db conn))
             {:account-notifications
              (assoc-in valid-notifications [:delivery :browser?] false)})]
        (transact-effects! conn browser-effects)
        (is (true? (entity-value (d/db conn)
                                 member-id
                                 :member.notify/browser?)))))))

(deftest notification-batch-time-is-a-local-time-not-an-enum
  (let [system (tc/new-system "account-notification-time")]
    (doseq [time ["08:00" "13:00" "20:00" "07:35"]]
      (is (some #(= :db/transact (first %))
                (actions/update-notification-settings-action
                 (action-state system)
                 {:account-notifications (assoc valid-notifications
                                                :batch-time time)}))))
    (doseq [time ["morning" "24:00" "8am" ""]]
      (let [effects (actions/update-notification-settings-action
                     (action-state system)
                     {:account-notifications (assoc valid-notifications
                                                    :batch-time time)})]
        (is (= [:i18n/tr :account-settings/error-batch-time-invalid]
               (get-in effects [1 2 :_error :batch-time :error])))
        (is (not-any? #(= :db/transact (first %)) effects))))))

(deftest notification-validation-rejects-invalid-ref-values
  (let [system (tc/new-system "account-notification-validation")]
    (doseq [[path value error-path error-key]
            [[[:what] "some" [:what]
              :account-settings/error-notification-what-invalid]
             [[:unread-style] "dots" [:unread-style]
              :account-settings/error-unread-style-invalid]
             [[:when] "weekly" [:when]
              :account-settings/error-notification-when-invalid]
             [[:delivery :browser-permission] "maybe" [:browser-permission]
              :account-settings/error-browser-permission-invalid]]]
      (let [effects
            (actions/update-notification-settings-action
             (action-state system)
             {:account-notifications (assoc-in valid-notifications path value)})]
        (is (= [:i18n/tr error-key]
               (get-in effects (into [1 2 :_error]
                                     (conj error-path :error)))))
        (is (not-any? #(= :db/transact (first %)) effects))))))

(deftest break-actions-persist-start-and-end-on-the-member
  (let [{:keys [conn member-id] :as system} (tc/new-system "account-break")]
    (seed-member! conn member-id {:member/timezone "Europe/Berlin"})
    (let [effects (actions/update-break-settings-action
                   (action-state system)
                   {:account-break valid-break})]
      (transact-effects! conn effects)
      (is (= "2026-07-01"
             (entity-value (d/db conn) member-id :member.break/start-date)))
      (is (= "2026-07-20"
             (entity-value (d/db conn) member-id :member.break/end-date)))
      (is (= [:i18n/tr :account-settings/break-saved-feedback]
             (get-in effects [2 2 :_feedback]))))
    (testing "right now uses today in the persisted member time zone"
      (let [effects (actions/update-break-settings-action
                     (assoc (action-state system) :db (d/db conn))
                     {:account-break {:active true
                                      :start-choice "now"
                                      :start-date ""
                                      :end-date ""}})]
        (transact-effects! conn effects)
        (is (= "2026-07-14"
               (entity-value (d/db conn)
                             member-id
                             :member.break/start-date)))))
    (testing "turning availability on accepts a start date in the past"
      (let [effects (actions/update-break-settings-action
                     (assoc (action-state system) :db (d/db conn))
                     {:account-break (assoc valid-break
                                            :start-date "2020-01-02"
                                            :end-date "")})]
        (is (some #(= :db/transact (first %)) effects))))
    (testing "turning the break off retracts both attributes"
      (let [effects (actions/update-break-settings-action
                     (assoc (action-state system) :db (d/db conn))
                     {:account-break {:active false
                                      :start-choice "now"
                                      :start-date ""
                                      :end-date ""}})]
        (transact-effects! conn effects)
        (is (nil? (entity-value (d/db conn)
                                member-id
                                :member.break/start-date)))
        (is (nil? (entity-value (d/db conn)
                                member-id
                                :member.break/end-date)))))))

(deftest successful-break-actions-do-not-pin-durable-values-in-page-state
  (let [{:keys [conn member-id] :as system}
        (tc/new-system "account-break-transient-state")]
    (seed-member! conn member-id {:member/timezone "Europe/Berlin"})
    (let [effects (actions/update-break-settings-action
                   (action-state system)
                   {:account-break valid-break})]
      (is (= #{:_error :_saved? :_feedback}
             (set (keys (get-in effects [2 2]))))))))

(deftest break-actions-require-the-injected-clock
  (let [{:keys [conn member-id] :as system}
        (tc/new-system "account-break-clock")]
    (seed-member! conn member-id {:member/timezone "Europe/Berlin"})
    (is (thrown? clojure.lang.ExceptionInfo
                 (actions/update-break-settings-action
                  (dissoc (action-state system) :now)
                  {:account-break
                   {:active true
                    :start-choice "now"
                    :start-date ""
                    :end-date ""}})))))

(deftest break-validation-and-end-early-never-use-a-break-time-zone-signal
  (let [{:keys [conn member-id] :as system} (tc/new-system "account-break-validation")]
    (seed-member! conn member-id {:member/timezone "Pacific/Auckland"
                                  :member.break/start-date "2026-07-01"
                                  :member.break/end-date "2026-07-20"})
    (doseq [[submitted field error-key]
            [[(assoc valid-break :active "yes")
              :active :account-settings/error-break-availability-invalid]
             [(assoc valid-break :start-date "2026-02-30")
              :start-date :account-settings/error-date-invalid]
             [(assoc valid-break :end-date "2026-06-30")
              :end-date :account-settings/error-break-date-order]
             [(assoc valid-break
                     :start-choice "now"
                     :start-date ""
                     :end-date "2026-07-13")
              :end-date :account-settings/error-break-date-order]]]
      (let [effects (actions/update-break-settings-action
                     (action-state system)
                     {:account-break submitted})]
        (is (= [:i18n/tr error-key]
               (get-in effects [1 2 :_error field :error])))
        (is (not-any? #(= :db/transact (first %)) effects))))
    (let [effects (actions/end-break-action (action-state system) {})]
      (transact-effects! conn effects)
      (is (nil? (entity-value (d/db conn)
                              member-id
                              :member.break/start-date)))
      (is (nil? (entity-value (d/db conn)
                              member-id
                              :member.break/end-date)))
      (is (= [:i18n/tr :account-settings/break-ended-feedback]
             (get-in effects [2 2 :_feedback]))))))

(deftest launch-app-remains-a-high-fidelity-prototype-action
  (doseq [platform ["ios" "android" "pwa"]]
    (let [effects (actions/launch-app-action
                   {}
                   {:account-app {:platform platform}})]
      (is (= [:i18n/tr
              :account-settings/app-prototype-feedback
              {:platform platform}]
             (get-in effects [1 2 :_feedback])))
      (is (not-any? #(= :db/transact (first %)) effects)))))
