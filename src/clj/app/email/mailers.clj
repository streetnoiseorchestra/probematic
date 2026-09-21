(ns app.email.mailers
  "Prepares named email invocations without delivering them."
  (:require
   [app.email.domain :as domain]
   [app.email.messages :as messages]
   [app.insurance.queries :as insurance-queries]
   [app.members.queries :as members-queries]
   [app.poll.queries :as poll-queries]
   [app.email.templates :as tmpl]
   [app.i18n :as i18n]
   [app.queries :as q]
   [app.schemas :as s]
   [clojure.data :as data]
   [datomic.api :as d]))

(defn build-gig-updated-email
  "Builds the shared-body batch with the supplied `:email-id`."
  [{:keys [tr email-id] :as context} gig members edited-attrs]
  (let [message {:subject (tr [:email-subject/gig-updated] {:gig-title (:gig/title gig)})
                 :html    (tmpl/gig-updated-email-html context gig edited-attrs)
                 :text    (tmpl/gig-updated-email-plain context gig edited-attrs)}]
    {:email/sender   :lettermint
     :email/batch?   true
     :email/email-id email-id
     :email/messages (mapv #(assoc message :to [(:member/email %)]) members)}))

(defn gig-updated
  "Prepares a gig-update batch with historical content and current recipients."
  [{:keys [db execution-db legacy-recipient-snapshot?] :as context}
   {:keys [gig-id member-ids edited-attrs]}]
  (let [gig     (q/retrieve-gig db gig-id)
        members (if (and legacy-recipient-snapshot? (seq member-ids))
                  (let [members-by-id (into {}
                                            (map (juxt :member/member-id identity))
                                            (q/active-members db))]
                    (mapv members-by-id member-ids))
                  (->> (q/active-members execution-db)
                       (sort-by :member/member-id)
                       vec))]
    (when-not (every? some? ((juxt :gig/gig-id :gig/title :gig/date :gig/status :gig/gig-type) gig))
      (throw (ex-info "Gig snapshot is missing required data"
                      {:email/permanent? true :email/reason :missing-gig-data})))
    (if (seq members)
      (build-gig-updated-email context gig members edited-attrs)
      ::skip)))

(defn gig-committed-update
  "Prepares an explicitly requested notification from the committed gig change."
  [{:keys [db db-before] :as context} {:keys [gig-id] :as arguments}]
  (let [before  (q/retrieve-gig db-before gig-id)
        after   (q/retrieve-gig db gig-id)
        changed (keys (apply merge (take 2 (data/diff before after))))]
    (if (seq changed)
      (gig-updated context (assoc arguments :edited-attrs (vec (sort changed))))
      ::skip)))

(defn gig-created
  [{:keys [db execution-db legacy-recipient-snapshot?] :as context}
   {:keys [gig-id member-ids]}]
  (let [members (if (and legacy-recipient-snapshot? (seq member-ids))
                  (mapv #(q/retrieve-member db %) member-ids)
                  (->> (q/active-members execution-db)
                       (sort-by :member/member-id)
                       vec))]
    (if (seq members)
      (messages/build-gig-created-email context (q/retrieve-gig db gig-id) members)
      ::skip)))

(defn- scheduled-reminder-member-ids
  [db gig-id reminder-ids]
  (let [eligible (set (q/gig-reminder-member-ids db gig-id))]
    (->> reminder-ids
         (keep #(d/entity db [:reminder/reminder-id %]))
         (filter #(= :reminder-status/queued (:reminder/reminder-status %)))
         (filter #(= gig-id (-> % :reminder/gig :gig/gig-id)))
         (map #(-> % :reminder/member :member/member-id))
         (filter eligible)
         distinct
         vec)))

(defn gig-reminder
  [{:keys [db execution-db legacy-recipient-snapshot?] :as context}
   {:keys [gig-id reminder-ids member-ids]}]
  (let [recipient-db  (if legacy-recipient-snapshot? db execution-db)
        eligible      (q/gig-reminder-member-ids execution-db gig-id)
        recipient-ids (cond
                        (and legacy-recipient-snapshot? (seq member-ids))
                        member-ids

                        (seq reminder-ids)
                        (scheduled-reminder-member-ids execution-db gig-id reminder-ids)

                        :else
                        eligible)
        members       (mapv #(q/retrieve-member recipient-db %) recipient-ids)]
    (if (seq members)
      (messages/build-gig-reminder-email context (q/retrieve-gig db gig-id) members)
      ::skip)))

(defn rehearsal-leader [{:keys [db] :as context} {:keys [gig-id member-id]}]
  (messages/build-rehearsal-leader-email context (q/retrieve-gig db gig-id) (q/retrieve-member db member-id)))

(defn poll-opened
  [{:keys [db execution-db legacy-recipient-snapshot?] :as context}
   {:keys [poll-id member-ids]}]
  (let [members (if (and legacy-recipient-snapshot? (seq member-ids))
                  (mapv #(q/retrieve-member db %) member-ids)
                  (->> (q/active-members execution-db)
                       (sort-by :member/member-id)
                       vec))]
    (if (seq members)
      (messages/build-new-poll-opened context (poll-queries/retrieve-poll db poll-id) members)
      ::skip)))

(defn insurance-debt [{:keys [db] :as context} {:keys [policy-id sender-id member-id]}]
  (let [{:keys [sender-name time-range member-data]}
        (insurance-queries/member-notification-data db policy-id sender-id member-id)]
    (when-not member-data
      (throw (ex-info "Payment notification snapshot has no recipient data"
                      {:email/permanent? true :email/reason :missing-payment-data})))
    (first (messages/build-insurance-debt-notification-emails context sender-name time-range [member-data]))))

(defn survey-reminder
  [{:keys [db execution-db legacy-recipient-snapshot?] :as context}
   {:keys [survey-id sender-id member-ids]}]
  (if (and legacy-recipient-snapshot? (seq member-ids))
    (let [{:keys [policy sender-name members email-data]}
          (insurance-queries/survey-notification-data db survey-id sender-id member-ids)]
      (messages/build-survey-notifications context sender-name policy members email-data))
    (let [current-survey (q/retrieve-survey execution-db survey-id)
          member-ids     (->> (:insurance.survey/responses current-survey)
                              (filter #(nil? (:insurance.survey.response/completed-at %)))
                              (map #(get-in % [:insurance.survey.response/member :member/member-id]))
                              distinct
                              vec)
          members        (mapv #(q/retrieve-member execution-db %) member-ids)
          {:keys [policy sender-name email-data]}
          (insurance-queries/survey-notification-data db survey-id sender-id [])]
      (if (seq members)
        (messages/build-survey-notifications context sender-name policy members email-data)
        ::skip))))

(defn member-invitation [{:keys [db] :as context} {:keys [member-id]}]
  (let [member (members-queries/invitation-state-by-member-id db member-id)]
    (when-not (and (= :member.invite.status/pending (:member/invite-status member))
                   (seq (:member/invite-code member)))
      (throw (ex-info "Invitation snapshot has no pending bearer"
                      {:email/permanent? true :email/reason :missing-invitation-data})))
    (messages/build-new-user-invite context member (:member/invite-code member))))

(defn job
  "Returns a durable mailer intent for a Nexus transaction's `:jobs` option.

  Nexus replaces the generated email id; the log consumer supplies `:source-t`.
  Neither preparing this intent nor committing it means the email was delivered."
  [state mailer arguments]
  ["send-email"
   {:version  2            :mailer mailer                           :arguments arguments
    :email-id :db/gen-uuid :locale (or (:current-locale state) :en)}
   {:queue "email-send-queue" :max-attempts 25}])

(def ^:private member-ids-schema
  [:and [:vector {:min 1 :max 500} :uuid]
   [:fn #(= (count %) (count (distinct %)))]])

(def ^:private reminder-ids-schema
  [:and [:vector {:min 1} :uuid]
   [:fn #(= (count %) (count (distinct %)))]])

(def registry
  {::gig-committed-update
   {:prepare   gig-committed-update
    :arguments [:map [:gig-id :uuid]
                [:member-ids {:optional true} member-ids-schema]]}
   ::gig-updated
   {:prepare   gig-updated
    :arguments [:map [:gig-id :uuid]
                [:member-ids {:optional true} member-ids-schema]
                [:edited-attrs [:vector {:min 1} :qualified-keyword]]]}
   ::gig-created
   {:prepare   gig-created
    :arguments [:map [:gig-id :uuid]
                [:member-ids {:optional true} member-ids-schema]]}
   ::gig-reminder
   {:prepare   gig-reminder
    :arguments [:map
                [:gig-id :uuid]
                [:reminder-ids {:optional true} reminder-ids-schema]
                [:member-ids {:optional true} member-ids-schema]]}
   ::rehearsal-leader
   {:prepare rehearsal-leader :arguments [:map [:gig-id :uuid] [:member-id :uuid]]}
   ::poll-opened
   {:prepare   poll-opened
    :arguments [:map [:poll-id :uuid]
                [:member-ids {:optional true} member-ids-schema]]}
   ::insurance-debt
   {:prepare insurance-debt :arguments [:map [:policy-id :uuid] [:sender-id :uuid] [:member-id :uuid]]}
   ::member-invitation
   {:prepare member-invitation :arguments [:map [:member-id :uuid]]}
   ::survey-reminder
   {:prepare   survey-reminder
    :arguments [:map [:survey-id :uuid] [:sender-id :uuid]
                [:member-ids {:optional true} member-ids-schema]]}})

(def ^:private envelope-schema
  [:map
   [:version [:enum 1 2]]
   [:mailer :qualified-keyword]
   [:arguments :map]
   [:source-t [:int {:min 1}]]
   [:email-id :uuid]
   [:locale [:enum :de :en]]])

(defn validate!
  "Validates an invocation and returns its registered preparation function.

  Invalid persisted input throws a non-sensitive, permanent email failure."
  [{:keys [mailer arguments] :as invocation}]
  (let [{:keys [prepare] argument-schema :arguments} (get registry mailer)]
    (when-not (and (s/valid? envelope-schema invocation)
                   prepare
                   (s/valid? argument-schema arguments))
      (throw (ex-info "Invalid email invocation"
                      {:email/permanent? true :email/reason :invalid-invocation})))
    prepare))

(defn source-t
  "Returns the t of a committed Peer transaction report.

  Rejects database filters and uncommitted transaction reports. The report must
  come directly from the successful transaction, not a reconstructed request map."
  [conn {:keys [db-after tx-data]}]
  (when-not (and (instance? datomic.Database db-after)
                 (not (d/is-filtered db-after))
                 (not (.isHistory ^datomic.Database db-after))
                 (nil? (d/as-of-t db-after))
                 (nil? (d/since-t db-after))
                 (seq tx-data))
    (throw (ex-info "Email requires a committed transaction report" {:email/permanent? true})))
  (let [t         (d/basis-t db-after)
        committed (d/db conn)
        logged    (when (<= t (d/basis-t committed))
                    (first (d/tx-range (d/log conn) t (inc t))))]
    (when-not (and (= (.id ^datomic.Database db-after) (.id ^datomic.Database committed))
                   (= t (:t logged))
                   (= (set tx-data) (set (:data logged))))
      (throw (ex-info "Email source transaction is not committed" {:email/permanent? true})))
    t))

(defn prepare!
  "Reconstructs historical content and current recipients, then prepares a message.

  Version 1 preserves the stored recipient snapshot for safe retries of jobs that
  may have reached the provider before this deployment. Version 2 selects automatic
  recipients when it first runs.

  An explicitly conditional mailer may return `::skip` for a no-op notification.

  Snapshot synchronization is bounded to one second per attempt. Storage failures
  remain retryable; invalid inputs and invalid prepared messages are permanent."
  [{:keys [datomic i18n-langs env]}
   {:keys [version source-t email-id locale arguments] :as invocation}]
  (let [prepare  (validate! invocation)
        conn     (:conn datomic)
        observed (deref (d/sync conn source-t) 1000 nil)]
    (when-not (and observed (>= (d/basis-t observed) source-t))
      (throw (ex-info "Email source snapshot is not yet available" {})))
    (let [context {:db                         (d/as-of observed source-t)
                   :db-before                  (d/as-of observed (dec source-t))
                   :execution-db               (d/db conn)
                   :legacy-recipient-snapshot? (= 1 version)
                   :env                        env
                   :tr                         (i18n/tr-with i18n-langs [locale])
                   :email-id                   email-id}
          message (prepare context arguments)]
      (when-not (or (= ::skip message) (s/valid? domain/QueuedEmailMessage message))
        (throw (ex-info "Invalid prepared email"
                        {:email/permanent? true :email/reason :invalid-prepared-email})))
      (if (= ::skip message) message (assoc message :email/email-id email-id)))))
