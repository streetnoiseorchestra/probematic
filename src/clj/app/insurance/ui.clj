(ns app.insurance.ui
  (:require
   [app.ui2 :as ui2]))

(def todo-metric-order
  [:needs-review :changed :new :removed])

(def todo-metric-data
  {:needs-review {:icon-name   "circle-question-outline"
                  :tooltip-key [:insurance/total-needs-review-tooltip]}
   :changed      {:icon-name   "circle-exclamation"
                  :tooltip-key [:insurance/total-total-changed-tooltip]}
   :new          {:icon-name   "circle-plus-solid"
                  :tooltip-key [:insurance/total-total-new-tooltip]}
   :removed      {:icon-name   "circle-xmark"
                  :tooltip-key [:insurance/total-total-removed-tooltip]}})

(defn todo-metric
  [tr {:keys [class-prefix count id-prefix policy-id status]}]
  (when (pos? (or count 0))
    (let [{:keys [icon-name tooltip-key]} (todo-metric-data status)
          status-name                     (name status)
          metric-id                       (str id-prefix
                                               "-"
                                               (ui2/safe-dom-id policy-id)
                                               "-"
                                               status-name)]
      (list
       [:span {:id    metric-id
               :class (ui2/cs (str class-prefix "-metric")
                              (str class-prefix "-metric--" status-name))}
        [:wa-icon {:library "snoico"
                   :name    icon-name}]
        [:span {:class (str class-prefix "-count")} count]]
       [:wa-tooltip {:for metric-id}
        (tr tooltip-key)]))))
