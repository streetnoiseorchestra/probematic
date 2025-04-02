(ns app.ui.input
  (:require
   [app.ui.core :as uic]
   [malli.experimental.lite :as l]))

(defn toggle-checkbox
  {:opts {:label    (l/optional :string)
          :id       (l/optional [:or :string :keyword])
          :name     (l/optional [:or :string :keyword])
          :checked? (l/optional :boolean)}}
  [& args]
  (let [[opts attrs children]            (uic/extract #'toggle-checkbox args)
        {:keys [label checked? id name]} opts]
    [:label {:for (or id name) :class "inline-flex relative items-center cursor-pointer"}
     [:input (uic/merge-attrs attrs :type "checkbox" :checked checked? :class "sr-only peer" :name name :id (or id name))]
     [:div {:class (uic/cs
                    ;; dark:peer-focus:ring-sno-orange-800 dark:bg-gray-700 dark:border-gray-600
                    "w-11 h-6 bg-gray-200 peer-focus:outline-hidden peer-focus:ring-4 peer-focus:ring-sno-orange-300  rounded-full peer"
                    " peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px]"
                    "after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all"
                    " peer-checked:bg-sno-orange-600")}]
     [:span {:class "ml-3 text-sm font-medium text-gray-900 "
             ;; dark:text-gray-300
             }
      label]]))
