(ns app.insurance.queries
  (:require
   [app.insurance.domain :as domain]
   [app.util :as util]))

(defn coverages-grouped-by-owner
  [policy]
  (->> (:insurance.policy/covered-instruments policy)
       (util/group-by-into-list
        :coverages
        #(get-in % [:instrument.coverage/instrument :instrument/owner]))
       (mapv #(update % :coverages
                      (fn [coverages]
                        (sort-by (fn [coverage]
                                   (get-in coverage
                                           [:instrument.coverage/instrument
                                            :instrument/name]))
                                 coverages))))
       (mapv #(update % :coverages
                      (fn [coverages]
                        (domain/enrich-coverages
                         policy
                         (:insurance.policy/coverage-types policy)
                         coverages))))
       (mapv (fn [{:keys [coverages] :as member}]
               (assoc member :total (domain/sum-by coverages
                                                   :instrument.coverage/cost))))
       (sort-by :member/name)
       vec))
