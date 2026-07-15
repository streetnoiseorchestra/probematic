(ns app.datomic.migrations.post001-normalize-active-insurance-surveys
  "Normalizes legacy data to at most one active insurance survey.

  ## Why This Migration Exists

  The legacy application does not enforce the invariant that at most one
  insurance survey is active globally. This release establishes that
  invariant by normalizing existing survey rows after the complete survey
  schema is available.

  ## What It Does

  The migration finds unclosed surveys whose closing time is still in the
  future, retains the newest one, and emits `:insurance.survey/closed-at`
  datoms for every other active survey. Expired and already closed surveys are
  untouched.

  ## How It Runs

  Stork invokes [[tx-data]] after the canonical schema has synchronized. The
  producer captures one instant, orders candidates by creation time, survey
  UUID string, and Datomic entity ID, then Stork atomically records the marker
  with the returned closing datoms."
  (:require
   [datomic.api :as d]))

(defn- created-at-order
  "Compares surveys newest first, placing missing creation times last."
  [left right]
  (let [left-created-at  (:insurance.survey/created-at left)
        right-created-at (:insurance.survey/created-at right)]
    (cond
      (and left-created-at right-created-at)
      (compare right-created-at left-created-at)

      left-created-at
      -1

      right-created-at
      1

      :else
      0)))

(defn- survey-id-order
  "Compares survey UUID strings in ascending order, placing missing IDs last."
  [left right]
  (let [left-id  (:insurance.survey/survey-id left)
        right-id (:insurance.survey/survey-id right)]
    (cond
      (and left-id right-id)
      (compare (str left-id) (str right-id))

      left-id
      -1

      right-id
      1

      :else
      0)))

(defn- active-survey-order
  "Orders surveys by creation time, survey UUID, and Datomic entity ID."
  [left right]
  (let [created-order (created-at-order left right)]
    (if (zero? created-order)
      (let [id-order (survey-id-order left right)]
        (if (zero? id-order)
          (compare (:db/id left) (:db/id right))
          id-order))
      created-order)))

(defn tx-data
  "Returns transactions that retain only the newest active insurance survey."
  [conn]
  (let [db     (d/db conn)
        now    (java.util.Date.)
        active (->> (d/q '[:find [?survey ...]
                           :in $ ?now
                           :where
                           [?survey :insurance.survey/closes-at ?closes-at]
                           [(< ?now ?closes-at)]
                           [(missing? $ ?survey
                                      :insurance.survey/closed-at)]]
                         db now)
                    (map #(d/pull db
                                  [:db/id
                                   :insurance.survey/survey-id
                                   :insurance.survey/created-at]
                                  %))
                    (sort active-survey-order))]
    (mapv (fn [{:db/keys [id]}]
            [:db/add id :insurance.survey/closed-at now])
          (rest active))))
