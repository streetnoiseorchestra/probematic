(ns app.ui.input
  (:require [app.ui.core :as uic]
            [malli.experimental.lite :as l]))

(defn input
  {:opts {:label     :string
          :leading   (l/optional :any)
          :trailing  (l/optional :any)
          :variant   (l/optional [:enum :overlap :hidden])
          :required? (l/optional :boolean)}}
  [& args]
  (let [[opts attrs _children]      (uic/extract #'input args)
        class                       (:class attrs)
        {:keys [id] :as attrs}      (dissoc attrs :class)
        {:keys [label leading trailing  required?  variant]
         :or   {required? true
                variant   :hidden}} opts]

    (assert id "input requires :id")
    (when (= "null" (:value attrs))
      (throw (ex-info "Legacy behavior: would have put `nil` as input :value, but not anymore. Better fix caller" {:attrs attrs :opts opts})))
    [:div {:class (uic/cs class
                          (when (or trailing leading) "grid grid-cols-1"))}
     (when (= variant :overlap)
       [:label {:for id :class "absolute -top-2 left-2 inline-block rounded-lg bg-white px-1 text-xs font-medium text-gray-900"}
        label])
     [:input (uic/merge-attrs attrs
                              :id id
                              :aria-label (when (= variant :hidden) label)
                              :class       (uic/cs
                                            (when (or leading trailing) "col-start-1 row-start-1")
                                            (when leading "pl-10")
                                            (when trailing "pr-10")
                                            (when (= variant :hidden) "block w-full rounded-md bg-white px-3 py-1.5 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-sno-orange-600 sm:text-sm/6")
                                            (when (= variant :overlap) "block w-full rounded-md bg-white px-3 py-1.5 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-sno-orange-600 sm:text-sm/6"))
                              :required    required?)]
     (when leading
       (let [c "pointer-events-none col-start-1 row-start-1 ml-3 size-5 self-center text-gray-400 sm:size-4"]
         (when (vector? leading)
           (uic/add-class leading c))
         (when (fn? leading)
           (leading {:class c}))))
     (when trailing
       (let [c "pointer-events-none col-start-1 row-start-1 mr-3 size-5 self-center justify-self-end text-gray-400 sm:size-4"]
         (when (vector? trailing)
           (uic/add-class trailing c))
         (when (fn? trailing)
           (trailing {:class c}))))]))

(defn text [& {:as opts}]
  (input (assoc opts :type "text")))

(defn toggle-checkbox
  {:opts {:label (l/optional :string)
          ;; :checked? (l/optional :boolean)
          }}
  [& args]
  (let [[opts attrs _children] (uic/extract #'toggle-checkbox args)
        {:keys [id]}           attrs
        {:keys [label]}        opts]
    (assert id "toggle-checkbox requires :id")
    [:label {:for id :class "inline-flex relative items-center cursor-pointer"}
     [:input (uic/merge-attrs attrs :type "checkbox" :class "sr-only peer")]
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
        options                (:options opts)
        required?              (:required? opts)]
    [:div {:class "grid grid-cols-1"}
     [:select (uic/merge-attrs attrs {:id       id
                                      :required required?
                                      :class    "col-start-1 row-start-1 w-full appearance-none rounded-md bg-white py-1.5 pr-8 pl-3 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 focus:outline-2 focus:-outline-offset-2 focus:outline-sno-orange-600 sm:text-sm/6"})
      (for [{:keys [value label selected?]} options]
        [:option {:class "text-base text-gray-900" :selected (if (not (nil? selected?)) selected? (= value selected-value)) :value value} label])]
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
     [:div {:class "mt-2 gap-2 flex justify-end sm:justify-normal sm:mt-0 sm:flex-row-reverse"}
      (map #(uic/add-class % "w-full sm:w-auto") (rest children))]]))

(defn checkbox [& args]
  (let [[_opts attrs _children] (uic/extract nil args)]
    [:div {:class "flex h-6 shrink-0 items-center"}
     [:div {:class "group grid size-4 grid-cols-1"}
      [:input
       (uic/merge-attrs attrs
                        :type :checkbox
                        :class
                        "col-start-1 row-start-1 appearance-none rounded-sm border border-gray-300 bg-white checked:border-sno-orange-600 checked:bg-sno-orange-600 indeterminate:border-sno-orange-600 indeterminate:bg-sno-orange-600 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sno-orange-600 disabled:border-gray-300 disabled:bg-gray-100 disabled:checked:bg-gray-100 forced-colors:appearance-auto")]
      [:svg
       {:class
        "pointer-events-none col-start-1 row-start-1 size-3.5 self-center justify-self-center stroke-white group-has-disabled:stroke-gray-950/25",
        :viewBox "0 0 14 14",
        :fill    "none"}
       [:path
        {:class           "opacity-0 group-has-checked:opacity-100",
         :d               "M3 8L6 11L11 3.5",
         :stroke-width    "2",
         :stroke-linecap  "round",
         :stroke-linejoin "round"}]
       [:path
        {:class           "opacity-0 group-has-indeterminate:opacity-100",
         :d               "M3 7H11",
         :stroke-width    "2",
         :stroke-linecap  "round",
         :stroke-linejoin "round"}]]]]))
