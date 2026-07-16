(ns app.members.ui
  (:require
   [app.form :as form]
   [app.ui2 :as ui2]
   [clojure.string :as str]
   [tick.core :as t]))

(defn current-travel-discount? [{:travel.discount/keys [expiry-date]}]
  (not (t/< (t/date expiry-date) (t/date))))

(defn travel-discount-name [discount]
  (get-in discount [:travel.discount/discount-type :travel.discount.type/discount-type-name]))

(defn travel-discount-badge
  ([discount]
   (travel-discount-badge discount (travel-discount-name discount)))
  ([discount label]
   (travel-discount-badge discount label nil))
  ([{:travel.discount/keys [discount-id expiry-date] :as discount} label id-suffix]
   (let [tooltip-id (ui2/safe-dom-id (str "travel-discount-"
                                          (or discount-id (hash [label expiry-date]))
                                          (when id-suffix (str "-" id-suffix))
                                          "-tooltip"))]
     [:span {:class "member-travel-discount-badge"}
      [:wa-badge {:id         tooltip-id
                  :appearance "outlined"
                  :pill       true
                  :variant    (if (current-travel-discount? discount) "success" "danger")}
       (or (some-> label str/trim not-empty) "—")]
      [:wa-tooltip {:for tooltip-id :placement "top"}
       [:i18n/tr :members/travel-discount-expires
        {:date (form/date-value expiry-date)}]]])))
