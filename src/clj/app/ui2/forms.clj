(ns app.ui2.forms
  (:require
   [medley.core :as medley]))

(defn untouched-fields
  "Get all field keys that have not been touched."
  [form-key signals]
  (keys (medley/filter-vals #(== % 0) (get-in signals [form-key :touched]))))

(defn touched-fields
  "Get all field keys that have been touched."
  [form-key signals]
  (keys (medley/filter-vals #(> % 0) (get-in signals [form-key :touched]))))

(defn all-fields
  "Get all field keys for a form."
  [form-key signals]
  (keys (get-in signals [form-key :touched])))

(defn merge-errors
  "Returns a Nexus effect vector that merges form errors into Datastar signals."
  ([signals form-key error]
   (merge-errors signals form-key error nil))
  ([signals form-key error {:keys [only] :or {only :all}}]
   (let [_top   (:malli/error error (:_top error))
         fields (-> (if (= only :touched)
                      (touched-fields form-key signals)
                      (all-fields form-key signals))
                    (zipmap (repeat nil))
                    (assoc :_top _top))]
     [[:app.datastar/merge-signals {form-key {:error (merge fields error)}}]])))

(defn clear-form-errors
  "Returns an effect vector that clears form errors."
  [signals form-key]
  (merge-errors signals form-key nil))

(defn remove-untouched-errors
  "Remove validation errors for untouched fields."
  [form-key signals validation-result]
  (let [errors (apply dissoc validation-result (untouched-fields form-key signals))]
    (when errors
      errors)))

(defn values-from-signals
  "Get the selected form field values from a signals map."
  [fields form-key signals]
  (-> signals form-key (select-keys fields)))
