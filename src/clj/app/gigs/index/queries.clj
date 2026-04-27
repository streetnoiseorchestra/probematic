(ns app.gigs.index.queries
  (:require
   app.gigs.service
   [app.queries :as q]))

(def past-gigs-limit 20)

(defn page-data [db]
  {:future-gigs (q/gigs-future db)
   :past-gigs   (app.gigs.service/gigs-past-page db 0 past-gigs-limit)})
