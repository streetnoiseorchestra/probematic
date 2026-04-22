(ns app.ui2.select
  (:require
   [app.ui2.core :as uic]
   [app.util :as util]
   [clojure.string :as str]))

(defn empty-option
  ([]
   (empty-option " - "))
  ([label]
   {:value "" :label label}))

(defn- member-display-name [{:member/keys [name nick]}]
  (if (str/blank? nick)
    name
    nick))

(defn select
  [& args]
  (let [[attrs _children] (if (map? (first args))
                            [(first args) (rest args)]
                            [{} args])
        {:keys [id value options required?]} attrs
        selected-value value]
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

(defn member-options [members & {:keys [full-names? with-empty-opt?]}]
  (concat
   (when with-empty-opt?
     [(empty-option)])
   (->> members
        (map (fn [m] {:value (:member/member-id m)
                      :label (if full-names?
                               (:member/name m)
                               (member-display-name m))}))
        (util/isort-by :label))))
