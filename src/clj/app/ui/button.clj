(ns app.ui.button
  (:require [app.ui :as ui]
            [app.ui.core :as uic]
            [malli.experimental.lite :as l]))

(def button-priority-classes {:link             "font-semibold text-sno-orange-600 hover:text-sno-orange-500"
                              :link-destructive "font-semibold text-red-600 hover:text-red-500"
                              :success
                              "border-transparent bg-sno-green-600 text-white shadow-xs hover:bg-sno-green-700 focus:outline-hidden focus:ring-2 focus:ring-sno-green-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :secondary
                              "border-transparent bg-sno-orange-100 px-4 py-2  text-sno-orange-700 hover:bg-sno-orange-200 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2"
                              :white
                              "border-gray-300 bg-white px-4 py-2 text-sm  text-gray-700 shadow-xs hover:bg-gray-50 focus:outline-hidden focus:ring-2 focus:ring-sno-green-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :white-destructive
                              "border-red-300 bg-white px-4 py-2 text-sm  text-red-600 shadow-xs hover:bg-red-50 focus:outline-hidden focus:ring-2 focus:ring-red-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :primary
                              "border-transparent bg-sno-orange-600 text-white shadow-xs hover:bg-sno-orange-700 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :primary-orange
                              "border-transparent bg-orange-600 text-white shadow-xs hover:bg-orange-700 focus:outline-hidden focus:ring-2 focus:ring-orange-500 focus:ring-offset-2 focus:ring-offset-gray-100"
                              :white-rounded    "rounded-full border border-gray-300 bg-white text-gray-700 shadow-xs hover:bg-gray-50"})

(def spinner-priority-classes {:secondary         "text-sno-orange-900"
                               :success           "text-sno-green-700"
                               :white             "text-sno-green-700"
                               :white-destructive "text-sno-orange-900"
                               :primary           "text-white"
                               :primary-orange    "text-white"
                               :white-rounded     "text-sno-orange-900"})

(def button-sizes-classes {:2xsmall "px-1.5 py-0.5 text-xs"
                           :xsmall  "px-2.5 py-1.5 text-xs"
                           :small   "px-3 py-2 text-sm leading-4"
                           :normal  "px-4 py-2 text-sm"
                           :large   "px-4 py-2 text-base"
                           :xlarge  "px-6 py-3 text-base"})

(def button-icon-sizes-classes {:xsmall "size-3"
                                :small  "size-4"
                                :normal "size-5"
                                :large  "size-5"
                                :xlarge "size-5"})

(defn wrap-text-node [children]
  (if (and (string? (first children)) (not= \< (first children)))
    [:span children]
    children))

(defn button
  {:opts {:size          (l/optional [:enum :2xsmall :xsmall :small :normal :large :xlarge])
          :priority      (l/optional (into [:enum] (keys button-priority-classes)))
          :disabled?     (l/optional :boolean)
          :icon          (l/optional fn?)
          :icon-trailing (l/optional fn?)
          :centered?     (l/optional :boolean)}}
  [& args]
  (let [[opts attrs children]     (uic/extract #'button args)
        {:keys [size priority centered? disabled? icon icon-trailing]
         :or   {size     :normal
                priority :white}} opts
        classes                   (uic/cs
                                   "btn-new"
                                   (when-not (#{:link :link-destructive} priority)
                                     "inline-flex items-center gap-x-1.5 rounded-md border font-semibold")
                                   ;; "inline-flex items-center border font-medium"
                                   ;; "inline-flex items-center rounded-md border"
                                   (size button-sizes-classes)
                                   (priority button-priority-classes)
                                   (when centered? "items-center justify-center")
                                   (when (and disabled? (= :tag :a)) "opacity-50 cursor-not-allowed")
                                   "disabled:opacity-50 disabled:cursor-not-allowed")]
    [:button (uic/merge-attrs attrs
                              :class classes)
     [:svg {:class (ui/cs  "spinner me-2 animate-spin"
                           (size button-icon-sizes-classes)
                           (priority spinner-priority-classes))} [:use {:href "#svg-sprite-spinner"}]]

     (when icon
       (icon {:class (uic/cs "button-icon" (size button-icon-sizes-classes)  "-ml-0.5") :aria-hidden true}))
     (wrap-text-node children)
     (when icon-trailing
       (icon-trailing {:class (uic/cs "button-icon" (size button-icon-sizes-classes)  "-mr-0.5") :aria-hidden true}))]))

(defn icon-button [& args])

#_(second
   (button (array-map :-size :large :data-signals "first" :data-on-click "wow" :class "wow" :a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9 :j 10 :k 11 :l 12 :m 13 :n 14 :o 15 :p 16 :q 17 :r 18 :s 19 :t 20 :u 21 :v 22 :w 23) "Foo"))

#_(defn testing [& {:as opts}]
    (type opts))
#_(testing :much :wow :a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9 :j 10 :k 11 :l 12 :m 13 :n 14 :o 15 :p 16 :q 17 :r 18 :s 19 :t 20 :u 21 :v 22 :w 23)
;; => clojure.lang.PersistentArrayMap

#_(testing :a 1)
