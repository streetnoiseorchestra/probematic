(ns app.form
  (:require
   [clojure.string :as str]
   [tick.core :as t]))

(defn trim-value
  "Returns `value` coerced to a trimmed string, or `nil` when `value` is nil."
  [value]
  (some-> value str str/trim))

(defn blank->nil
  "Returns nil when `value` is a blank string, otherwise returns `value`."
  [value]
  (if (and (string? value) (str/blank? value))
    nil
    value))

(defn optional-text
  "Returns `value` as trimmed text, or nil when blank."
  [value]
  (blank->nil (trim-value value)))

(defn normalize-bool
  "Normalizes common form boolean values.

  String values are true only when equal to `true`, ignoring case.
  Nil values use `default`, which is false when omitted."
  ([value]
   (normalize-bool value false))
  ([value default]
   (cond
     (true? value) true
     (false? value) false
     (string? value) (= "true" (str/lower-case value))
     (nil? value) default
     :else (boolean value))))

(defn text-value
  "Returns `value`, or an empty string when `value` is nil."
  [value]
  (or value ""))

(defn field-error
  "Returns a field error message from a form state map.

  Uses `:_error` by default.
  Pass `error-key` for forms that store errors elsewhere, such as `:error`."
  ([form-state field]
   (field-error form-state :_error field))
  ([form-state error-key field]
   (get-in form-state [error-key field :error])))

(defn update-present
  "Updates key `k` with `f` only when `m` already contains `k`."
  [m k f]
  (if (contains? m k)
    (update m k f)
    m))

(defn date-value
  "Returns `value` as a date input string, or nil when blank."
  [value]
  (some-> value t/date str))

(defn time-value
  "Returns `value` as a time input string, or nil when blank."
  [value]
  (some-> value str))

(defn parse-date
  "Parses `value` as a date, returning nil for blank or invalid values."
  [value]
  (when-let [value (optional-text value)]
    (try
      (t/date value)
      (catch Exception _
        nil))))

(defn parse-time
  "Parses `value` as a time, returning nil for blank or invalid values."
  [value]
  (when-let [value (optional-text value)]
    (try
      (t/time value)
      (catch Exception _
        nil))))

(defn signal-field
  "Returns the final signal path segment as a keyword."
  [signal]
  (some-> signal (str/split #"\.") last keyword))
