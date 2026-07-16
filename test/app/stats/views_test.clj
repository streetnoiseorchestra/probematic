(ns app.stats.views-test
  (:require
   [app.stats.views :as views]
   [app.test-common :as tc]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.card :as card]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [jsonista.core :as j]
   [lookup.core :as l]
   [reitit.core :as r]))

(def translations
  {[:statistics/attendance-rate] "Attendance Rate (%)"
   [:statistics/gig-attendance]  "Gig Attendance"
   [:statistics/num-members]     "Members"
   [:statistics/probe-attendance] "Probe Attendance"})

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

(deftest cards-use-native-card-chassis
  (let [summary       (#'views/summary-card {})
        chart-section (views/charts-section {:tr tr} {:gig-histogram []
                                                      :probe-histogram []})
        chart         (nth chart-section 2)]
    (is (= [{:tag card/Card :class "stats-summary-card"}
            {:tag card/Card :class "stats-chart-card" :header-slot "header"}]
           [{:tag (first summary)
             :class (:class (l/attrs summary))}
            {:tag         (first chart)
             :class       (:class (l/attrs chart))
             :header-slot (:slot (l/attrs (l/select-one "div[slot=header]" chart)))}]))))

(deftest attendance-chart
  (testing "Gig and rehearsal attendance histograms contain three percentage bins."
    (let [view   (views/charts-section
                  {:tr tr}
                  {:gig-histogram   [{:x 0 :y 1} {:x 50 :y 2} {:x 100 :y 3}]
                   :probe-histogram [{:x 0 :y 4} {:x 50 :y 5} {:x 100 :y 6}]})
          chart  (l/select-one 'wa-chart view)
          config (chart-config view)
          effect (:data-effect (l/attrs chart))]
      (testing "One accessible attendance chart reacts to the combined JSON configuration signal."
        (is (= {:headings    [:statistics/attendance]
                :chart-count 1
                :chart       {:label           :statistics/attendance
                              :description     :statistics/methodology-histograms-body
                              :without-tooltip true}
                :reactive?   true}
               {:headings    (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                                   (l/select 'h2 view))
                :chart-count (count (l/select 'wa-chart view))
                :chart       {:label           (some-> (l/select-one :i18n/tr (:label (l/attrs chart)))
                                                       l/first-child)
                              :description     (some-> (l/select-one :i18n/tr (:description (l/attrs chart)))
                                                       l/first-child)
                              :without-tooltip (:without-tooltip (l/attrs chart))}
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

(deftest statistics-page-surface
  (testing "Statistics uses a standard read-only workspace and keeps timespan filters in its content."
    (let [{:keys [conn]} (tc/new-system "statistics-page-surface")
          view           (views/page {::r/router       (r/router ["/act" {:name :app.routes.datastar/act}])
                                      :current-locale :en
                                      :db             (d/db conn)
                                      :query-params   {}
                                      :tr             tr})
          surface        (l/select-one page-surface/PageSurface view)
          surface-attrs  (l/attrs surface)
          toolbar-attrs  (-> surface-attrs ::page-surface/toolbar l/attrs)
          breadcrumb     (::page-toolbar/breadcrumb toolbar-attrs)
          parent         (->> (l/select breadcrumb/BreadcrumbItem breadcrumb)
                              vec
                              butlast
                              last)
          header         (l/select-one page-header/PageHeader surface)
          timespans      (l/select button/Button (l/select-one 'wa-button-group surface))]
      (is (= {:width       :standard
              :breadcrumbs [:home :statistics/title]
              :mobile      {:href "/" :label :home}
              :toolbar-actions nil
              :heading     :statistics/title
              :subtitle    :statistics/current-range
              :timespans   [:statistics/last-three-months
                            :statistics/last-six-months
                            :statistics/last-year]}
             {:width       (or (::page-surface/width surface-attrs) :standard)
              :breadcrumbs (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                                 (l/select breadcrumb/BreadcrumbItem breadcrumb))
              :mobile      {:href  (-> parent l/attrs ::breadcrumb/href)
                            :label (some-> (l/select-one :i18n/tr parent) l/first-child)}
              :toolbar-actions (::page-toolbar/actions toolbar-attrs)
              :heading     (some-> header l/attrs ::page-header/title l/first-child)
              :subtitle    (some-> header l/attrs ::page-header/subtitle l/first-child)
              :timespans   (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                                 timespans)}))
      (is (not (contains? toolbar-attrs ::page-toolbar/mobile-back))))))
