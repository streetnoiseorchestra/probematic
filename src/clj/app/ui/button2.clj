(ns app.ui.button2
  (:require [app.ui.core :as uic]
            [malli.experimental.lite :as l]))

(def button-sizes
  {:xxsmall {:classes   "rounded-sm px-2 py-1 text-xs"
             :gap       "gap-x-1.5"
             :icon-size "size-3"}
   :xsmall  {:classes   "rounded-sm px-2 py-1 text-sm"
             :gap       "gap-x-1.5"
             :icon-size "size-4"}
   :small   {:classes   "rounded-md px-2.5 py-1.5 text-sm"
             :gap       "gap-x-1.5"
             :icon-size "size-5"}
   :normal  {:classes   "rounded-md px-3 py-2 text-sm"
             :gap       "gap-x-1.5"
             :icon-size "size-5"}
   :large   {:classes   "rounded-md px-3.5 py-2.5 text-sm"
             :gap       "gap-x-2"
             :icon-size "size-5"}})

(def button-priorities
  {:primary               {:classes       "bg-sno-orange-600 text-white shadow-xs hover:bg-sno-orange-500 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sno-orange-600"
                           :dark          "dark:bg-sno-orange-500 dark:hover:bg-sno-orange-400 dark:focus-visible:outline-sno-orange-500"
                           :spinner-color "text-white"}
   :secondary             {:classes       "bg-white text-gray-900 ring-1 ring-gray-300 ring-inset shadow-xs hover:bg-gray-50"
                           :dark          "dark:bg-white/10 dark:text-white dark:ring-0 dark:hover:bg-white/20"
                           :spinner-color "text-gray-900"}
   :secondary-destructive {:classes       "bg-white text-red-600 ring-1 ring-red-300 ring-inset shadow-xs hover:bg-red-50"
                           :dark          "dark:bg-white/10 dark:text-red-400 dark:ring-0 dark:hover:bg-red-900/20"
                           :spinner-color "text-red-600"}
   :link                  {:classes       "text-sno-orange-600 hover:text-sno-orange-500 underline-offset-4 hover:underline"
                           :dark          "dark:text-sno-orange-400 dark:hover:text-sno-orange-300"
                           :spinner-color "text-sno-orange-600"
                           :no-border     true}
   :link-destructive      {:classes       "text-red-600 hover:text-red-500 underline-offset-4 hover:underline"
                           :dark          "dark:text-red-400 dark:hover:text-red-300"
                           :spinner-color "text-red-600"
                           :no-border     true}})

(defn wrap-text-node [children]
  (if (and (string? (first children)) (not= \< (first children)))
    [:span children]
    children))

(defn button
  {:opts {:size          (l/optional [:enum :xxsmall :xsmall :small :normal :large])
          :priority      (l/optional [:enum :primary :secondary :secondary-destructive :link :link-destructive])
          :disabled?     (l/optional :boolean)
          :loading?      (l/optional :boolean)
          :icon          (l/optional fn?)
          :icon-trailing (l/optional fn?)
          :centered?     (l/optional :boolean)}}
  [& args]
  (let [[opts attrs children]             (uic/extract #'button args)
        {:keys [size priority centered? disabled? loading? icon icon-trailing]
         :or   {size     :normal
                priority :secondary
                loading? false}}          opts
        {:keys [type] :or {type :button}} attrs
        size-data                         (get button-sizes size)
        priority-data                     (get button-priorities priority)
        classes                           (uic/cs
                                           "font-semibold"
                                           (:classes size-data)
                                           (:classes priority-data)
                                           (:dark priority-data)
                                           (when (or icon icon-trailing loading?) "inline-flex items-center")
                                           (when (or icon icon-trailing) (:gap size-data))
                                           (when centered? "items-center justify-center")
                                           (when disabled? "opacity-50 cursor-not-allowed")
                                           "disabled:opacity-50 disabled:cursor-not-allowed")]
    [:button (uic/merge-attrs attrs
                              :type type
                              :class classes)
     (when loading?
       [:svg {:class (uic/cs "spinner me-2 animate-spin"
                             (:icon-size size-data)
                             (:spinner-color priority-data))}
        [:use {:href "#svg-sprite-spinner"}]])
     (when (and icon (not loading?))
       (icon {:class (uic/cs "button-icon" (:icon-size size-data) "-ml-0.5") :aria-hidden true}))
     (wrap-text-node children)
     (when icon-trailing
       (icon-trailing {:class (uic/cs "button-icon" (:icon-size size-data) "-mr-0.5") :aria-hidden true}))]))
