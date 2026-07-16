(ns app.stats.charts
  "Builds localized Chart.js configuration data for the statistics page.

  Chart configuration is serialized before it enters the Hiccup view, so this
  namespace resolves the translated labels that must be concrete strings."
  (:require
   [jsonista.core :as j]))

(defn- histogram-values-by-bin [values]
  (into {} (map (juxt :x :y)) values))

(defn- histogram-bins [gig-histogram probe-histogram]
  (->> (concat gig-histogram probe-histogram)
       (map :x)
       distinct
       sort
       vec))

(defn- histogram-bin-label [bin]
  (str bin "%"))

(defn- histogram-dataset [label color values-by-bin bins]
  {:label           label
   :data            (mapv #(get values-by-bin % 0) bins)
   :backgroundColor color
   :borderColor     color
   :borderRadius    4})

(defn- attendance-histogram-chart-config
  [{:keys [gig-histogram gig-title probe-histogram probe-title x-axis-label y-axis-label]}]
  (let [bins            (histogram-bins gig-histogram probe-histogram)
        gig-values      (histogram-values-by-bin gig-histogram)
        probe-values    (histogram-values-by-bin probe-histogram)
        gig-dataset     (histogram-dataset gig-title
                                           "var(--wa-color-warning-fill-loud)"
                                           gig-values
                                           bins)
        probe-dataset   (histogram-dataset probe-title
                                           "var(--wa-color-success-fill-loud)"
                                           probe-values
                                           bins)
        dataset-options {:barPercentage      0.85
                         :categoryPercentage 0.75}]
    {:type    "bar"
     :data    {:labels   (mapv histogram-bin-label bins)
               :datasets [(merge gig-dataset dataset-options)
                          (merge probe-dataset dataset-options)]}
     :options {:responsive          true
               :maintainAspectRatio false
               :font                {:size 16}
               :interaction         {:mode "index" :intersect false}
               :scales              {:x {:title {:display true
                                                 :text    x-axis-label}
                                         :grid  {:display false}
                                         :ticks {:padding 0
                                                 :font    {:size 14}}}
                                     :y {:title      {:display true
                                                      :padding 0
                                                      :text    y-axis-label}
                                         :ticks      {:padding   0
                                                      :precision 0
                                                      :font      {:size 14}}
                                         :beginAtZero true}}
               :plugins             {:tooltip {:enabled false}
                                     :legend  {:position "bottom"
                                               :labels   {:font {:size 16}}}}}}))

(defn attendance-histogram-signals
  "Returns localized Chart.js configuration serialized as Datastar signals."
  [{:keys [tr]} {:keys [gig-histogram probe-histogram]}]
  (let [config (attendance-histogram-chart-config
                {:gig-histogram   gig-histogram
                 :gig-title       (tr [:statistics/gig-attendance])
                 :probe-histogram probe-histogram
                 :probe-title     (tr [:statistics/probe-attendance])
                 :x-axis-label    (tr [:statistics/attendance-rate])
                 :y-axis-label    (tr [:statistics/num-members])})]
    {:statsDashboard
     {:attendanceHistogramChartJson (j/write-value-as-string config)}}))
