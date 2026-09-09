(ns app.schemas.vat
  (:require
   [medley.core :as m]
   [clojure.string :as str]))

(def vat-number-prefixes
  #{"AT"
    "BE"
    "BG"
    "HR"
    "CY"
    "CZ"
    "DK"
    "EE"
    "FI"
    "FR"
    "DE"
    "EL"
    "HU"
    "IE"
    "IT"
    "LV"
    "LT"
    "LU"
    "MT"
    "NL"
    "PL"
    "PT"
    "RO"
    "SK"
    "SI"
    "ES"
    "SE"
    "CHE"
    "GB"})

(defn clean-vat-number [vat-number]
  (when vat-number
    (let [cleaned        (-> vat-number
                             (str/upper-case)
                             (str/replace  " " ""))
          prefix         (m/find-first (fn [prefix]
                                         (str/starts-with? cleaned prefix)) vat-number-prefixes)
          without-prefix (if prefix
                           (subs cleaned (count prefix))
                           cleaned)]
      (if (str/blank? without-prefix)
        nil
        without-prefix))))
