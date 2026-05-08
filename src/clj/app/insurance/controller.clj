(ns app.insurance.controller
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.datomic :as d]
   [app.email :as email]
   [app.errors :as errors]
   [app.file-utils :as fs]
   [app.filestore.controller :as filestore]
   [app.insurance.domain :as domain]
   [app.insurance.excel :as excel]
   [app.ledger.domain :as ledger.domain]
   [app.queries :as q]
   [app.schemas :as s]
   [app.urls :as urls]
   [app.util :as util]
   [app.util.http :as util.http]
   [app.util.zip :as zip]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]
   [datomic.client.api :as datomic]
   [malli.util :as mu]
   [medley.core :as m]
   [tick.core :as t])
  (:import
   [java.io ByteArrayOutputStream]
   [java.net URLEncoder]))

(def str->instrument-coverage-status (zipmap (map name domain/instrument-coverage-statuses) domain/instrument-coverage-statuses))
(def str->policy-status (zipmap (map name domain/policy-statuses) domain/policy-statuses))
(def str->instrument-coverage-change (zipmap (map name domain/instrument-coverage-changes) domain/instrument-coverage-changes))

(defn policy-editable? [{:insurance.policy/keys [status] :as p}]
  (= status :insurance.policy.status/draft))

(defn create-policy-txs [{:keys [name effective-at effective-until
                                 base-factor overnight-factor proberaum-factor
                                 category-factors]}]
  (let [category-factor-txs (mapv (fn [{:keys [category-id factor]}]
                                    {:db/id category-id
                                     :insurance.category.factor/category-factor-id (sq/generate-squuid)
                                     :insurance.category.factor/category [:instrument.category/category-id category-id]
                                     :insurance.category.factor/factor (bigdec factor)})
                                  category-factors)
        category-factor-tx-ids (mapv :db/id category-factor-txs)
        policy-txs [{:db/id "ct_basic"
                     :insurance.coverage.type/type-id (sq/generate-squuid)
                     :insurance.coverage.type/name "Basic Coverage"
                     :insurance.coverage.type/description ""
                     :insurance.coverage.type/premium-factor (bigdec 1.0)}

                    {:db/id "ct_overnight"
                     :insurance.coverage.type/type-id (sq/generate-squuid)
                     :insurance.coverage.type/name "Auto/Overnight"
                     :insurance.coverage.type/description ""
                     :insurance.coverage.type/premium-factor (bigdec overnight-factor)}
                    {:db/id "ct_proberaum"
                     :insurance.coverage.type/type-id (sq/generate-squuid)
                     :insurance.coverage.type/name "Proberaum"
                     :insurance.coverage.type/description ""
                     :insurance.coverage.type/premium-factor (bigdec proberaum-factor)}

                    {:insurance.policy/policy-id (sq/generate-squuid)
                     :insurance.policy/currency :currency/EUR
                     :insurance.policy/name name
                     :insurance.policy/effective-at effective-at
                     :insurance.policy/effective-until effective-until
                     :insurance.policy/covered-instruments []
                     :insurance.policy/coverage-types ["ct_basic" "ct_overnight" "ct_proberaum"]
                     :insurance.policy/premium-factor base-factor
                     :insurance.policy/category-factors category-factor-tx-ids}]]
    (concat category-factor-txs policy-txs)))

(defn duplicate-policy-tx [{:insurance.policy/keys [name effective-at effective-until premium-factor category-factors coverage-types covered-instruments currency] :as old-policy}]
  (let [cat-factor-txs (map-indexed (fn [idx {:insurance.category.factor/keys [category factor]}]
                                      {:db/id (str "cat_fact_" idx)
                                       :insurance.category.factor/category-factor-id (sq/generate-squuid)
                                       :insurance.category.factor/category [:instrument.category/category-id (:instrument.category/category-id category)]
                                       :insurance.category.factor/factor (bigdec factor)})
                                    category-factors)
        cat-factor-ids (map :db/id cat-factor-txs)
        coverage-type-txs (map-indexed (fn [idx {:insurance.coverage.type/keys [type-id name description premium-factor]}]
                                         {:db/id (str "cat_type_" idx)
                                          :insurance.coverage.type/name name
                                          :insurance.coverage.type/type-id (sq/generate-squuid)
                                          :old-type-id type-id
                                          :insurance.coverage.type/description description
                                          :insurance.coverage.type/premium-factor premium-factor})
                                       coverage-types)

        coverage-type-ids (map :db/id coverage-type-txs)
        coverage-type-old-to-new (reduce (fn [m tx]
                                           (assoc m (:old-type-id tx) (:db/id tx))) {} coverage-type-txs)
        coverage-type-txs (map #(dissoc % :old-type-id) coverage-type-txs)

        covered-instruments-txs (map-indexed (fn [idx {:instrument.coverage/keys [status instrument types private? value change]}]
                                               {:db/id (str "coverage_" idx)
                                                :instrument.coverage/coverage-id (sq/generate-squuid)
                                                :instrument.coverage/instrument (d/ref instrument :instrument/instrument-id)
                                                :instrument.coverage/types (mapv (fn [{:insurance.coverage.type/keys [type-id]}]
                                                                                   (get coverage-type-old-to-new type-id)) types)
                                                :instrument.coverage/private? private?
                                                :instrument.coverage/status status
                                                :instrument.coverage/change change
                                                :instrument.coverage/value value})
                                             covered-instruments)

        covered-instruments-ids (map :db/id covered-instruments-txs)]

    (map util/remove-nils
         (concat
          cat-factor-txs
          coverage-type-txs
          covered-instruments-txs
          [{:insurance.policy/policy-id (sq/generate-squuid)
            :insurance.policy/name (str "Copy of " name)
            :insurance.policy/effective-at (t/inst effective-at)
            :insurance.policy/effective-until (t/inst effective-until)
            :insurance.policy/currency currency
            :insurance.policy/status :insurance.policy.status/draft
            :insurance.policy/premium-factor premium-factor
            :insurance.policy/coverage-types coverage-type-ids
            :insurance.policy/category-factors  cat-factor-ids
            :insurance.policy/covered-instruments covered-instruments-ids}]))))

(defn transact-policy! [conn tx-data]
  (let [result (datomic/transact conn {:tx-data tx-data})]
    {:policy
     (d/find-by (:db-after result)
                :insurance.policy/policy-id
                (:insurance.policy/policy-id (last tx-data))
                q/policy-pattern)}))

(defn datestr->inst
  "Convert a string like 2022-01-01 to an instant at midnight"
  [dstr]
  (-> dstr (t/date) (t/at (t/midnight)) (t/inst)))

(defn duplicate-policy! [{:keys [db datomic-conn] :as req}]
  (let [params (util.http/unwrap-params req)
        old-pol-id (util.http/ensure-uuid (:policy-id params))
        old-policy (q/retrieve-policy db old-pol-id)
        new-policy-tx (duplicate-policy-tx old-policy)]
    (transact-policy! datomic-conn new-policy-tx)))

(defn delete-policy! [{:keys [db datomic-conn] :as req}]
  (try
    (let [params (util.http/unwrap-params req)
          policy-id (util.http/ensure-uuid (:policy-id params))
          policy-ref [:insurance.policy/policy-id policy-id]]
      (when-not (q/policy-has-open-surveys? db policy-id)
        (datomic/transact datomic-conn {:tx-data [[:db/retractEntity policy-ref]]})
        true))
    (catch Throwable t
      (if
       (re-find #".*Cannot resolve key.*" (ex-message t))
        true
        (throw t)))))

(defn create-policy! [{:keys [db datomic-conn] :as req}]
  (let [params (util.http/unwrap-params req)
        effective-at (-> params :effective-at datestr->inst)
        effective-until (-> params :effective-until datestr->inst)
        base-factor (-> params :base-factor bigdec)
        tx-data [{:insurance.policy/policy-id (sq/generate-squuid)
                  :insurance.policy/currency :currency/EUR
                  :insurance.policy/name (:name params)
                  :insurance.policy/effective-at effective-at
                  :insurance.policy/effective-until effective-until
                  :insurance.policy/premium-factor base-factor}]]
    (transact-policy! datomic-conn tx-data)))

(defn update-policy! [{:keys [db datomic-conn] :as req} policy-id]
  (let [params (util.http/unwrap-params req)
        effective-at (-> params :effective-at datestr->inst)
        effective-until (-> params :effective-until datestr->inst)
        base-factor (-> params :base-factor bigdec)
        tx-data [{:insurance.policy/policy-id  policy-id
                  :insurance.policy/currency :currency/EUR
                  :insurance.policy/name (:name params)
                  :insurance.policy/status (or (str->policy-status (get params :status)) :insurance.policy.status/draft)
                  :insurance.policy/effective-at effective-at
                  :insurance.policy/effective-until effective-until
                  :insurance.policy/premium-factor base-factor}]]
    (transact-policy! datomic-conn tx-data)))

(defn has-category-factor? [policy category-factor-id]
  (m/find-first #(= category-factor-id (:insurance.category.factor/category-factor-id %))
                (:insurance.policy/category-factors policy)))

(defn create-category-factor! [{:keys [datomic-conn] :as req} policy-id]
  (let [policy-id (util.http/ensure-uuid policy-id)
        params (util.http/unwrap-params req)
        tx-data [{:insurance.category.factor/category-factor-id (sq/generate-squuid)
                  :db/id "cat_fact"
                  :insurance.category.factor/factor (bigdec (:premium-factor params))
                  :insurance.category.factor/category [:instrument.category/category-id (util.http/ensure-uuid (:category-id params))]}
                 [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/category-factors "cat_fact"]]
        result (datomic/transact datomic-conn {:tx-data tx-data})]
    {:policy (q/retrieve-policy (:db-after result) policy-id)}))

(defn update-category-factors! [{:keys [datomic-conn] :as req} policy-id]
  (let [params (util.http/unwrap-params req)
        policy-id (util.http/ensure-uuid policy-id)
        tx-data (mapv (fn [{:keys [premium-factor category-id delete]}]
                        (if delete
                          [:db/retractEntity [:insurance.category.factor/category-factor-id (parse-uuid category-id)]]
                          {:insurance.category.factor/category-factor-id (parse-uuid category-id)
                           :insurance.category.factor/factor (bigdec premium-factor)}))
                      (if (sequential? params) params [params]))
        result (datomic/transact datomic-conn {:tx-data tx-data})]
    {:policy (q/retrieve-policy (:db-after result) policy-id)}))

(defn update-coverage-types! [{:keys [datomic-conn] :as req} policy-id]
  (let [params (util.http/unwrap-params req)
        policy-id (util.http/ensure-uuid policy-id)
        tx-data (mapv (fn [{:keys [description premium-factor name type-id delete] :as args}]
                        (if delete
                          [:db/retractEntity [:insurance.coverage.type/type-id (parse-uuid type-id)]]
                          {:insurance.coverage.type/name name
                           :insurance.coverage.type/description description
                           :insurance.coverage.type/type-id  (parse-uuid type-id)
                           :insurance.coverage.type/premium-factor (bigdec premium-factor)}))
                      (if (sequential? params) params [params]))
        result (datomic/transact datomic-conn {:tx-data tx-data})]
    {:policy (q/retrieve-policy (:db-after result) policy-id)}))

(defn create-coverage-type! [{:keys [datomic-conn] :as req} policy-id]
  (let [params (util.http/unwrap-params req)
        tx-data [{:insurance.coverage.type/type-id (sq/generate-squuid)
                  :insurance.coverage.type/name (:coverage-name params)
                  :insurance.coverage.type/description (:description params)
                  :insurance.coverage.type/premium-factor (bigdec (:premium-factor params))
                  :db/id "new-coverage"}
                 [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/coverage-types "new-coverage"]]
        result (datomic/transact datomic-conn {:tx-data tx-data})]
    {:policy (q/retrieve-policy (:db-after result) policy-id)}))

#_(defn upsert-instrument! [conn {:keys [owner-member-id category-id
                                         name make model build-year
                                         instrument-id
                                         serial-number]}]
    (let [tx-data [(util/remove-nils {:instrument/instrument-id (or  instrument-id (sq/generate-squuid))
                                      :instrument/owner [:member/member-id (util/ensure-uuid! owner-member-id)]
                                      :instrument/category [:instrument.category/category-id category-id]
                                      :instrument/name name
                                      :instrument/make make
                                      :instrument/model model
                                      :instrument/build-year build-year
                                      :instrument/serial-number serial-number})]
          result  (datomic/transact conn {:tx-data tx-data})]
      {:instrument
       (d/find-by (:db-after result)
                  :instrument/instrument-id
                  (:instrument/instrument-id (last tx-data))
                  q/instrument-pattern)}))

#_(defn create-instrument! [{:keys [datomic-conn] :as req}]
    (let [params (-> (util.http/unwrap-params req)
                     (update :category-id parse-uuid))]

      (upsert-instrument! datomic-conn params)))

#_(defn update-instrument! [{:keys [datomic-conn] :as req} instrument-id]
    (let [params (-> (util.http/unwrap-params req)
                     (update :category-id parse-uuid)
                     (assoc :instrument-id instrument-id))]
      (upsert-instrument! datomic-conn params)))

#_(defn build-image-uris [{:keys [system webdav] :as req} {:instrument/keys [images instrument-id]}]
    (let [{:keys [thumbnail-opts full-opts]} (config/insurance-image-settings (:env system))]
      {:thumbnail-uris
       (let [thumbnail-opts]
         (map (fn [{:image/keys [image-id]}] (urls/absolute-link-instrument-image-local-thumbnail (:env system) instrument-id  image-id thumbnail-opts)) images))
       :full-uris (map (fn [{:image/keys [image-id]}] (urls/absolute-link-instrument-image-local-full (:env system) instrument-id  image-id)) images)}))

(defn build-image-uri [{:keys [system]} {:instrument/keys [instrument-id]} {:image/keys [image-id]}]
  (when image-id
    {:thumbnail (urls/absolute-link-instrument-image-thumbnail (:env system) instrument-id  image-id)
     :full (urls/absolute-link-instrument-image-full (:env system) instrument-id  image-id)}))

(defn build-image-uris [req {:instrument/keys [images] :as instrument}]
  (map #(build-image-uri req instrument %) images))

(defn- append-images-to-zip! [zip  attachments file-name-prefix]
  (doseq [{:keys [content file-name]} attachments]
    (when (and content file-name)
      (zip/open-and-append! zip (str file-name-prefix "_" file-name) content))))

(defn get-all-images-as-input-stream! [{:keys [db] :as req} instrument-id]
  (let [{:instrument/keys [name images]} (q/retrieve-instrument db
                                                                instrument-id
                                                                [:instrument/name
                                                                 {:instrument/images [:image/image-id {:image/source-file [:filestore.file/file-id :filestore.file/hash :filestore.file/filename]}]}])
        file-name                        (str name ".zip")
        attachments                      (->> images
                                              (map :image/image-id)
                                              (map (partial filestore/load-image req))
                                              (map (fn [{:keys [content-thunk file-name]}]
                                                     {:content   content-thunk
                                                      :file-name file-name})))]
    {:status  200
     :headers {"Content-Type"        "application/octet-stream"
               "Content-Disposition" (util/content-disposition-filename file-name false)}
     :body    (zip/piped-zip-input-stream
               (fn [zip]
                 (append-images-to-zip! zip  attachments  "instrument")))}))

(defn add-instrument-coverage! [conn {:keys [instrument-id policy-id private? value coverage-type-ids]}]
  (datomic/transact conn {:tx-data [{:db/id "covered_instrument"
                                     :instrument.coverage/coverage-id (sq/generate-squuid)
                                     :instrument.coverage/instrument [:instrument/instrument-id instrument-id]
                                     :instrument.coverage/types (mapv (fn [type-id] [:insurance.coverage.type/type-id type-id]) coverage-type-ids)
                                     :instrument.coverage/private? private?
                                     :instrument.coverage/value (bigdec value)}
                                    [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/covered-instruments "covered_instrument"]]}))

(defn remove-instrument-coverage! [{:keys [datomic-conn] :as req} coverage-id]
  (let [result (datomic/transact datomic-conn {:tx-data [[:db/retractEntity [:instrument.coverage/coverage-id (util.http/ensure-uuid coverage-id)]]]})]
    {:policy (q/retrieve-policy (:db-after result) (-> req :policy :insurance.policy/policy-id))}))

(defn create-instrument-coverage! [{:keys [datomic-conn] :as req} policy-id]
  (let [params (-> (util.http/unwrap-params req))
        policy-id (util.http/ensure-uuid policy-id)
        instrument-id (parse-uuid (:instrument-id params))
        coverage-types (mapv util.http/ensure-uuid  (util/ensure-coll (:coverage-types params)))
        coverage-types-tx (mapv (fn [coverage-type-id]
                                  [:db/add "covered_instrument" :instrument.coverage/types [:insurance.coverage.type/type-id coverage-type-id]]) coverage-types)
        tx-data (concat coverage-types-tx  [{:instrument.coverage/coverage-id (sq/generate-squuid)
                                             :instrument.coverage/instrument [:instrument/instrument-id instrument-id]
                                             :instrument.coverage/value (bigdec (:value params))
                                             :instrument.coverage/private? (= "private" (:band-or-private params))
                                             :db/id "covered_instrument"}
                                            [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/covered-instruments "covered_instrument"]])

        result (datomic/transact datomic-conn {:tx-data tx-data})]
    {:policy (q/retrieve-policy (:db-after result) policy-id)}))

(def UpdateInstrument
  "This schema describes the http post we receive when updating an instrument"
  (s/schema
   [:map {:name ::UpdateInstrument}
    [:instrument-id {:optional true} [:or :uuid [:string {:min 0 :max 0}]]]
    [:category-id :uuid]
    [:owner-member-id :uuid]
    [:description :string]
    [:build-year :string]
    [:serial-number :string]
    [:make ::s/non-blank-string]
    [:model :string]
    [:instrument-name ::s/non-blank-string]]))

(def UpdateCoverage
  "This schema describes the http post we receive when updating a coverage"
  (s/schema
   [:map {:name ::UpdateInstrumentAndCoverage}
    [:instrument-id :uuid]
    [:policy-id :uuid]
    [:coverage-id {:optional true} [:or :uuid [:string {:min 0 :max 0}]]]
    [:coverage-types [:vector {:min 1} :uuid]]
    [:value [:int {:min 1}]]
    [:item-count [:int {:min 1}]]
    [:insurer-id {:optional true} :string]
    [:private-band [:enum "band" "private"]]]))

(def UpdateInstrumentAndCoverage
  "This schema describes the http post we receive when updating an instrument and coverage "
  (mu/merge UpdateInstrument UpdateCoverage))

(defn update-coverage-txs [policy {:keys [value coverage-types item-count private-band insurer-id] :as decoded} coverage owner-changed? category-changed?]
  (let [coverage-ref (d/ref coverage)
        selected-coverage-type-ids (util/remove-dummy-uuid (mapv util.http/ensure-uuid (util/ensure-coll coverage-types)))
        private? (= "private" private-band)
        txs-coverage-changes  (if private?
                                (domain/txs-private-instrument-coverage-types coverage selected-coverage-type-ids)
                                ;; band instruments get all coverage types
                                (domain/txs-band-instrument-coverage-types policy coverage))
        has-coverage-changes? (boolean (seq txs-coverage-changes))
        ;; if these things have changed then we need to ensure the changes get marked so we can send them upstream to the provider
        has-upstream-change? (domain/has-upstream-change? coverage
                                                          owner-changed?
                                                          category-changed?
                                                          value
                                                          item-count
                                                          private?
                                                          has-coverage-changes?)
        current-change-status (get coverage :instrument.coverage/change :instrument.coverage.change/none)
        is-new? (= :instrument.coverage.change/new current-change-status)
        change-status-value (cond
                              (not has-upstream-change?) current-change-status
                              is-new? :instrument.coverage.change/new
                              :else :instrument.coverage.change/changed)
        #_#__ (tap> {:value-new value
                     :value-old (:instrument.coverage/value coverage)
                     :value-changed (not (== (:instrument.coverage/value coverage) value))
                     :count-new item-count
                     :count-old (:instrument.coverage/item-count coverage)
                     :count-changed (not= (:instrument.coverage/item-count coverage) item-count)
                     :coverage-changes coverage-changes
                     :covchanged (not-empty coverage-changes)
                     :has-upstream-change? has-upstream-change?})]

    (concat txs-coverage-changes
            [[:db/add coverage-ref :instrument.coverage/value (bigdec value)]
             [:db/add coverage-ref :instrument.coverage/status (if has-upstream-change? :instrument.coverage.status/needs-review (:instrument.coverage/status coverage))]
             [:db/add coverage-ref :instrument.coverage/change change-status-value]
             [:db/add coverage-ref :instrument.coverage/private? private?]
             [:db/add coverage-ref :instrument.coverage/item-count item-count]]
            (when-not (str/blank? insurer-id)
              [[:db/add coverage-ref :instrument.coverage/insurer-id insurer-id]]))))

(defn create-coverage-txs [policy {:keys [value coverage-types item-count private-band policy-id instrument-id] :as decoded}]
  (let [private? (= "private" private-band)
        txs-coverage-changes  (if private?
                                ;; private instruments get selection
                                (->> (util/remove-dummy-uuid coverage-types)
                                     (mapv (fn [coverage-type-id]
                                             [:db/add "covered_instrument" :instrument.coverage/types [:insurance.coverage.type/type-id coverage-type-id]])))
                                ;; band instruments get all coverage types
                                (->> (:insurance.policy/coverage-types policy)
                                     (map :insurance.coverage.type/type-id)
                                     (mapv (fn [type-id]
                                             [:db/add "covered_instrument" :instrument.coverage/types [:insurance.coverage.type/type-id type-id]]))))]

    (concat
     txs-coverage-changes
     [{:instrument.coverage/coverage-id (sq/generate-squuid)
       :instrument.coverage/instrument [:instrument/instrument-id instrument-id]
       :instrument.coverage/value (bigdec value)
       :instrument.coverage/item-count item-count
       :instrument.coverage/status :instrument.coverage.status/needs-review
       :instrument.coverage/change :instrument.coverage.change/new
       :instrument.coverage/private? private?
       :db/id "covered_instrument"}
      [:db/add [:insurance.policy/policy-id policy-id] :insurance.policy/covered-instruments "covered_instrument"]])))

(defn update-instrument-tx [{:keys [description build-year serial-number make instrument-name owner-member-id model category-id] :as decoded} instrument-id]
  {:instrument/instrument-id instrument-id
   :instrument/description description
   :instrument/build-year build-year
   :instrument/serial-number serial-number
   :instrument/make make
   :instrument/model model
   :instrument/name instrument-name
   :instrument/owner [:member/member-id owner-member-id]
   :instrument/category [:instrument.category/category-id category-id]})

(defn upsert-instrument! [req]
  (let [decoded (util/remove-nils (s/decode UpdateInstrument (util.http/unwrap-params req)))]
    (if (s/valid? UpdateInstrument decoded)
      (let [creating? (str/blank? (:instrument-id decoded))
            instrument-id (if creating? (sq/generate-squuid) (:instrument-id decoded))
            instr-tx (update-instrument-tx decoded instrument-id)
            tempid (d/tempid)
            instr-tx (if creating?
                       (assoc instr-tx :db/id tempid)
                       instr-tx)
            txs (concat [instr-tx]
                        (domain/txs-instrument-share-link req (if creating? tempid [:instrument/instrument-id instrument-id]) instrument-id))
            {:keys [db-after]} (d/transact-wrapper! req {:tx-data txs})]
        {:instrument (q/retrieve-instrument db-after instrument-id)})
      {:error (s/explain-human UpdateInstrument decoded)})))

(defn upsert-coverage! [{:keys [db] :as req}]
  (let [params (util.http/unwrap-params req)
        decoded (util/remove-nils (s/decode UpdateCoverage params))
        coverage-id (util.http/ensure-uuid (:coverage-id decoded))
        policy-id (:policy-id decoded)
        policy (q/retrieve-policy db policy-id)
        valid-change? (s/valid? UpdateCoverage decoded)]
    (cond
      (not (policy-editable? policy))
      {:error {:form-error :insurance/error-edit-frozen-policy} :policy policy}

      valid-change?
      (let [txs (if coverage-id
                  ;; upsert
                  (update-coverage-txs policy decoded (q/retrieve-coverage db coverage-id) false false)
                  ;; create
                  (create-coverage-txs policy decoded))
            {:keys [db-after]} (d/transact-wrapper! req {:tx-data txs})]
        {:policy (q/retrieve-policy db-after policy-id)})
      :else
      {:error (s/explain-human UpdateCoverage decoded)
       :policy (q/retrieve-policy db policy-id)})))

(defn update-instrument-and-coverage! [{:keys [db] :as req}]
  (let [params (util.http/unwrap-params req)
        decoded  (util/remove-nils (s/decode UpdateInstrumentAndCoverage params))
        ;; _ (tap> {:decoded decoded})
        policy (q/retrieve-policy db (:policy-id decoded))
        change-valid? (s/valid? UpdateInstrumentAndCoverage decoded)]
    (cond
      (not (policy-editable? policy))
      {:error {:form-error :insurance/error-edit-frozen-policy} :policy policy}

      change-valid?
      (let [coverage (q/retrieve-coverage db (util.http/ensure-uuid (:coverage-id decoded)))
            _ (assert coverage)
            instrument-id (:instrument-id decoded)
            old-instrument (q/retrieve-instrument db instrument-id)
            new-owner-member-id (:owner-member-id decoded)
            old-owner-member-id (-> old-instrument  :instrument/owner :member/member-id)
            owner-changed? (not= new-owner-member-id old-owner-member-id)
            new-category-id (:category-id decoded)
            old-category-id  (-> old-instrument :instrument/category :instrument.category/category-id)
            category-changed? (not= new-category-id old-category-id)
            txs (concat (update-coverage-txs policy decoded coverage owner-changed? category-changed?)
                        [(update-instrument-tx decoded instrument-id)]
                        (domain/txs-instrument-share-link req [:instrument/instrument-id instrument-id] instrument-id))
            ;; _ (tap> {:tx txs})
            {:keys [db-after]} (d/transact-wrapper! req {:tx-data txs})]
        {:policy (q/retrieve-policy db-after (:policy-id decoded))})

      :else {:error (s/explain-human UpdateInstrumentAndCoverage decoded)
             :policy (q/retrieve-policy db (:policy-id decoded))})))

(defn delete-coverage! [{:keys [db] :as req}]
  (let [coverage-id (-> req (util.http/unwrap-params) :coverage-id util/ensure-uuid!)
        ;; TODO  do we want to remove the instrument if there are no other coverages?
        coverage (q/retrieve-coverage db coverage-id)
        #_related-coverages #_(->> (q/coverages-for-instrument db (-> coverage :instrument.coverage/instrument :instrument/instrument-id) q/instrument-coverage-detail-pattern)
                                   (remove #(= coverage-id (:instrument.coverage/coverage-id %))))
        txs [[:db/retractEntity [:instrument.coverage/coverage-id coverage-id]]]
        result (d/transact-wrapper! req {:tx-data txs})]
    {:policy (q/retrieve-policy (:db-after result) (-> coverage :insurance.policy/_covered-instruments  :insurance.policy/policy-id))}))

(defn toggle-instrument-coverage-type! [{:keys [datomic-conn db]} coverage-id type-id]
  (let [coverage-id (util.http/ensure-uuid coverage-id)
        type-id (util.http/ensure-uuid type-id)
        coverage (q/retrieve-coverage db coverage-id)
        operation (if (domain/get-coverage-type-from-coverage coverage type-id) :db/retract :db/add)
        tx-data [[operation [:instrument.coverage/coverage-id coverage-id] :instrument.coverage/types [:insurance.coverage.type/type-id (util.http/ensure-uuid type-id)]]]
        result (datomic/transact datomic-conn {:tx-data tx-data})]
    {:coverage (q/retrieve-coverage (:db-after result) coverage-id)}))

(defn instrument-categories [db]
  (mapv first
        (d/find-all db :instrument.category/category-id  [:instrument.category/name :instrument.category/category-id])))

(def category-default-factors
  {"Blech, Sax, Elektrisch" (bigdec 2.0)
   "Holz" (bigdec 1.3)
   "Schlag" (bigdec 1.6)
   "Etui/Zubehör/Mundstück" (bigdec 0.8)
   "Zupf und Bögen" (bigdec 1.0)
   "Akkordeon" (bigdec 2.0)
   "Streich" (bigdec 1.5)})

(def category-default-tx
  [{:instrument.category/category-id (sq/generate-squuid)
    :instrument.category/name "Blech, Sax, Elektrisch"
    :instrument.category/code "1"}
   {:instrument.category/category-id (sq/generate-squuid)
    :instrument.category/name "Holz"
    :instrument.category/code "2"}
   {:instrument.category/category-id (sq/generate-squuid)
    :instrument.category/name "Schlag"
    :instrument.category/code "3"}

   {:instrument.category/category-id (sq/generate-squuid)
    :instrument.category/name "Etui/Zubehör/Mundstück"
    :instrument.category/code "4"}

   {:instrument.category/category-id (sq/generate-squuid)
    :instrument.category/name "Zupf und Bögen"
    :instrument.category/code "5"}

   {:instrument.category/category-id (sq/generate-squuid)
    :instrument.category/name "Akkordeon"
    :instrument.category/code "6"}

   {:instrument.category/category-id (sq/generate-squuid)
    :instrument.category/name "Streich"
    :instrument.category/code "7"}])

(defn coverages-grouped-by-owner [policy]
  (->> (:insurance.policy/covered-instruments policy)
       (util/group-by-into-list :coverages (fn [c] (get-in c [:instrument.coverage/instrument :instrument/owner])))
       (mapv (fn [r] (update r :coverages #(sort-by (fn [c] (get-in c [:instrument.coverage/instrument :instrument/name])) %))))
       (mapv (fn [r] (update r :coverages #(domain/enrich-coverages policy (:insurance.policy/coverage-types policy) %))))
       (mapv (fn [{:keys [coverages] :as person}]
               (assoc person :total (domain/sum-by coverages :instrument.coverage/cost))))
       (sort-by :member/name)))

(def MarkAsSchema
  (s/schema
   [:map {:name ::MarkAs}
    [:policy-id :uuid]
    [:workflow-status {:optional true} (into [:enum {:kw-namespace true}] domain/instrument-coverage-statuses)
     [:enum {:kw-namespace true} :instrument.coverage.status/needs-review :instrument.coverage.status/reviewed :instrument.coverage.status/coverage-active]]
    [:change-status {:optional true} (into [:enum {:kw-namespace true}] domain/instrument-coverage-changes)]
    [:coverage-ids [:vector {:vectorize true} :uuid]]]))

(defn mark-coverages-as! [req]
  (let [{:keys [policy-id coverage-ids workflow-status change-status] :as p} (s/decode MarkAsSchema (:params req))]
    ;; (tap> {:mark-as p :valid? (s/valid? MarkAsSchema p)})
    (if (s/valid? MarkAsSchema p)
      (let [workflow-status-txs (if workflow-status (map (fn [cid]
                                                           [:db/add [:instrument.coverage/coverage-id cid] :instrument.coverage/status workflow-status]) coverage-ids)
                                    [])
            change-status-txs (if change-status (map (fn [cid]
                                                       [:db/add [:instrument.coverage/coverage-id cid] :instrument.coverage/change change-status]) coverage-ids)
                                  [])
            txs (concat change-status-txs workflow-status-txs)
            ;; _ (tap> [:txs txs])
            {:keys [db-after]} (d/transact-wrapper! req {:tx-data txs})]
        {:policy (q/retrieve-policy db-after policy-id)
         :db-after db-after})
      (s/throw-error "Invalid arguments" nil MarkAsSchema p))))

(defn download-changes-excel [{:keys [db] :as req}]
  (let [policy-id (util.http/path-param-uuid! req :policy-id)
        policy (q/retrieve-policy db policy-id)
        {:keys [attachment-filename preview-type] :as p} (-> req :params)
        _ (assert preview-type)
        _ (assert attachment-filename)
        output-stream (ByteArrayOutputStream.)
        changeset-scope (condp = preview-type
                          "new"  #{:instrument.coverage.change/new}
                          "changes" #{:instrument.coverage.change/changed :instrument.coverage.change/removed})
        file-bytes (.toByteArray (excel/generate-excel-changeset! changeset-scope policy output-stream))]
    {:status 200
     :headers {"Content-Disposition" (format "%s; filename*=UTF-8''%s" "attachment" (URLEncoder/encode attachment-filename "UTF-8"))
               "Content-Type" "application/vnd.ms-excel"
               "Content-Length" (str (count file-bytes))}
     :body (java.io.ByteArrayInputStream. file-bytes)}))

(defn- confirm-and-activate-policy [{:keys [db] :as req} policy-id]
  (let [policy (q/retrieve-policy db policy-id)
        txs (domain/txs-confirm-and-activate-policy policy)]
    ;; (tap> {:confirm-txs txs})
    (d/transact-wrapper! req {:tx-data txs})))

(defn confirm-changes! [{:keys [db] :as req}]
  (confirm-and-activate-policy req (util.http/path-param-uuid! req :policy-id)))

(defn send-changes! [{:keys [db] :as req}]
  (let [policy-id (util.http/path-param-uuid! req :policy-id)
        {:keys [attachment-filename-changes attachment-filename-new] :as p} (:params req)
        _ (tap> [:send-changes! (:params req)])
        _ (assert attachment-filename-changes)
        _ (assert attachment-filename-new)
        policy (q/retrieve-policy db policy-id)
        {:keys [subject recipient body]} p
        smtp (-> req :system :env :smtp-sno)
        from (:from smtp)]
    (excel/send-email! policy smtp from recipient subject body attachment-filename-new attachment-filename-changes)
    #_(confirm-and-activate-policy req (util.http/path-param-uuid! req :policy-id))))

(defn instrument-coverage-history [{:keys [db] :as req} {:instrument.coverage/keys [coverage-id instrument] :as coverage}]
  (let [instr-id (:instrument/instrument-id instrument)
        instr-history (d/entity-history db :instrument/instrument-id instr-id)
        coverage-history (d/entity-history db :instrument.coverage/coverage-id coverage-id)]
    ;; (tap> {:instr-history instr-history :coverage-history coverage-history})
    (->>
     (concat instr-history
             coverage-history)
     (group-by :tx-id)
     (vals)
     (map (fn [txs]
            (reduce (fn [agg {:keys [audit changes timestamp tx-id]}]
                      (-> agg
                          (assoc :tx-id tx-id)
                          (assoc :timestamp timestamp)
                          (assoc :audit audit)
                          (update :changes concat changes))) {} txs)))
     (sort-by :timestamp)
     (reverse)
      ;; (vals)
      ;; (flatten)
     )))

(defn policy-totals [policy]
  (let [covered-instruments (:insurance.policy/covered-instruments policy)
        grouped-by-owner (coverages-grouped-by-owner policy)]
    {:total-cost (domain/sum-by grouped-by-owner :total)
     :total-instruments  (count covered-instruments)
     :total-private-count (count (filter :instrument.coverage/private? covered-instruments))
     :total-band-count (count (remove :instrument.coverage/private? covered-instruments))
     :total-needs-review (count (filter #(= :instrument.coverage.status/needs-review (:instrument.coverage/status %)) covered-instruments))
     :total-reviewed (count (filter #(= :instrument.coverage.status/reviewed (:instrument.coverage/status %)) covered-instruments))
     :total-coverage-active (count (filter #(= :instrument.coverage.status/coverage-active (:instrument.coverage/status %)) covered-instruments))
     :total-changed (count (filter #(= :instrument.coverage.change/changed (:instrument.coverage/change %)) covered-instruments))
     :total-removed (count (filter #(= :instrument.coverage.change/removed (:instrument.coverage/change %)) covered-instruments))
     :total-new (count (filter #(= :instrument.coverage.change/new (:instrument.coverage/change %)) covered-instruments))
     :total-no-changes (count (remove #(#{:instrument.coverage.change/changed :instrument.coverage.change/new :instrument.coverage.change/removed} (:instrument.coverage/change %)) covered-instruments))}))

(defn policies-with-todos [db]
  (->> (q/policies db)
       (mapv (fn [policy]
               (merge policy (policy-totals policy))))
       (filter #(> (:total-needs-review %) 0))))

(defn build-data-notification-table [{:keys [db] :as req}]
  (let [policy-id (util.http/path-param-uuid! req :policy-id)
        {:insurance.policy/keys [effective-at effective-until] :as  policy} (q/retrieve-policy db policy-id)
        grouped-by-owner (coverages-grouped-by-owner policy)]
    {:policy policy
     :time-range (format "%s - %s" (t/year effective-at) (t/year effective-until))
     :sender-name (:member/name (auth/get-current-member req))
     :members-data (->> grouped-by-owner
                        (map (fn [{:member/keys [name member-id] :keys [coverages total] :as member}]
                               (let [private-coverages (filter :instrument.coverage/private? coverages)
                                     private-cost-total-decimal (reduce + (map :instrument.coverage/cost private-coverages))
                                     private-cost-total (-> private-cost-total-decimal
                                                            (double)
                                                            (* 100)
                                                            (Math/round)
                                                            (int))]
                                 (when (seq private-coverages)
                                   {:member member
                                    :private-coverages private-coverages
                                    :count-private (count private-coverages)
                                    :private-cost-total private-cost-total}))))

                        (util/remove-nils))}))

(defn member-debited-for-policy? [db policy-id member-id]
  (let [existing-entries (q/ledger-entry-debit-for-policy db member-id policy-id)]
    (boolean (seq existing-entries))))

(defn txs-new-transaction-for-member [db policy-id policy-name {:keys [member private-cost-total count-private] :as members-data}]
  (ledger.domain/prepare-transaction-data db
                                          (q/retrieve-ledger db (:member/member-id member))
                                          {:ledger.entry/amount       private-cost-total
                                           :ledger.entry/tx-date      (t/date)
                                           :ledger.entry/posting-date (t/inst)
                                           :ledger.entry/description   policy-name
                                           :ledger.entry/entry-id     (sq/generate-squuid)}
                                          {:ledger.entry.meta/meta-type :ledger.entry.meta.type/insurance
                                           :ledger.entry.meta.insurance/policy [:insurance.policy/policy-id policy-id]}))

(defn prepare-new-transaction-txs [{:keys [db]} policy-id policy-name members-data]
  (->> members-data
       (remove #(member-debited-for-policy? db policy-id (-> % :member :member/member-id)))
       (reduce #(concat %1 (txs-new-transaction-for-member db policy-id policy-name %2)) (list))))

(defn send-notifications! [{:keys [db] :as req}]
  (let [{:keys [policy time-range members-data sender-name]} (build-data-notification-table req)
        policy-id (util.http/path-param-uuid! req :policy-id)
        params (util.http/unwrap-params req)
        member-ids (set (map util/ensure-uuid! (util/ensure-coll (:member-ids params))))
        to-send (filter #(member-ids (-> % :member :member/member-id))
                        members-data)
        tx-data (prepare-new-transaction-txs req policy-id (:insurance.policy/name policy) to-send)]
    ;; (tap> [:to-send to-send :tx-data tx-data])
    (try
      (let [result (d/transact-wrapper! req {:tx-data tx-data})]
        (email/send-insurance-debt-notifications! req sender-name time-range to-send)
        {:count-sent (count to-send) :policy policy})
      (catch Exception e
        (tap> e)
        (errors/report-error! e)
        {:error "Failed to send notifications" :ex e}))))

(defn report-belongs-to-current? [req report]
  (let [current-member (auth/get-current-member req)
        owning-member-id (-> report :response :insurance.survey.response/member :member/member-id)]
    (= owning-member-id (:member/member-id current-member))))

(defn survey-report-for-member [{:keys [db] :as req}]
  (let [{:keys [report-id]}  (util.http/unwrap-params req)
        report-id (util/ensure-uuid! report-id)
        report (q/retrieve-survey-report db report-id)]
    (if (report-belongs-to-current? req report)
      report
      (throw (ex-info "Report does not belong to current user" {:report-id report-id})))))

(defn survey-response-for-member [{:keys [db] :as req} policy]
  (let [response (q/open-survey-for-member-policy db (auth/get-current-member req) policy)]
    response))

(defn survey-table-items [{:keys [db] :as req}]
  (let [surveys (q/surveys-for-policy db (:policy req))
        open-surveys (filter #(nil? (:insurance.survey/closed-at %)) surveys)
        closed-surveys (filter #(some? (:insurance.survey/closed-at %)) surveys)]
    {:open-surveys open-surveys
     :closed-surveys closed-surveys}))

(defn complete-member-survey-response [db response member]
  [:db/add (d/ref response) :insurance.survey.response/completed-at (t/inst)])

(defn dismiss-insurance-widget! [{:keys [db] :as req} policy-id]
  (let [member (auth/get-current-member req)
        policy (q/retrieve-policy db policy-id)
        open-items (q/open-survey-for-member-items db member policy)]
    (if (= 0 open-items)
      (let [response (q/open-survey-for-member-policy db member policy)
            tx (complete-member-survey-response db response member)]
        (d/transact-wrapper! req {:tx-data [tx]})
        :finished)
      :not-finished)))

(defn resolve-survey-report! [{:keys [db] :as req} active-report decisions]
  (let [txs (->> decisions
                 (mapcat #(domain/txs-for-decision active-report %))
                 (concat (domain/txs-complete-survey-report active-report))
                 (concat (domain/txs-maybe-survey-response-complete active-report (q/open-survey-for-member-policy db (-> active-report :response :insurance.survey.response/member) (:policy req)))))]
    ;; (tap> {:report active-report :deci decisions :txs txs})
    (d/transact-wrapper! req {:tx-data txs})))

(defn members-for-policy-survey
  "Returns a list of members that are covered by the policy and active members that are not covered.
  Includes the :coverages and cost :total"
  [db policy]
  (let [existing-coverages-by-member (coverages-grouped-by-owner policy)
        existing-member-ids (set (map :member/member-id existing-coverages-by-member))
        active-members (q/active-members db)
        all-active-member-ids (set (map :member/member-id active-members))
        member-ids-without-insurance (set/difference all-active-member-ids existing-member-ids)]
    (->> active-members
         (filter #(member-ids-without-insurance (-> % :member/member-id)))
         (map #(assoc % :coverages [] :total 0))
         (concat existing-coverages-by-member)
         (sort-by :member/name))))

(defn txs-new-survey [db survey-name closes-at policy]
  (let [survey-tempid (d/tempid)
        members (members-for-policy-survey db policy)
        policy-id (:insurance.policy/policy-id policy)
        response-txs (mapcat (fn [{:keys [coverages] :member/keys [member-id]}]
                               (let [report-txs (map (fn [{:instrument.coverage/keys [coverage-id] :as cov}]
                                                       (domain/tx-new-survey-report (d/tempid) coverage-id))
                                                     coverages)
                                     report-tempids (map :db/id report-txs)]
                                 (conj report-txs
                                       (domain/tx-new-survey-response (d/tempid) member-id report-tempids))))
                             members)
        response-tempids (map :db/id response-txs)
        survey-tx (domain/tx-new-survey survey-tempid survey-name policy-id closes-at response-tempids)]
    (conj response-txs survey-tx)))

(defn upsert-survey! [{:keys [db] :as req}]
  (let [insurance-team-member? (q/insurance-team-member? db (auth/get-current-member req))
        policy (:policy req)
        {:keys [survey-id survey-name closes-at] :as p} (util.http/unwrap-params req)
        txs (if (str/blank? survey-id)
              (txs-new-survey db survey-name (t/date-time closes-at) policy)
              (domain/txs-update-survey (util/ensure-uuid! survey-id) survey-name (t/date-time closes-at)))]
    (when insurance-team-member?
      (d/transact-wrapper! req {:tx-data txs}))))

(defn close-survey! [{:keys [db] :as req}]
  (let [insurance-team-member? (q/insurance-team-member? db (auth/get-current-member req))
        {:keys [survey-id]} (util.http/unwrap-params req)
        survey-id (util/ensure-uuid! survey-id)
        txs [[:db/add [:insurance.survey/survey-id survey-id] :insurance.survey/closed-at (t/inst)]]]
    (when insurance-team-member?
      (d/transact-wrapper! req {:tx-data txs}))))

(defn members-to-email-about-survey [{:insurance.survey/keys [responses] :as survey}]
  ;; return a list of members who have not completed the survey
  (->> responses
       (filter #(nil? (:insurance.survey.response/completed-at %)))
       (map :insurance.survey.response/member)))

(defn send-survey-notifications! [{:keys [db] :as req}]
  (let [policy (:policy req)
        {:keys [survey-id] :as p} (util.http/unwrap-params req)
        survey-id (util/ensure-uuid! survey-id)
        {:insurance.survey/keys [closes-at responses] :as survey} (q/retrieve-survey db survey-id)
        members-to-email (members-to-email-about-survey survey)
        most-member (apply max-key (comp count :insurance.survey.response/coverage-reports) responses)
        email-data {:closes-at closes-at
                    :member-most-instruments (:insurance.survey.response/member most-member)
                    :member-most-instrument-count (count (:insurance.survey.response/coverage-reports most-member))}]
    (email/send-survey-notifications! req (:member/name (auth/get-current-member req)) policy members-to-email email-data)))

(defn toggle-survey-response-completion! [{:keys [db] :as req}]
  (let [{:keys [response-id]} (util.http/unwrap-params req)
        response-id (util/ensure-uuid! response-id)
        response (q/retrieve-survey-response db response-id)
        txs (domain/txs-toggle-response-completion response)
        result (d/transact-wrapper! req {:tx-data txs})]
    (q/retrieve-survey-response (:db-after result) response-id)))

(defn -upload-instrument-image! [req  instrument-id {:keys [filename _size tempfile content-type]}]
  (let [{:keys [image-tempid tx-data]} (filestore/store-image! req {:file-name filename :file tempfile :mime-type content-type})
        instr-txs (domain/txs-add-instrument-image req instrument-id image-tempid)
        txs (concat tx-data instr-txs)]
    (try
      (d/transact-wrapper! req {:tx-data txs})
      (finally
        (fs/delete-if-exists tempfile)))))
(defn upload-instrument-image! [{:keys [parameters] :as req}]
  (let [instrument-id (util/ensure-uuid! (-> parameters :path :instrument-id))
        ;; file is a reitit.ring.malli/temp-file-part
        file (-> parameters :multipart :file)]
    (-upload-instrument-image! req instrument-id file)))

(defn upload-instrument-images! [req instrument-id files]
  (assert instrument-id "instrument-id is required")
  (let [;; files is a vec of reitit.ring.malli/temp-file-part
        images (doall (map (fn [{:keys [filename _size tempfile content-type]}]
                             (filestore/store-image! req {:file-name filename :file tempfile :mime-type content-type}))
                           files))
        txs (mapcat (fn [{:keys [image-tempid tx-data]}]
                      (concat tx-data
                              (domain/txs-add-instrument-image req instrument-id image-tempid))) images)]
    (try
      (d/transact-wrapper! req {:tx-data txs})
      (finally
        (doseq [{:keys [tempfile]} files]
          (fs/delete-if-exists tempfile))))))

(defn load-instrument-image [{:keys [system] :as req} instrument-id image-id mode]
  (let [{:keys [thumbnail-opts full-opts] :as opts} (config/insurance-image-settings (:env system))]
    (if (= mode "thumbnail")
      (filestore/load-image-rendition req image-id thumbnail-opts)
      (filestore/load-image-rendition req image-id full-opts))))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (require '[datomic.client.api :as datomic])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn))) ;; rcf

  (datomic/transact conn {:tx-data [[:db/retractEntity [:image/image-id #uuid "018f670f-96ef-8a29-a183-cf2d713b3f26"]]]})

  (members-for-policy-survey db (q/retrieve-policy db #uuid "018e15c2-ddbe-8b4f-b814-bddd26f8aec2"))
  (txs-new-survey db
                  "Survey 2024"
                  (t/>> (t/instant) (t/new-period 3 :days))
                  (q/retrieve-policy db #uuid "018e15c2-ddbe-8b4f-b814-bddd26f8aec2")) ;; rcf

  (q/retrieve-coverage db #uuid "018e15c2-ddbf-89a1-bb60-61e5e01189b9")
  (q/retrieve-coverage db #uuid "018e15c2-ddbf-89a1-bb60-61e5e01189b7")

  (datomic/transact conn {:tx-data [{:insurance.policy/policy-id #uuid "018e15c2-ddbe-8b4f-b814-bddd26f8aec2" :insurance.policy/status :insurance.policy.status/draft}]})

  (q/retrieve-policy db
                     (->
                      (q/policies db)
                      first
                      :insurance.policy/policy-id
                      str
                      parse-uuid))

  (datomic/transact conn {:tx-data category-default-tx})

  (defn rename-category [conn code new-name]
    (let [ref (d/ref (d/find-by (datomic/db conn) :instrument.category/code code
                                [:instrument.category/category-id :instrument.category/name])
                     :instrument.category/category-id)]
      (datomic/transact conn {:tx-data [[:db/add ref :instrument.category/name new-name]]})))

  (rename-category conn "4" "Etui/Zubehör/Mundstück")
  (rename-category conn "5" "Zupf und Bögen")

  (def category-blech-ref
    (d/ref (d/find-by (datomic/db conn) :instrument.category/code "4"
                      [:instrument.category/category-id :instrument.category/name])
           :instrument.category/category-id))

  (d/find-all db :member/name [:member/member-id])

  (def casey-ref
    (d/ref
     (d/find-by db :member/name "Casey Link" [:member/member-id])
     :member/member-id))

  (datomic/transact conn {:tx-data [{:instrument/owner         casey-ref
                                     :instrument/category      category-blech-ref
                                     :instrument/name          "Marching Trombone"
                                     :instrument/instrument-id (sq/generate-squuid)
                                     :instrument/make          "King"
                                     :instrument/model         "Flugelbone"
                                     :instrument/build-year    "1971"
                                     :instrument/serial-number "12345"}]})

  (def caseys-instrument-ref (d/ref
                              (d/find-by (datomic/db conn) :instrument/owner casey-ref [:instrument/instrument-id])
                              :instrument/instrument-id))

  (datomic/transact conn {:tx-data [{:db/ident       :insurance.policy/effective-until
                                     :db/doc         "The instant at which this policy ends"
                                     :db/valueType   :db.type/instant
                                     :db/cardinality :db.cardinality/one}]})

  (def txns [{:db/id                                        "cat_factor_blech"
              :insurance.category.factor/category-factor-id (sq/generate-squuid)
              :insurance.category.factor/category           category-blech-ref
              :insurance.category.factor/factor             (bigdec 2.0)}
             {:db/id                                  "ct_basic"
              :insurance.coverage.type/type-id        (sq/generate-squuid)
              :insurance.coverage.type/name           "Basic Coverage"
              :insurance.coverage.type/description    ""
              :insurance.coverage.type/premium-factor (bigdec 1.0)}

             {:db/id                                  "ct_overnight"
              :insurance.coverage.type/type-id        (sq/generate-squuid)
              :insurance.coverage.type/name           "Auto/Overnight"
              :insurance.coverage.type/description    ""
              :insurance.coverage.type/premium-factor (bigdec 0.25)}
             {:db/id                                  "ct_proberaum"
              :insurance.coverage.type/type-id        (sq/generate-squuid)
              :insurance.coverage.type/name           "Proberaum"
              :insurance.coverage.type/description    ""
              :insurance.coverage.type/premium-factor (bigdec 0.2)}

             {:instrument.coverage/coverage-id (sq/generate-squuid)
              :instrument.coverage/instrument  caseys-instrument-ref
              :instrument.coverage/types       ["ct_basic" "ct_overnight" "ct_proberaum"]
              :instrument.coverage/private?    false
              :instrument.coverage/value       (bigdec 1000)}

             {:insurance.policy/policy-id           (sq/generate-squuid)
              :insurance.policy/currency            :currency/EUR
              :insurance.policy/name                "2022-2023"
              :insurance.policy/effective-at        (t/inst)
              :insurance.policy/effective-until
              (t/inst
               (t/>>
                (t/inst)
                (t/new-duration 365 :days)))
              :insurance.policy/covered-instruments []
              :insurance.policy/coverage-types      ["ct_basic" "ct_overnight" "ct_proberaum"]
              :insurance.policy/premium-factor      (* (bigdec 1.07) (bigdec 0.00447))
              :insurance.policy/category-factors    ["cat_factor_blech"]}])

  (datomic/transact conn {:tx-data txns})

  (def policy-ref (d/ref (d/find-by (datomic/db conn) :insurance.policy/name "2022-2023" [:insurance.policy/policy-id])
                         :insurance.policy/policy-id))

  (def caseys-covered-ref (d/ref
                           (ffirst
                            (d/find-all (datomic/db conn)
                                        :instrument.coverage/coverage-id
                                        [:instrument.coverage/coverage-id]))
                           :instrument.coverage/coverage-id))

  (datomic/transact conn {:tx-data [{:db/id                                policy-ref
                                     :insurance.policy/covered-instruments [caseys-covered-ref]}]})

  (datomic/transact conn {:tx-data [{:db/id                     caseys-covered-ref
                                     :instrument.coverage/value (bigdec 3000)}]})

  (def policy
    (d/find-by (datomic/db conn) :insurance.policy/name "2022-2023"
               q/policy-pattern))

  (sum-by
   (-> policy
       :insurance.policy/covered-instruments
       first
       :instrument.coverage/types)
   :insurance.coverage.type/premium-factor)

  (make-category-factor-lookup policy)

  (update-total-coverage-price policy
                               (-> policy
                                   :insurance.policy/covered-instruments
                                   first))

  (update-coverage-price
   (bigdec 2.0)
   (:insurance.policy/premium-factor policy)
   (-> policy
       :insurance.policy/covered-instruments
       first))

  (defn ident [db attr]
    (:db/ident (datomic/pull db '[:db/ident] attr)))

  (defn ref? [db attr]
    (= :db.type/ref
       (->
        (datomic/pull db '[*] attr)
        :db/valueType
        :db/ident)))

  (defn resolve-ref [db eid]
    (datomic/pull db '[*] eid))

  (require '[app.debug :refer [xxx xxx>>]])

  (instrument-coverage-history {:db (datomic/db conn)} (q/retrieve-coverage (datomic/db conn) #uuid "018e15c2-ddbf-89a1-bb60-61e5e01189b8"))

  (let [db (datomic/db conn)]
    (->> (datomic/q '{:find  [?tx ?attr ?val ?added]
                      :in    [$ ?coverage-id]
                      :where [[?e :instrument.coverage/coverage-id ?coverage-id]
                              [?e ?attr ?val ?tx ?added]]}
                    (datomic/history db)
                    #uuid "018e15c2-ddbf-89a1-bb60-61e5e01189b8")
         (xxx>>)
         (group-by first)
         (map (fn [[tx transactions]]
                (let [tx-info (datomic/pull db '[*] tx)]
                  {:timestamp (:db/txInstant tx-info)
                   :audit     (select-keys tx-info [:audit/user])
                   :changes   (->> transactions
                                   (map (fn [[_ attr val added]]
                                          [(ident db attr) (if (ref? db attr)
                                                             (resolve-ref db val)
                                                             val) (if added :added :retracted)]))
                                   (sort-by last))})))
         (sort-by :timestamp)))

  (map
   first (d/find-all db :insurance.category.factor/category-factor-id '[*]))

  (d/find-by db :instrument.coverage/coverage-id #uuid "0186c248-07ba-82c0-bfda-237986a7573b" q/instrument-coverage-detail-pattern)

  ;; end
  )
