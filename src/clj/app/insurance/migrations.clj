(ns app.insurance.migrations)

(defn plan-legacy-metadata
  [_db]
  {:tx-data                     []
   :migrated-coverage-type-count 0
   :configured-policy-count      0
   :incomplete-policies          []})
