(ns app.ui2.input
  (:require
   [app.ui2.core :as uic]))

(defn- split-attrs-and-children [args]
  (if (map? (first args))
    [(first args) (rest args)]
    [{} args]))

(defn toggle-checkbox
  [& args]
  (let [[attrs _children] (split-attrs-and-children args)
        {:keys [id]}      attrs]
    [:label {:for id :class "inline-flex relative items-center cursor-pointer"}
     [:input (uic/merge-attrs attrs :type "checkbox" :class "sr-only peer")]
     [:div {:class (uic/cs
                    "w-11 h-6 bg-gray-200 peer-focus:outline-hidden peer-focus:ring-4 peer-focus:ring-sno-orange-300 rounded-full peer"
                    "peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px]"
                    "after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all"
                    "peer-checked:bg-sno-orange-600")}]]))

(defn input-button
  [& args]
  (let [[attrs children] (split-attrs-and-children args)]
    [:div (uic/merge-attrs attrs :class "sm:flex sm:items-center")
     [:div {:class "w-full sm:max-w-xs"}
      (first children)]
     [:div {:class "mt-2 gap-2 flex justify-end sm:justify-normal sm:mt-0 sm:flex-row-reverse"}
      (map #(uic/add-class % "w-full sm:w-auto") (rest children))]]))
