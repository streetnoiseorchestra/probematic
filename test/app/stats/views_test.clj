(ns app.stats.views-test
  (:require
   [app.stats.views :as views]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as j]
   [lookup.core :as l]))

(def translations
  {[:gig/attendance]                   "Attendance"
   [:stats/attendance-rate]            "Attendance Rate (%)"
   [:stats/gig-attendance]             "Gig Attendance"
   [:stats/methodology-histograms-body] "The histograms show attendance-rate percentage bins on the x-axis and the number of members in each bin on the y-axis."
   [:stats/num-members]                "Members"
   [:stats/probe-attendance]           "Probe Attendance"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce (fn [s [idx arg]]
             (str/replace s (str "%" (inc idx)) (str arg)))
           (tr path)
           (map-indexed vector args))))

(defn chart-config [view]
  (let [signals (-> (l/select-one "section[data-signals]" view)
                    l/attrs
                    :data-signals
                    j/read-value)]
    (-> (get-in signals ["statsDashboard" "attendanceHistogramChartJson"])
        j/read-value)))

(deftest attendance-chart
  (testing "Gig and rehearsal attendance histograms contain three percentage bins."
    (let [view   (views/charts-section
                  tr
                  {:gig-histogram   [{:x 0 :y 1} {:x 50 :y 2} {:x 100 :y 3}]
                   :probe-histogram [{:x 0 :y 4} {:x 50 :y 5} {:x 100 :y 6}]})
          chart  (l/select-one 'wa-chart view)
          config (chart-config view)
          effect (:data-effect (l/attrs chart))]
      (testing "One accessible attendance chart reacts to the combined JSON configuration signal."
        (is (= {:headings    ["Attendance"]
                :chart-count 1
                :chart       {:label           "Attendance"
                              :description     "The histograms show attendance-rate percentage bins on the x-axis and the number of members in each bin on the y-axis."
                              :without-tooltip true}
                :reactive?   true}
               {:headings    (mapv l/text (l/select 'h2 view))
                :chart-count (count (l/select 'wa-chart view))
                :chart       (select-keys (l/attrs chart)
                                          [:label :description :without-tooltip])
                :reactive?   (and (str/includes? effect
                                                 "$statsDashboard.attendanceHistogramChartJson")
                                  (str/includes? effect
                                                 "el.config = JSON.parse(chartConfigJson)"))})))
      (testing "The chart plots gig and rehearsal counts as separate colored datasets."
        (is (= {:labels   ["0%" "50%" "100%"]
                :datasets [{"label"           "Gig Attendance"
                            "data"            [1 2 3]
                            "backgroundColor" "var(--wa-color-warning-fill-loud)"}
                           {"label"           "Probe Attendance"
                            "data"            [4 5 6]
                            "backgroundColor" "var(--wa-color-success-fill-loud)"}]
                :axes     ["Attendance Rate (%)" "Members"]}
               {:labels   (get-in config ["data" "labels"])
                :datasets (mapv #(select-keys % ["label" "data" "backgroundColor"])
                                (get-in config ["data" "datasets"]))
                :axes     [(get-in config ["options" "scales" "x" "title" "text"])
                           (get-in config ["options" "scales" "y" "title" "text"])]}))))))
