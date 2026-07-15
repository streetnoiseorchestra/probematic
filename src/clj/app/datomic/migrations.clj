(ns app.datomic.migrations)

(def pre-schema-migrations
  "Ordered migrations applied before the canonical application schema."
  [{:id :app.migration/pre001-prepare-member-uniqueness
    :tx-data-fn
    'app.datomic.migrations.pre001-prepare-member-uniqueness/tx-data}])

(def post-schema-migrations
  "Ordered migrations applied after the canonical application schema."
  [{:id :app.migration/post001-normalize-active-insurance-surveys
    :tx-data-fn
    'app.datomic.migrations.post001-normalize-active-insurance-surveys/tx-data}])
