(ns app.insurance.coverage.queries
  (:require
   [app.datomic :as datomic]
   [app.insurance.domain :as domain]
   [app.queries :as q]
   [app.urls :as urls]))

(defn policy-editable?
  [{:insurance.policy/keys [status]}]
  (= status :insurance.policy.status/draft))

(defn image-uri
  [{:keys [system]} {:instrument/keys [instrument-id]} {:image/keys [image-id]}]
  (when image-id
    {:thumbnail (urls/absolute-link-instrument-image-thumbnail (:env system) instrument-id image-id)
     :full      (urls/absolute-link-instrument-image-full (:env system) instrument-id image-id)}))

(defn image-uris
  [req {:instrument/keys [images] :as instrument}]
  (keep #(image-uri req instrument %) images))

(defn coverage
  [db coverage-id]
  (when-let [coverage (q/retrieve-coverage db coverage-id)]
    (let [policy         (:insurance.policy/_covered-instruments coverage)
          coverage-types (:insurance.policy/coverage-types policy)]
      (first (domain/enrich-coverages policy coverage-types [coverage])))))

(defn coverage-history
  [db {:instrument.coverage/keys [coverage-id instrument]}]
  (let [instrument-id     (:instrument/instrument-id instrument)
        instrument-events (datomic/entity-history db :instrument/instrument-id instrument-id)
        coverage-events   (datomic/entity-history db :instrument.coverage/coverage-id coverage-id)]
    (->> (concat instrument-events coverage-events)
         (group-by :tx-id)
         vals
         (map (fn [txs]
                (reduce (fn [acc {:keys [audit changes timestamp tx-id]}]
                          (-> acc
                              (assoc :tx-id tx-id)
                              (assoc :timestamp timestamp)
                              (assoc :audit audit)
                              (update :changes concat changes)))
                        {}
                        txs)))
         (sort-by :timestamp)
         reverse)))
