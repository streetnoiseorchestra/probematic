(ns app.insurance.policy.dashboard.chart
  (:require
   [app.ui2 :as ui2]
   [jsonista.core :as j]))

(def colors
  {:band    "var(--wa-color-success-fill-loud)"
   :private "var(--wa-color-warning-fill-loud)"})

(defn- round-up-to
  [value step]
  (* step (long (Math/ceil (/ (double value) step)))))

(defn- rounded-count-max
  [value]
  (let [maximum (max 0.0 (double (or value 0)))]
    (cond
      (zero? maximum) 10
      (<= maximum 20) (round-up-to maximum 5)
      (<= maximum 100) (round-up-to maximum 25)
      :else (round-up-to maximum 100))))

(defn- rounded-money-max
  [value]
  (let [maximum (max 0.0 (double (or value 0)))]
    (if (zero? maximum)
      10
      (let [power       (Math/pow 10 (Math/floor (Math/log10 maximum)))
            scaled      (/ maximum power)
            nice-scaled (cond
                          (<= scaled 1) 1
                          (<= scaled 2) 2
                          (<= scaled 2.5) 2.5
                          (<= scaled 5) 5
                          :else 10)]
        (* nice-scaled power)))))

(defn- total
  [values]
  (reduce + 0M (map #(or % 0M) values)))

(defn- dataset
  [{:keys [axis-id color data label measure]}]
  {:label              label
   :data               data
   :measure            measure
   :xAxisID            axis-id
   :backgroundColor    color
   :borderColor        "transparent"
   :borderSkipped      false
   :borderWidth        0
   :borderRadius       4
   :barPercentage      0.7
   :categoryPercentage 0.75
   :stack              measure})

(defn- data
  [tr currency {:keys [band-count band-cost private-count private-cost]}]
  (let [band-count    (or band-count 0)
        private-count (or private-count 0)
        band-cost     (or band-cost 0M)
        private-cost  (or private-cost 0M)
        counts        [band-count private-count]
        costs         [band-cost private-cost]
        count-label   (tr [:insurance/item-count])
        cost-label    (tr [:insurance/cost])
        band-label    (tr [:insurance/dashboard-band-instruments])
        private-label (tr [:insurance/dashboard-private-instruments])
        currency-code (or (some-> currency name) "EUR")
        cost-title    (str cost-label " " (ui2/currency-symbol currency))]
    {:type    "bar"
     :data    {:labels   [count-label cost-label]
               :datasets [(dataset {:label   band-label
                                    :data    [band-count nil]
                                    :measure "count"
                                    :axis-id "count"
                                    :color   (:band colors)})
                          (dataset {:label   private-label
                                    :data    [private-count nil]
                                    :measure "count"
                                    :axis-id "count"
                                    :color   (:private colors)})
                          (dataset {:label   band-label
                                    :data    [nil band-cost]
                                    :measure "cost"
                                    :axis-id "cost"
                                    :color   (:band colors)})
                          (dataset {:label   private-label
                                    :data    [nil private-cost]
                                    :measure "cost"
                                    :axis-id "cost"
                                    :color   (:private colors)})]}
     :options {:indexAxis           "y"
               :responsive          true
               :maintainAspectRatio false
               :animation           false
               :interaction         {:mode "index" :intersect false}
               :plugins             {:legend  {:display false}
                                     :tooltip {:enabled false}}
               :scales              {:count {:type         "linear"
                                             :axis         "x"
                                             :position     "top"
                                             :stacked      true
                                             :beginAtZero  true
                                             :suggestedMax (rounded-count-max (total counts))
                                             :title        {:display true
                                                            :text    count-label}
                                             :ticks        {:precision 0}
                                             :grid         {:drawOnChartArea false}}
                                     :cost  {:type         "linear"
                                             :axis         "x"
                                             :position     "bottom"
                                             :stacked      true
                                             :beginAtZero  true
                                             :suggestedMax (rounded-money-max (total costs))
                                             :title        {:display true
                                                            :text    cost-title}
                                             :ticks        {:format {:style                 "currency"
                                                                     :currency              currency-code
                                                                     :maximumFractionDigits 0}}}
                                     :x     {:display false
                                             :grid    {:display false}}
                                     :y     {:stacked true
                                             :grid    {:display false}}}}}))

(defn signals
  [tr currency totals]
  {:insuranceDashboard
   {:coverageMixChartJson
    (j/write-value-as-string (data tr currency totals))}})
