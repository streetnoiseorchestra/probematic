(ns app.insurance.policy.changes.actions
  (:require
   [app.form :as form]
   [app.insurance.domain :as domain]
   [app.insurance.exporters :as exporters]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]))

(def form-key :insurance-policy-changes)

(defn default-form
  [tr {:keys [policy-id policy-number recipient-email recipient-name recipient-title sender-name today]}]
  {:policy-id                   (str policy-id)
   :recipient                   (format "%s <%s>" recipient-name recipient-email)
   :subject                     (tr [:insurance/changes-email-subject]
                                    {:policy-number (or policy-number "")})
   :body                        (tr [:insurance/changes-email-body]
                                    {:recipient-title (or recipient-title "")
                                     :recipient-name  (or recipient-name "")
                                     :sender-name     (or sender-name "")})
   :attachment-filename-new     (format "AnlageNeueInstrumente-%s.xls" today)
   :attachment-filename-changes (format "AnlageÄnderungen-%s.xls" today)
   :preview-type                ""})

(defn- normalize-form
  [signals]
  (let [params (or (form-key signals) signals)]
    {:policy-id                   (some-> (:policy-id params) form/optional-text)
     :recipient                   (or (form/trim-value (:recipient params)) "")
     :subject                     (or (form/trim-value (:subject params)) "")
     :body                        (or (form/trim-value (:body params)) "")
     :attachment-filename-new     (or (form/trim-value (:attachment-filename-new params)) "")
     :attachment-filename-changes (or (form/trim-value (:attachment-filename-changes params)) "")
     :preview-type                (some-> (:preview-type params) form/optional-text)}))

(defn- policy-context
  [db policy-id-value]
  (try
    (let [policy-id (some-> policy-id-value util/ensure-uuid!)
          policy    (when policy-id (q/retrieve-policy db policy-id))]
      (when (:insurance.policy/policy-id policy)
        {:policy-id policy-id
         :policy    policy}))
    (catch Exception _
      nil)))

(defn- delivery-errors
  [tr policy params]
  (let [required-fields [[:recipient :insurance/recipient]
                         [:subject :insurance/subject]
                         [:body :insurance/message]
                         [:attachment-filename-new :insurance/attachment-filename]
                         [:attachment-filename-changes :insurance/attachment-filename]]
        errors          (reduce (fn [errors [field label]]
                                  (cond-> errors
                                    (str/blank? (field params))
                                    (assoc field
                                           {:error (tr [:error/is-required]
                                                       {:field (tr [label])})})))
                                {}
                                required-fields)
        exporter-error  (some->> policy
                                 exporters/configuration-guidance-key
                                 vector
                                 tr
                                 (hash-map :error))
        errors          (cond-> errors
                          (nil? policy)
                          (assoc :_top {:error (tr [:error/not-found-title])})

                          exporter-error
                          (assoc :_top exporter-error))]
    (cond-> errors
      (and (seq errors) (nil? (:_top errors)))
      (assoc :_top {:error (tr [:error/form-has-errors])}))))

(defn- failure-effects
  [params errors]
  [support/clear-loading
   [:app.datastar/assoc-state [form-key] (assoc params :_error errors)]])

(defn- confirmation-transaction
  [current-member-id policy]
  [:db/transact
   (support/with-audit (domain/txs-confirm-and-activate-policy policy)
     current-member-id)
   {}])

(defn confirm-changes-action
  [{:keys [current-member-id db tr]} signals]
  (let [params (normalize-form signals)
        context (policy-context db (:policy-id params))]
    (if-not context
      (failure-effects params {:_top {:error (tr [:error/not-found-title])}})
      [(confirmation-transaction current-member-id (:policy context))
       [:app.datastar/respond-sse
        [[:app.datastar.sse/redirect
          (urls/link-policy (:policy-id context))]]]])))

(defn confirm-sent-action
  [{:keys [current-member-id db tr]} signals]
  (let [params  (normalize-form signals)
        context (policy-context db (:policy-id params))]
    (if-not context
      (failure-effects params {:_top {:error (tr [:error/not-found-title])}})
      [(confirmation-transaction current-member-id (:policy context))])))

(defn send-and-confirm-changes-action
  [{:keys [db tr]} signals]
  (let [params  (normalize-form signals)
        context (policy-context db (:policy-id params))
        errors  (delivery-errors tr (:policy context) params)]
    (if (seq errors)
      (failure-effects params errors)
      [[:app.insurance/send-policy-changes
        (-> params
            (dissoc :preview-type)
            (assoc :policy-id  (:policy-id context)
                   :on-success [[::confirm-sent
                                 {form-key {:policy-id (str (:policy-id context))}}]
                                [:app.datastar/respond-sse
                                 [[:app.datastar.sse/redirect
                                   (urls/link-policy (:policy-id context))]]]]))]])))

(defn preview-attachment-action
  [{:keys [db tr]} signals]
  (let [params       (normalize-form signals)
        context      (policy-context db (:policy-id params))
        preview-type (:preview-type params)
        filename     (case preview-type
                       "new" (:attachment-filename-new params)
                       "changes" (:attachment-filename-changes params)
                       nil)
        guidance-key (some-> context
                             :policy
                             exporters/configuration-guidance-key)]
    (if guidance-key
      (failure-effects params {:_top {:error (tr [guidance-key])}})
      (if (and context (contains? #{"new" "changes"} preview-type) (not (str/blank? filename)))
        [[:app.datastar/respond-sse
          [[:app.datastar.sse/redirect
            (urls/link-policy-changes-download-excel
             (:policy-id context)
             preview-type
             filename)]]]]
        (failure-effects params {:_top {:error (tr [:error/form-has-errors])}})))))

(def actions
  {::confirm-changes    #'confirm-changes-action
   ::confirm-sent       #'confirm-sent-action
   ::send-and-confirm   #'send-and-confirm-changes-action
   ::preview-attachment #'preview-attachment-action})
