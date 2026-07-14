(ns app.account.actions-test
  (:require
   [app.account.test-support :as support]
   [app.account.actions :as actions]
   [app.nexus :as app-nexus]
   [app.nexus.actions :as nexus-actions]
   [app.queries :as queries]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(def max-avatar-size (* 5 1024 1024))

(defn action [symbol]
  (let [action-fn (support/public-fn symbol)]
    (is (fn? action-fn) (str symbol " should exist"))
    action-fn))

(defn seed-member!
  ([conn member-id]
   (seed-member! conn member-id {}))
  ([conn member-id overrides]
   @(d/transact
     conn
     [(merge {:member/member-id  member-id
              :member/name       "Ada Lovelace"
              :member/nick       "ada"
              :member/email      "ada@example.test"
              :member/username   "ada_l"
              :member/phone      "+436601111111"
              :member/active?    true
              :member/keycloak-id (str member-id)}
             overrides)])))

(defn action-state [{:keys [conn member-id]}]
  {:db                (d/db conn)
   :current-member-id member-id
   :now               #inst "2026-07-13T12:00:00.000-00:00"})
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
  {:member-id      "00000000-0000-0000-0000-000000000099"
   :name           "  Ada Byron  "
   :nick           "  Countess  "
   :email          "  ADA.BYRON@EXAMPLE.TEST  "
   :username       "  ADA_BYRON  "
   :phone          "+43 660 1234567"
   :current-status "  Rehearsing tonight  "
   :date-of-birth  "1815-12-10"
   :avatar-removed? false
   :avatar         {:filename "portrait.png"
                    :mime-type "image/png"
                    :size 2048
                    :staged? true}})

(def normalized-profile
  {:name           "Ada Byron"
   :nick           "Countess"
   :email          "ada.byron@example.test"
   :username       "ada_byron"
   :phone          "+436601234567"
   :current-status "Rehearsing tonight"
   :date-of-birth  "1815-12-10"
   :avatar-removed? false
   :avatar         {:filename "portrait.png"
                    :mime-type "image/png"
                    :size 2048
                    :staged? true}})

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
  (let [actions (support/public-value 'app.account.actions/actions)]
    (is (map? actions) "app.account.actions/actions should exist")
    (when (map? actions)
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
             (set (keys actions)))))))

(deftest validate-profile-field-normalizes-the-complete-signal-map
  (when-let [validate-field (action 'app.account.actions/validate-profile-field-action)]
    (is (= [[:app.datastar/merge-state [:account-profile] normalized-profile]
            [:app.datastar/assoc-state [:account-profile :_error :email] nil]]
           (validate-field
            {}
            {:account-profile (assoc valid-profile :validate-field "email")})))
    (is (= [:i18n/tr :account-settings/error-name-required]
           (-> (validate-field
                {}
                {:account-profile (assoc valid-profile
                                         :name " "
                                         :validate-field "name")})
               second
               last
               :error)))
    (is (= [:i18n/tr :account-settings/error-date-invalid]
           (-> (validate-field
                {}
                {:account-profile (assoc valid-profile
                                         :date-of-birth "1815-02-30"
                                         :validate-field "date-of-birth")})
               second
               last
               :error)))))

(deftest avatar-actions-validate-real-file-metadata-and-preserve-the-form
  (when-let [stage-avatar (action 'app.account.actions/stage-avatar-action)]
    (is (= [nexus-actions/clear-loading
            [:app.datastar/assoc-state
             [:account-profile :avatar]
             {:filename "portrait.webp"
              :mime-type "image/webp"
              :size 4096
              :staged? true}]
            [:app.datastar/assoc-state
             [:account-profile :avatar-removed?]
             false]
            [:app.datastar/assoc-state [:account-profile :_error :avatar] nil]
            [:app.datastar/assoc-state
             [:account-profile :_feedback]
             [:i18n/tr :account-settings/avatar-staged-feedback]]]
           (stage-avatar
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
      (let [effects (stage-avatar
                     {}
                     {:account-profile (assoc valid-profile :avatar metadata)})]
        (is (= nexus-actions/clear-loading (first effects)))
        (is (= [:i18n/tr error-key]
               (get-in effects [1 2 :error])))
        (is (= 2 (count effects))))))
  (when-let [remove-avatar (action 'app.account.actions/remove-avatar-action)]
    (is (= [nexus-actions/clear-loading
            [:app.datastar/assoc-state [:account-profile :avatar] nil]
            [:app.datastar/assoc-state [:account-profile :avatar-removed?] true]
            [:app.datastar/assoc-state
             [:account-profile :_feedback]
             [:i18n/tr :account-settings/avatar-removed-feedback]]]
           (remove-avatar {} {:account-profile valid-profile})))))

(deftest save-profile-targets-only-the-authenticated-member
  (when-let [save-profile (action 'app.account.actions/save-profile-action)]
    (let [{:keys [conn member-id] :as system} (tc/new-system "account-save-self")
          other-id                            (random-uuid)]
      (seed-member! conn member-id)
      (seed-member! conn other-id
                    {:member/name        "Other Member"
                     :member/nick        "other"
                     :member/email       "other@example.test"
                     :member/username    "other_user"
                     :member/phone       "+436602222222"
                     :member/keycloak-id (str other-id)})
      (let [effects (save-profile
                     (action-state system)
                     {:account-profile
                      (assoc valid-profile :member-id (str other-id))})]
        (is (= [[:db/transact
                 [{:db/id            [:member/member-id member-id]
                   :member/name      "Ada Byron"
                   :member/nick      "Countess"
                   :member/email     "ada.byron@example.test"
                   :member/username  "ada_byron"
                   :member/phone     "+436601234567"}
                  [:db/add "datomic.tx" :audit/user [:member/member-id member-id]]]
                 {:transact-w-nils? true}]
                [:app.members/update-keycloak-meta member-id]
                nexus-actions/clear-loading
                [:app.datastar/assoc-state
                 [:account-profile]
                 (assoc normalized-profile
                        :_error {}
                        :_saved? true
                        :_feedback [:i18n/tr :account-settings/profile-saved-feedback])]]
               effects))
        (let [[_ tx-data opts] (first effects)]
          @(d/transact conn (app-nexus/batch-transactions [[tx-data opts]])))
        (is (= {:member/name     "Ada Byron"
                :member/nick     "Countess"
                :member/email    "ada.byron@example.test"
                :member/username "ada_byron"
                :member/phone    "+436601234567"}
               (select-keys (queries/retrieve-member (d/db conn) member-id)
                            [:member/name
                             :member/nick
                             :member/email
                             :member/username
                             :member/phone])))
        (is (= {:member/name     "Other Member"
                :member/email    "other@example.test"
                :member/username "other_user"}
               (select-keys (queries/retrieve-member (d/db conn) other-id)
                            [:member/name :member/email :member/username])))))))

(deftest save-profile-retracts-cleared-optional-existing-attributes
  (when-let [save-profile (action 'app.account.actions/save-profile-action)]
    (let [{:keys [conn member-id] :as system} (tc/new-system "account-save-retract")]
      (seed-member! conn member-id)
      (let [effects (save-profile
                     (action-state system)
                     {:account-profile (assoc valid-profile :nick " " :phone " ")})
            [_ tx-data opts] (first effects)]
        @(d/transact conn (app-nexus/batch-transactions [[tx-data opts]]))
        (is (= {:member/nick nil :member/phone nil}
               (select-keys (merge {:member/nick nil :member/phone nil}
                                   (queries/retrieve-member (d/db conn) member-id))
                            [:member/nick :member/phone])))))))

(deftest save-profile-preserves-complete-input-on-validation-errors
  (when-let [save-profile (action 'app.account.actions/save-profile-action)]
    (let [{:keys [conn member-id] :as system} (tc/new-system "account-save-invalid")
          other-id                            (random-uuid)]
      (seed-member! conn member-id)
      (seed-member! conn other-id
                    {:member/nick        "taken"
                     :member/email       "taken@example.test"
                     :member/username    "taken_user"
                     :member/phone       "+436609999999"
                     :member/keycloak-id (str other-id)})
      (let [submitted (assoc valid-profile
                             :name " "
                             :nick " taken "
                             :email " TAKEN@EXAMPLE.TEST "
                             :username " TAKEN_USER "
                             :phone "+43 660 9999999"
                             :date-of-birth "1815-02-30")
            effects   (save-profile (action-state system)
                                    {:account-profile submitted})
            saved     (get-in effects [1 2])]
        (is (= nexus-actions/clear-loading (first effects)))
        (is (= [:app.datastar/assoc-state :account-profile]
               [(get-in effects [1 0]) (get-in effects [1 1 0])]))
        (is (= {:name           ""
                :nick           "taken"
                :email          "taken@example.test"
                :username       "taken_user"
                :phone          "+436609999999"
                :current-status "Rehearsing tonight"
                :date-of-birth  "1815-02-30"
                :avatar-removed? false
                :avatar         (:avatar normalized-profile)}
               (select-keys saved (keys normalized-profile))))
        (is (= #{:name :nick :email :username :phone :date-of-birth :_top}
               (set (keys (:_error saved)))))
        (is (not-any? #(= :db/transact (first %)) effects))))))

(deftest save-profile-rejects-a-missing-authenticated-member
  (when-let [save-profile (action 'app.account.actions/save-profile-action)]
    (let [{:keys [conn]} (tc/new-system "account-save-missing")
          effects (save-profile {:db (d/db conn)
                                 :current-member-id (random-uuid)}
                                {:account-profile valid-profile})]
      (is (= nexus-actions/clear-loading (first effects)))
      (is (= [:i18n/tr :account-settings/error-current-member-missing]
             (get-in effects [1 2 :_error :_top :error])))
      (is (not-any? #(= :db/transact (first %)) effects)))))

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

(deftest launch-app-records-prototype-feedback-without-navigation-or-persistence
  (when-let [launch-app (action 'app.account.actions/launch-app-action)]
    (doseq [platform ["ios" "android" "pwa"]]
      (let [effects (launch-app {} {:account-app {:platform platform}})]
        (is (= [nexus-actions/clear-loading
                [:app.datastar/assoc-state
                 [:account-app]
                 {:platform platform
                  :_feedback [:i18n/tr
                              :account-settings/app-prototype-feedback
                              {:platform platform}]}]]
               effects))
        (is (not-any? #(contains? #{:db/transact :app.datastar/redirect}
                                  (first %))
                      effects))))
    (is (= [:i18n/tr :account-settings/error-app-platform-invalid]
           (-> (launch-app {} {:account-app {:platform "blackberry"}})
               second last :_error :platform :error)))))
