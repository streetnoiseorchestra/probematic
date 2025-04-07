(ns app.ui.input
  (:require
   [app.ui.core :as uic]
   [malli.experimental.lite :as l]))

(defn input
  {:opts {:label     :string
          :suffix    (l/optional :any)
          :variant   (l/optional [:enum :overlap :hidden])
          :required? (l/optional :boolean)}}
  [& args]
  (let [[opts attrs _children]      (uic/extract #'input args)
        class                       (:class attrs)
        {:keys [id] :as attrs}      (dissoc attrs :class)
        {:keys [label suffix required?  variant]
         :or   {required? true
                variant   :hidden}} opts]

    (assert id "input requires :id")
    (when (= "null" (:value attrs))
      (throw (ex-info "Legacy behavior: would have put `nil` as input :value, but not anymore. Better fix caller" {:attrs attrs :opts opts})))
    (assert (nil? suffix) "Suffix not yet implemented")
    [:div {:class (uic/cs class)}
     (when (= variant :overlap)
       [:label {:for id :class "absolute -top-2 left-2 inline-block rounded-lg bg-white px-1 text-xs font-medium text-gray-900"}
        label])
     [:input (uic/merge-attrs attrs
                              :id id
                              :aria-label (when (= variant :hidden) label)
                              :class       (uic/cs
                                            (when (= variant :hidden) "block w-full rounded-md bg-white px-3 py-1.5 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-sno-orange-600 sm:text-sm/6")
                                            (when (= variant :overlap) "block w-full rounded-md bg-white px-3 py-1.5 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-sno-orange-600 sm:text-sm/6"))
                              :required    required?)]]))

(defn text [& {:as opts}]
  (input (assoc opts :type "text")))

(defn toggle-checkbox
  {:opts {:label    (l/optional :string)
          :checked? (l/optional :boolean)}}
  [& args]
  (let [[opts attrs _children]   (uic/extract #'toggle-checkbox args)
        {:keys [id]}             attrs
        {:keys [label checked?]} opts]
    (assert id "toggle-checkbox requires :id")
    [:label {:for id :class "inline-flex relative items-center cursor-pointer"}
     [:input (uic/merge-attrs attrs :type "checkbox" :checked checked? :class "sr-only peer")]
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

(defn select
  {:opts {:required? (l/optional :boolean)
          :options   :any}}
  [& args]
  (let [[opts attrs _children] (uic/extract #'select args)
        id                     (:id attrs)
        selected-value         (:value opts)
        options                (:options opts)]
    [:div {:class "grid grid-cols-1"}
     [:select (uic/merge-attrs attrs uic/merge-attrs attrs {:id    id
                                                            :class "col-start-1 row-start-1 w-full appearance-none rounded-md bg-white py-1.5 pr-8 pl-3 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 focus:outline-2 focus:-outline-offset-2 focus:outline-sno-orange-600 sm:text-sm/6"})
      (for [{:keys [value label selected?]} options]
        [:option {:selected (if (not (nil? selected?)) selected? (= value selected-value)) :value value} label])]
     [:svg
      {:class
       "pointer-events-none col-start-1 row-start-1 mr-2 size-5 self-center justify-self-end text-gray-500 sm:size-4",
       :viewBox     "0 0 16 16",
       :fill        "currentColor",
       :aria-hidden "true",
       :data-slot   "icon"}
      [:path
       {:fill-rule "evenodd",
        :d
        "M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z",
        :clip-rule "evenodd"}]]]))

(defn input-button
  [& args]
  (let [[_opts attrs children] (uic/extract #'input-button args)]
    [:div (uic/merge-attrs attrs :class "sm:flex sm:items-center")
     [:div {:class "w-full sm:max-w-xs"}
      (first children)]
     [:div {:class "sm:flex sm:flex-row-reverse"}
      (map #(uic/add-class % "sm:ml-3 sm:w-auto") (rest children))]]))
