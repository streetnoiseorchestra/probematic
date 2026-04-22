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

(defn select
  [& args]
  (let [[attrs _children] (split-attrs-and-children args)
        {:keys [id value options required?]} attrs
        selected-value     value]
    [:div {:class "grid grid-cols-1"}
     [:select (uic/merge-attrs (dissoc attrs :options :required?)
                               :id id
                               :required required?
                               :class "col-start-1 row-start-1 w-full appearance-none rounded-md bg-white py-1.5 pr-8 pl-3 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 focus:outline-2 focus:-outline-offset-2 focus:outline-sno-orange-600 sm:text-sm/6")
      (for [{:keys [value label selected?]} options]
        [:option {:class    "text-base text-gray-900"
                  :selected (if (some? selected?) selected? (= value selected-value))
                  :value    value}
         label])]
     [:svg {:class       "pointer-events-none col-start-1 row-start-1 mr-2 size-5 self-center justify-self-end text-gray-500 sm:size-4"
            :viewBox     "0 0 16 16"
            :fill        "currentColor"
            :aria-hidden "true"
            :data-slot   "icon"}
      [:path {:fill-rule "evenodd"
              :d         "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z"
              :clip-rule "evenodd"}]]]))

(defn input-button
  [& args]
  (let [[attrs children] (split-attrs-and-children args)]
    [:div (uic/merge-attrs attrs :class "sm:flex sm:items-center")
     [:div {:class "w-full sm:max-w-xs"}
      (first children)]
     [:div {:class "mt-2 gap-2 flex justify-end sm:justify-normal sm:mt-0 sm:flex-row-reverse"}
      (map #(uic/add-class % "w-full sm:w-auto") (rest children))]]))
