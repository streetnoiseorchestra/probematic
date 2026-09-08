(ns app.email.mailers
  "Prepares named email invocations without delivering them."
  (:require
   [app.email.domain :as domain]
   [app.email.templates :as tmpl]
   [app.i18n :as i18n]
   [app.queries :as q]
   [app.schemas :as s]
   [datomic.api :as d]))

(defn build-gig-updated-email
  "Builds the shared-body batch with the supplied `:email-id`."
  [{:keys [tr email-id] :as context} gig members edited-attrs]
  (let [message {:subject (tr [:email-subject/gig-updated] {:gig-title (:gig/title gig)})
                 :html (tmpl/gig-updated-email-html context gig edited-attrs)
                 :text (tmpl/gig-updated-email-plain context gig edited-attrs)}]
    {:email/sender :lettermint
     :email/batch? true
     :email/email-id email-id
     :email/messages (mapv #(assoc message :to [(:member/email %)]) members)}))

(defn gig-updated
  "Prepares a gig-update batch from the pinned database in `context`."
  [{:keys [db] :as context} {:keys [gig-id member-ids edited-attrs]}]
  (let [gig (q/retrieve-gig db (parse-uuid gig-id))
        members-by-id (into {} (map (juxt (comp str :member/member-id) identity)) (q/active-members db))
        members (mapv members-by-id member-ids)]
    (when-not (every? some? ((juxt :gig/gig-id :gig/title :gig/date :gig/status :gig/gig-type) gig))
      (throw (ex-info "Gig snapshot is missing required data"
                      {:email/permanent? true :email/reason :missing-gig-data})))
    (build-gig-updated-email context gig members (mapv keyword edited-attrs))))

(def ^:private uuid-string
  [:and :string [:fn #(some? (parse-uuid %))]])

(def registry
  {"gig-updated"
   {:prepare gig-updated
    :arguments [:map {:closed true}
                [:gig-id uuid-string]
                [:member-ids [:and [:vector {:min 1 :max 500} uuid-string]
                              [:fn #(= (count %) (count (distinct %)))]]]
                [:edited-attrs [:vector {:min 1} [:re #"[a-zA-Z][a-zA-Z0-9_.-]*/[a-zA-Z][a-zA-Z0-9_?!.-]*"]]]]}})

(def ^:private envelope-schema
  [:map {:closed true}
   [:version [:= 1]]
   [:mailer :string]
   [:arguments :map]
   [:source-t [:int {:min 1}]]
   [:email-id uuid-string]
   [:locale [:enum "de" "en"]]])

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
  (let [t (d/basis-t db-after)
        committed (d/db conn)
        logged (when (<= t (d/basis-t committed))
                 (first (d/tx-range (d/log conn) t (inc t))))]
    (when-not (and (= (.id ^datomic.Database db-after) (.id ^datomic.Database committed))
                   (= t (:t logged))
                   (= (set tx-data) (set (:data logged))))
      (throw (ex-info "Email source transaction is not committed" {:email/permanent? true})))
    t))

(defn prepare!
  "Reconstructs an invocation from its source snapshot and prepares its message.

  Snapshot synchronization is bounded to one second per attempt. Storage failures
  remain retryable; invalid inputs and invalid prepared messages are permanent."
  [{:keys [datomic i18n-langs env]} {:keys [source-t email-id locale arguments] :as invocation}]
  (let [prepare (validate! invocation)
        conn (:conn datomic)
        current (d/db conn)
        observed (if (>= (d/basis-t current) source-t)
                   current
                   (deref (d/sync conn source-t) 1000 nil))]
    (when-not (and observed (>= (d/basis-t observed) source-t))
      (throw (ex-info "Email source snapshot is not yet available" {})))
    (let [context {:db (d/as-of observed source-t)
                   :env env
                   :tr (i18n/tr-with i18n-langs [(keyword locale)])
                   :email-id (parse-uuid email-id)}
          message (prepare context arguments)]
      (when-not (s/valid? domain/QueuedEmailMessage message)
        (throw (ex-info "Invalid prepared email"
                        {:email/permanent? true :email/reason :invalid-prepared-email})))
      message)))
