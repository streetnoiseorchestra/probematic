(ns app.insurance.domain
  (:require
   [app.schemas :as s]
   [app.urls :as urls]
   [clojure.data :as clojure.data]
   [clojure.set :as set]
   [com.yetanalytics.squuid :as sq]
   [medley.core :as m]
   [tick.core :as t]))

(def instrument-coverage-statuses [:instrument.coverage.status/needs-review
                                   :instrument.coverage.status/reviewed
                                   :instrument.coverage.status/coverage-active])

(def instrument-coverage-changes [:instrument.coverage.change/new
                                  :instrument.coverage.change/removed
                                  :instrument.coverage.change/changed
                                  :instrument.coverage.change/none])

(def policy-statuses [:insurance.policy.status/active
                      :insurance.policy.status/sent
                      :insurance.policy.status/draft])

(defn sum-by [ms k]
  ;; (tap> {:ms ms :k k})
  (when (seq ms)
    (->> ms
         (map k)
         (reduce + 0))))

(defn reconcile-coverage-types [eid existing-type-ids new-type-ids]
  (let [[added removed] (clojure.data/diff (set existing-type-ids)  (set new-type-ids))
        ;; _ (tap> {:added added :removed removed})
        add-tx (map #(-> [:db/add eid :instrument.coverage/types [:insurance.coverage.type/type-id  %]]) (filter some? added))
        remove-tx (map #(-> [:db/retract eid :instrument.coverage/types [:insurance.coverage.type/type-id  %]]) (filter some? removed))]
    (concat add-tx remove-tx)))

(defn make-category-factor-lookup
  "Given a policy, create a map where the keys are the category ids and the values are the category factors"
  [policy]
  (->> (-> policy :insurance.policy/category-factors)
       (map (fn [factor]
              {:instrument.category/category-id (-> factor :insurance.category.factor/category :instrument.category/category-id)
               :insurance.category.factor/factor (:insurance.category.factor/factor factor)}))
       (reduce (fn [r m]
                 (assoc r (:instrument.category/category-id m) (:insurance.category.factor/factor m))) {})))

(defn update-coverage-price [category-factor base-premium-factor coverage]
  (assert category-factor)
  ;; (tap> {:coverage coverage :base-factor base-premium-factor :category category-factor})
  (assoc coverage :instrument.coverage/types
         (map (fn [coverage-type]
                ;; (tap> {:type coverage-type :cov coverage :cat-f category-factor :base base-premium-factor})
                (assoc coverage-type :insurance.coverage.type/cost
                       (* (:instrument.coverage/value coverage)
                          (or (:instrument.coverage/item-count coverage) 1)
                          category-factor
                          base-premium-factor
                          (:insurance.coverage.type/premium-factor coverage-type)))) (:instrument.coverage/types coverage))))

(defn update-total-coverage-price
  "Given a policy and a specific instrument coverage, calculate the total price for the instrument"
  [policy {:instrument.coverage/keys [value instrument] :as coverage}]
  ;;  value * category factor * premium factor * coverage factor
  (let [category-id (-> instrument :instrument/category :instrument.category/category-id)
        _  (assert category-id)
        category-factor (get  (make-category-factor-lookup policy) category-id)
        ;; _ (tap> {:lookup (make-category-factor-lookup policy) :cat-id category-id})
        _  (assert category-factor)
        ;; _ (tap> {:cat-id category-id :cat-fact category-factor :lookup (make-category-factor-lookup policy) :policy policy})
        premium-factor (-> policy :insurance.policy/premium-factor)
        coverage  (update-coverage-price category-factor premium-factor coverage)
        ;; _ (tap> {:cov coverage})
        total-cost (sum-by (:instrument.coverage/types coverage) :insurance.coverage.type/cost)]

    (assoc coverage :instrument.coverage/cost total-cost)))

(defn get-coverage-type-from-coverage [instrument-coverage type-id]
  (m/find-first #(= type-id (:insurance.coverage.type/type-id %))
                (:instrument.coverage/types instrument-coverage)))

(defn enrich-coverages [policy coverage-types coverages]
  (mapv (fn [coverage]
          (let [coverage (update-total-coverage-price policy coverage)]
            (assoc coverage :types
                   (mapv (fn [{:insurance.coverage.type/keys [type-id]}]
                           (when-let [coverage-type (get-coverage-type-from-coverage coverage type-id)]
                             coverage-type))
                         coverage-types))))
        coverages))

(def SurveyReportEntity
  (s/schema
   [:map {:name :app.entity/insurance.survey.report}
    [:insurance.survey.report/report-id :uuid]
    [:insurance.survey.report/completed-at {:optional true} ::s/inst]
    [:insurance.survey.report/coverage ::s/datomic-ref]]))

(def SurveyResponseEntity
  (s/schema
   [:map {:name :app.entity/insurance.survey.response}
    [:insurance.survey.response/response-id :uuid]
    [:insurance.survey.response/member ::s/datomic-ref]
    [:insurance.survey.response/completed-at {:optional true} ::s/inst]
    [:insurance.survey.response/coverage-reports {:optional true} [:sequential s/DatomicRefOrTempid]]]))

(def SurveyEntity
  (s/schema
   [:map {:name :app.entity/insurance.survey}
    [:insurance.survey/survey-id :uuid]
    [:insurance.survey/survey-name ::s/non-blank-string]
    [:insurance.survey/policy ::s/datomic-ref]
    [:insurance.survey/created-at ::s/inst]
    [:insurance.survey/closes-at ::s/inst]
    [:insurance.survey/closed-at {:optional true} ::s/inst]
    [:insurance.survey/responses {:optional true} [:sequential s/DatomicRefOrTempid]]]))

(defn tx-new-survey-report [tempid coverage-id]
  (assert tempid "Tempid must be non-nil")
  (assert coverage-id "Coverage ID must be non-nil")
  {:db/id tempid
   :insurance.survey.report/report-id (sq/generate-squuid)
   :insurance.survey.report/coverage [:instrument.coverage/coverage-id coverage-id]})

(defn tx-new-survey-response [tempid member-id coverage-report-tempids]
  (assert tempid "Tempid must be non-nil")
  (assert member-id "Member ID must be non-nil")
  (when (seq coverage-report-tempids)
    (assert (every? some? coverage-report-tempids) "report empids cannot be nil"))
  (let [tx {:db/id tempid
            :insurance.survey.response/response-id (sq/generate-squuid)
            :insurance.survey.response/member [:member/member-id member-id]}]
    (if (seq coverage-report-tempids)
      (assoc tx :insurance.survey.response/coverage-reports coverage-report-tempids)
      tx)))

(defn closes-at-inst [closes-at]
  (t/inst (t/in closes-at (t/zone "Europe/Vienna"))))

(defn survey->db
  ([survey]
   (survey->db SurveyEntity survey))
  ([schema survey]
   (let [survey (update survey :insurance.survey/closes-at closes-at-inst)]
     (when-not (s/valid? schema survey)
       (throw
        (ex-info "Survey not valid" {:survey survey
                                     :schema schema
                                     :error (s/explain schema survey)
                                     :human (s/explain-human schema survey)})))
     (s/encode-datomic schema survey))))

(defn txs-update-survey [survey-id name closes-at]
  (assert survey-id "Survey ID must be non-nil")
  (assert name "Name must be non-nil")
  (assert closes-at "Closes at must be non-nil")
  [[:db/add [:insurance.survey/survey-id survey-id] :insurance.survey/survey-name name]
   [:db/add [:insurance.survey/survey-id survey-id] :insurance.survey/closes-at (closes-at-inst closes-at)]])

(defn tx-new-survey [tempid name policy-id closes-at response-tempids]
  (assert tempid "Tempid must be non-nil")
  (assert name "Name must be non-nil")
  (assert policy-id "Policy ID must be non-nil")
  (assert closes-at "Closes at must be non-nil")
  (assert (seq response-tempids) "Response tempids must be non-empty")
  (survey->db
   {:db/id tempid
    :insurance.survey/survey-id (sq/generate-squuid)
    :insurance.survey/survey-name name
    :insurance.survey/policy [:insurance.policy/policy-id policy-id]
    :insurance.survey/created-at (t/inst)
    :insurance.survey/closes-at closes-at
    :insurance.survey/responses response-tempids}))

(defn db->survey-report [m]
  (-> (s/decode-datomic SurveyReportEntity m)
      (set/rename-keys {:insurance.survey.response/_coverage-reports :response})
      (m/update-existing :insurance.survey.report/coverage (fn [coverage]
                                                             (let [policy (:insurance.policy/_covered-instruments coverage)
                                                                   coverage-types (:insurance.policy/coverage-types policy)]
                                                               ;; (tap> {:policy policy :coverage-types coverage-types :coverage coverage})
                                                               (assert policy)
                                                               (assert coverage-types)
                                                               (first (enrich-coverages policy coverage-types [coverage])))))
      (m/update-existing :insurance.survey.report/completed-at t/date-time)))

(defn db->survey-response [m]
  (-> (s/decode-datomic SurveyResponseEntity m)
      (m/update-existing :insurance.survey.response/completed-at t/date-time)
      (m/update-existing :insurance.survey.response/coverage-reports #(->> %
                                                                           (map db->survey-report)
                                                                           (sort-by (fn [r] (-> r :coverage :instrument.coverage/value)))
                                                                           (reverse)))
      (clojure.set/rename-keys {:insurance.survey/_responses :survey})))

(defn db->survey [m]
  (-> (s/decode-datomic SurveyEntity m)
      (m/update-existing :insurance.survey/created-at t/date-time)
      (m/update-existing :insurance.survey/closes-at t/date-time)
      (m/update-existing :insurance.survey/closed-at t/date-time)
      (m/update-existing :insurance.survey/responses #(->> %
                                                           (map db->survey-response)
                                                           (sort-by (fn [r] (-> r :member :member/name)))))))

(defn summarize-member-reports [coverage-reports]
  (let [result (reduce (fn [acc {:insurance.survey.report/keys [coverage report-id completed-at]}]
                         (if (some? completed-at)
                           (update acc :completed inc)
                           (update acc :open inc)))
                       {:open 0 :completed 0}
                       coverage-reports)]
    (assoc result :finished? (= 0 (:open result)))))

(defn coverage-ref [{:instrument.coverage/keys [coverage-id]}]
  [:instrument.coverage/coverage-id coverage-id])

(defn report-ref [{:insurance.survey.report/keys [report-id]}]
  [:insurance.survey.report/report-id report-id])

(defn response-ref [{:insurance.survey.response/keys [response-id]}]
  [:insurance.survey.response/response-id response-id])

(defn has-upstream-change? [old-coverage owner-changed? category-changed? new-value new-item-count new-private? coverage-changes?]
  (or
   owner-changed?
   category-changed?
   (not (== (:instrument.coverage/value old-coverage) new-value))
   (not (== (:instrument.coverage/item-count old-coverage 1) new-item-count))
   (not (= (:instrument.coverage/private? old-coverage) new-private?))
   coverage-changes?))

(defn txs-confirm-keep-insured [{:insurance.survey.report/keys [coverage]}]
  ;; member wants to keep insurance, so if it was marked for removal, unremove it
  (when (= :instrument.coverage.change/removed (:instrument.coverage/change coverage))
    [[:db/add (coverage-ref coverage) :instrument.coverage/change :instrument.coverage.change/changed]]))

(defn txs-band-instrument-coverage-types [policy coverage]
  (let [cov-types (:insurance.policy/coverage-types policy)
        before-type-ids (mapv :insurance.coverage.type/type-id (:instrument.coverage/types coverage))
        after-type-ids  (mapv :insurance.coverage.type/type-id cov-types)]
    (reconcile-coverage-types (coverage-ref coverage)
                              after-type-ids
                              before-type-ids)))

(defn txs-private-instrument-coverage-types [coverage selected-coverage-type-ids]
  (let [before-type-ids (mapv :insurance.coverage.type/type-id (:instrument.coverage/types coverage))]
    (reconcile-coverage-types (coverage-ref coverage)
                              selected-coverage-type-ids
                              before-type-ids)))

(defn txs-confirm-band [{:insurance.survey.report/keys [coverage]}]
  (let [policy (:insurance.policy/_covered-instruments coverage)]
    (concat
     (txs-band-instrument-coverage-types policy coverage)
     [[:db/add (coverage-ref coverage) :instrument.coverage/private? false]])))

(defn txs-confirm-not-band [{:insurance.survey.report/keys [coverage]}]
  [[:db/add (coverage-ref coverage) :instrument.coverage/private? true]])

(defn txs-remove-coverage [{:insurance.survey.report/keys [coverage]}]
  [[:db/add (coverage-ref coverage) :instrument.coverage/change :instrument.coverage.change/removed]])

(defn txs-for-decision [active-report decision]
  (condp = decision
    :confirm-keep-insured (txs-confirm-keep-insured active-report)
    :confirm-data-ok nil                ;; nothing to change for this one (yet?)
    :confirm-band (txs-confirm-band active-report)
    :confirm-not-band (txs-confirm-not-band active-report)
    :remove-coverage (txs-remove-coverage active-report)
    nil))

(defn txs-complete-survey-report [report]
  [[:db/add (report-ref report) :insurance.survey.report/completed-at (t/inst)]])

(defn txs-uncomplete-survey-report [report]
  (when-let [old-value (:insurance.survey.report/completed-at report)]
    [[:db/retract (report-ref report) :insurance.survey.report/completed-at (t/inst old-value)]]))

(defn txs-maybe-survey-response-complete [{:insurance.survey.report/keys [report-id]  :as report} {:insurance.survey.response/keys [response-id coverage-reports] :as response}]
  (assert response "Response must be non-nil")
  (let [open-reports (filter (comp nil? :insurance.survey.report/completed-at) coverage-reports)

        maybe-first-report-id    (:insurance.survey.report/report-id (first open-reports))]
    ;; (tap> {:r response :report report})
    (when (and (= 1 (count open-reports))
               (= maybe-first-report-id report-id))
      [[:db/add (response-ref response) :insurance.survey.response/completed-at (t/inst)]])))

(defn txs-toggle-response-completion [{:insurance.survey.response/keys [completed-at coverage-reports] :as r}]
  (if completed-at
    (concat
     [[:db/retract (response-ref r) :insurance.survey.response/completed-at (t/inst completed-at)]]
     (mapcat txs-uncomplete-survey-report coverage-reports))
    [[:db/add (response-ref r) :insurance.survey.response/completed-at (t/inst)]]))

(defn txs-confirm-and-activate-policy-coverages [{:instrument.coverage/keys [coverage-id status change] :as coverage}]
  (if (= change :instrument.coverage.change/removed)
    [[:db/retractEntity (coverage-ref coverage)]]
    [[:db/add [:instrument.coverage/coverage-id coverage-id] :instrument.coverage/status :instrument.coverage.status/coverage-active]
     [:db/add [:instrument.coverage/coverage-id coverage-id] :instrument.coverage/change :instrument.coverage.change/none]]))

(defn txs-confirm-and-activate-policy [{:as policy :insurance.policy/keys [policy-id covered-instruments]}]
  (concat
   [{:insurance.policy/policy-id policy-id
     :insurance.policy/status    :insurance.policy.status/active}]
   (mapcat txs-confirm-and-activate-policy-coverages covered-instruments)))

(defn public-instrument-url [req instrument-id]
  (urls/absolute-link-instrument-public (-> req :system :env) instrument-id))

(defn txs-instrument-share-link [req ref instrument-id]
  [[:db/add ref :instrument/images-share-url (public-instrument-url req instrument-id)]])

(defn txs-add-instrument-image [req instrument-id image-ref]
  (let [ref [:instrument/instrument-id instrument-id]]
    (concat
     [[:db/add ref :instrument/images image-ref]]
     (txs-instrument-share-link req ref instrument-id))))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (require '[app.datomic.shim :as datomic])
    (require '[app.queries :as q])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn)))

  (def me (q/retrieve-member db #uuid "01860c2a-2929-8727-af1a-5545941b1111"))

  (q/open-survey-for-member db me)

  (def report (q/retrieve-survey-report db #uuid  "018f76e0-7959-8168-809b-418f21041371"))

  (let [coverage (:insurance.survey.report/coverage report)
        policy (:insurance.policy/_covered-instruments coverage)
        cov-types (:insurance.policy/coverage-types policy)
        before-type-ids (mapv :insurance.coverage.type/type-id (:instrument.coverage/types coverage))
        after-type-ids  (mapv :insurance.coverage.type/type-id cov-types)]
    (reconcile-coverage-types  (coverage-ref coverage)
                               after-type-ids
                               before-type-ids))

  (let [coverage (:insurance.survey.report/coverage report)
        policy (:insurance.policy/_covered-instruments coverage)
        cov-types (:insurance.policy/coverage-types policy)
        before-type-ids (mapv :insurance.coverage.type/type-id (:instrument.coverage/types coverage))
        after-type-ids  (mapv :insurance.coverage.type/type-id cov-types)
        after-type-ids before-type-ids]
    (reconcile-coverage-types  (coverage-ref coverage)
                               after-type-ids
                               before-type-ids))

  (txs-confirm-band report)

  ;;
  )
