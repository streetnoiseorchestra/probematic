(ns app.insurance.policy.notifications.queries
  (:require
   [app.insurance.domain :as domain]
   [app.insurance.queries :as insurance.queries]
   [app.queries :as q]
   [tick.core :as t]))

(defn- member-payment-data
  [{:keys [coverages] :as member}]
  (let [private-coverages (filterv :instrument.coverage/private? coverages)]
    (when (seq private-coverages)
      (let [unavailable-coverages
            (filterv :instrument.coverage/missing-category-factor?
                     private-coverages)
            private-cost-total
            (-> (or (domain/sum-by private-coverages
                                   :instrument.coverage/cost)
                    0M)
                double
                (* 100)
                Math/round
                int)]
        {:member                         member
         :private-coverages              private-coverages
         :count-private                  (count private-coverages)
         :private-cost-total             private-cost-total
         :unavailable-private-cost-count (count unavailable-coverages)
         :private-costs-available?       (empty? unavailable-coverages)
         :missing-category-names
         (->> unavailable-coverages
              (keep #(get-in % [:instrument.coverage/instrument
                                :instrument/category
                                :instrument.category/name]))
              distinct
              sort
              vec)}))))

(defn notification-data
  [db policy-id current-member-id]
  (let [{:insurance.policy/keys [effective-at effective-until] :as policy}
        (q/retrieve-policy db policy-id)
        current-member (when current-member-id
                         (q/retrieve-member db current-member-id))
        authorized? (boolean (and current-member
                                  (q/insurance-team-member? db current-member)))]
    {:policy      policy
     :time-range  (format "%s - %s" (t/year effective-at) (t/year effective-until))
     :sender-name (:member/name current-member)
     :authorized? authorized?
     :members-data (if authorized?
                     (->> (insurance.queries/coverages-grouped-by-owner policy)
                          (keep member-payment-data)
                          vec)
                     [])}))

(defn select-members
  [members-data member-ids]
  (let [selected (filterv #(contains? member-ids
                                      (get-in % [:member :member/member-id]))
                          members-data)]
    {:to-send     (filterv :private-costs-available? selected)
     :unavailable (filterv (complement :private-costs-available?) selected)}))
