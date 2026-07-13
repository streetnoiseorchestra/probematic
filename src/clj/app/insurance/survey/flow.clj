(ns app.insurance.survey.flow)

(def start-key :used)

(def steps
  {:used
   {:question :insurance/review-used-at-gig
    :answers  [{:id :no
                :label :insurance/review-no
                :next :go-private
                :decisions [:confirm-not-band]}
               {:id :yes
                :label :insurance/review-yes
                :next :keep-insured
                :decisions [:confirm-band]}]}

   :keep-insured
   {:question :insurance/review-keep-insured
    :answers  [{:id :no
                :label :insurance/review-no
                :next :confirm-band-removal}
               {:id :yes
                :label :insurance/review-yes
                :next :data-check
                :decisions [:confirm-keep-insured]}]}

   :confirm-band-removal
   {:question   :insurance/review-confirm-remove
    :secondary :insurance/review-confirm-remove-band-hint
    :answers    [{:id :keep
                  :label :insurance/review-keep-coverage
                  :next :data-check}
                 {:id :remove
                  :label :insurance/review-remove-coverage
                  :next :complete
                  :decisions [:remove-coverage]}]}

   :go-private
   {:question   :insurance/review-pay-to-keep
    :secondary :insurance/review-pay-to-keep-hint
    :answers    [{:id :stop
                  :label :insurance/review-stop-coverage
                  :next :confirm-private-removal}
                 {:id :pay
                  :label :insurance/review-pay
                  :next :data-check
                  :decisions [:confirm-keep-insured]}]}

   :confirm-private-removal
   {:question :insurance/review-confirm-remove
    :answers  [{:id :keep
                :label :insurance/review-keep-coverage
                :next :confirm-go-private}
               {:id :remove
                :label :insurance/review-remove-coverage
                :next :complete
                :decisions [:remove-coverage]}]}

   :confirm-go-private
   {:question :insurance/review-confirm-private-cost
    :answers  [{:id :no
                :label :insurance/review-no
                :next :go-private}
               {:id :pay
                :label :insurance/review-pay
                :next :data-check
                :decisions [:confirm-keep-insured]}]}

   :data-check
   {:question   :insurance/review-data-correct
    :secondary :insurance/review-data-correct-hint
    :answers    [{:id :no
                  :label :insurance/review-no
                  :next :data-edit}
                 {:id :yes
                  :label :insurance/review-yes
                  :next :complete
                  :decisions [:confirm-data-ok]}]}})

(defn transition [step-key answer-id]
  (some #(when (= answer-id (:id %)) %)
        (get-in steps [step-key :answers])))
