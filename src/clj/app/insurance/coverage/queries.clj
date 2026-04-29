(ns app.insurance.coverage.queries
  (:require
   [app.datomic :as datomic]
   [app.insurance.domain :as domain]
   [app.queries :as q]
   [app.urls :as urls]
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
   [java.io PushbackReader]))

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

(def member-pull
  [:member/member-id :member/username :member/name :member/nick])

;; Some imported audit refs still point at pre-import member entity ids.
;; The current database cannot resolve those refs, but the transaction export can map them back to member names.

(def legacy-member-attrs
  #{:member/member-id :member/username :member/name :member/nick})

(defn- reduce-legacy-member-datom
  [acc [eid attr value added?]]
  (if (legacy-member-attrs attr)
    (if added?
      (assoc-in acc [eid attr] value)
      (cond-> acc
        (= value (get-in acc [eid attr])) (update eid dissoc attr)))
    acc))

(defn- read-legacy-member-map*
  []
  (let [file (io/file "txns.edn")]
    (if (.exists file)
      (with-open [reader (PushbackReader. (io/reader file))]
        (loop [acc {}]
          (let [form (edn/read {:eof ::eof} reader)]
            (if (= ::eof form)
              (into {}
                    (keep (fn [[eid member]]
                            (when (:member/name member)
                              [eid (select-keys member member-pull)])))
                    acc)
              (recur (reduce reduce-legacy-member-datom acc (:data form)))))))
      {})))

(def legacy-member-map
  (memoize read-legacy-member-map*))

(defn- recover-audit-member
  [{:audit/keys [member user] :as audit}]
  (let [user-id (:db/id user)]
    (cond-> audit
      (and (nil? member) user-id) (assoc :audit/member ((legacy-member-map) user-id)))))

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
                              (assoc :audit (recover-audit-member audit))
                              (update :changes concat changes)))
                        {}
                        txs)))
         (sort-by :timestamp)
         reverse)))
