(ns app.members.phone-number
  (:require [clojure.string :as str])
  (:import (com.google.i18n.phonenumbers NumberParseException PhoneNumberUtil PhoneNumberUtil$PhoneNumberFormat PhoneNumberUtil$PhoneNumberType)))

(defn instance ^PhoneNumberUtil []
  (PhoneNumberUtil/getInstance))

(defn- region->kw [r]
  (keyword (str/lower-case r)))

(def regions
  (into #::{}
        (map
         (juxt region->kw identity)
         (.getSupportedRegions (instance)))))

(defn parse-region [k]
  (when (and k (keyword? k))
    (assert (contains? regions k) (str "Region code " k " is not valid"))
    (get regions k)))

(defmacro try-parse-or-false
  [& body]
  `(try (or (do ~@body) false)
        (catch AssertionError        e# false)
        (catch NumberParseException  e# false)
        (catch NumberFormatException e# false)))

(def re-two-digits
  "Regular expression pattern matching at least 2 digits in a string."
  #".*\d.*\d.*")

(defn valid-input? [^String phone-number]
  (and (> (.length phone-number) 1)
       (some? (re-matches re-two-digits phone-number))))

(defn- parse-phone-number
  [phone-number region-code]
  (assert (valid-input? phone-number)
          "Phone number string should begin with at least 2 digits")
  (.parse ^PhoneNumberUtil (instance)
          phone-number
          (parse-region region-code)))

(defn possible? [^String n region-code]
  (->
   (instance)
   (.isPossibleNumber n (parse-region region-code))))

(defn valid?
  ([^String n region-code]
   (try-parse-or-false
    (.isValidNumber (instance)
                    (parse-phone-number n region-code)))))

(defn region-for-country-code [cc]
  (region->kw
   (.getRegionCodeForCountryCode (instance) cc)))

(defn normalize
  "Strips all non dialiable characters from the phone number string."
  [^String n]
  (PhoneNumberUtil/normalizeDiallableCharsOnly n))

(defn problem [^String n region-code]
  (keyword
   (str
    (.isPossibleNumberForTypeWithReason (instance) (parse-phone-number n region-code)
                                        PhoneNumberUtil$PhoneNumberType/MOBILE))))
(defn canonical
  "Returns the number formatted with the international + format"
  [^String n region-code]
  (if-let [pn (try-parse-or-false (parse-phone-number n region-code))]
    (.format (instance) pn PhoneNumberUtil$PhoneNumberFormat/INTERNATIONAL)
    (throw (ex-info "Invalid phone number" {:phone-number n
                                            :region-code  region-code
                                            :reason       (try-parse-or-false (problem n region-code))
                                            :type         :invalid-phone}))))
