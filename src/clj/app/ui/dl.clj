(ns app.ui.dl
  (:require
   [malli.experimental.lite :as l]
   [app.ui.core :as uic]))

(defn dl
  [& args]
  (let [[opts attrs children] (uic/extract #'dl args)]
    [:dl (uic/merge-attrs attrs
                          :class "grid grid-cols-1 gap-x-4 gap-y-8 sm:grid-cols-3")
     children]))

(defn dt [& args]
  (let [[opts attrs children] (uic/easy-extract #'dt args "text-sm font-medium text-gray-500")]
    [:dt attrs children]))

(defn dd [& args]
  (let [[opts attrs children] (uic/easy-extract #'dd args "mt-1 text-sm text-gray-900")]
    [:dd attrs children]))

(defn wrapper
  {:opts {:span (l/optional :int)}}
  [& args]
  (let [[opts attrs children]       (uic/extract #'wrapper args)
        {:keys [span] :or {span 1}} opts
        $span                       (case span
                                      1 "sm:col-span-1"
                                      2 "sm:col-span-2"
                                      3 "sm:col-span-3")]
    [:div (uic/merge-attrs attrs :class (uic/cs $span))
     children]))

(defn item
  {:opts {:span  (l/optional :int)
          :label :any}}
  [& args]
  (let [[opts attrs children] (uic/extract #'item args)
        {:keys [span label]}  opts]
    (wrapper  (assoc attrs :-span span)
              (dt label)
              (dd children))))
