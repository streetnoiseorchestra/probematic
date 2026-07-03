(ns app.stats.views-test
  (:require
   [app.html :as html]
   [app.stats.views :as views]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]))

(def translations
  {[:gig/attendance] "Attendance"
   [:stats/attendance-rate] "Attendance Rate (%)"
   [:stats/current-range] "%1 – %2"
   [:stats/gig-attendance] "Gig Attendance"
   [:stats/methodology-histograms-body] "The histograms show attendance-rate percentage bins on the x-axis and the number of members in each bin on the y-axis."
   [:stats/methodology-histograms-title] "Attendance Histograms"
   [:stats/num-members] "Members"
   [:stats/probe-attendance] "Probe Attendance"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce (fn [s [idx arg]]
             (str/replace s (str "%" (inc idx)) (str arg)))
           (tr path)
           (map-indexed vector args))))

(defn- occurrences [needle s]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote needle)) s)))

(deftest charts-section-renders-one-combined-wa-chart-with-reactive-json-config-signal
  (let [html (html/->str
              (#'views/charts-section
               tr
               {:gig-histogram   [{:x 0 :y 1} {:x 50 :y 2} {:x 100 :y 3}]
                :probe-histogram [{:x 0 :y 4} {:x 50 :y 5} {:x 100 :y 6}]}))]
    (is (= {:wa-chart-count             1
            :has-reactive-signal-wrapper true
            :has-combined-json-signal   true
            :effect-reads-signal        true
            :effect-assigns-config      true
            :uses-attendance-title      true
            :does-not-use-histogram-title true
            :includes-chartjs-config    true
            :includes-gig-dataset       true
            :includes-probe-dataset     true
            :uses-gig-token-color       true
            :uses-probe-token-color     true
            :no-hard-coded-gig-hex      true
            :no-hard-coded-probe-hex    true
            :no-separate-gig-signal     true
            :no-separate-probe-signal   true
            :no-canvas-elements         true
            :no-legacy-render-hook      true}
           {:wa-chart-count             (occurrences "<wa-chart" html)
            :has-reactive-signal-wrapper (str/includes? html "data-signals")
            :has-combined-json-signal   (str/includes? html "attendanceHistogramChartJson")
            :effect-reads-signal        (str/includes? html "$statsDashboard.attendanceHistogramChartJson")
            :effect-assigns-config      (str/includes? html "el.config = JSON.parse(chartConfigJson)")
            :uses-attendance-title      (str/includes? html ">Attendance</h2>")
            :does-not-use-histogram-title (not (str/includes? html ">Attendance Histograms</h2>"))
            :includes-chartjs-config    (and (str/includes? html "labels")
                                             (str/includes? html "datasets")
                                             (str/includes? html "scales"))
            :includes-gig-dataset       (str/includes? html "Gig Attendance")
            :includes-probe-dataset     (str/includes? html "Probe Attendance")
            :uses-gig-token-color       (str/includes? html "var(--wa-color-warning-fill-loud)")
            :uses-probe-token-color     (str/includes? html "var(--wa-color-success-fill-loud)")
            :no-hard-coded-gig-hex      (not (str/includes? html "#f97316"))
            :no-hard-coded-probe-hex    (not (str/includes? html "#22c55e"))
            :no-separate-gig-signal     (not (str/includes? html "gigHistogramChartJson"))
            :no-separate-probe-signal   (not (str/includes? html "probeHistogramChartJson"))
            :no-canvas-elements         (not (str/includes? html "<canvas"))
            :no-legacy-render-hook      (not (str/includes? html "SnoStatsCharts"))}))))

(deftest page-imports-wa-chart-and-drops-legacy-stats-chart-widget
  (let [{:keys [conn]} (tc/new-system "stats-views-page")
        html          (views/page {:current-locale :en
                                   :db             (d/db conn)
                                   :query-params   {}
                                   :tr             tr})]
    (is (= {:imports-wa-chart       true
            :wa-chart-count         1
            :no-stats-chart-widget  true}
           {:imports-wa-chart       (str/includes? html "import 'wa/components/chart/chart.js';")
            :wa-chart-count         (occurrences "<wa-chart" html)
            :no-stats-chart-widget  (not (str/includes? html "/js/widgets/stats-chart.js"))}))))
