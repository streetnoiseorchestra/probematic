(ns app.twilio
  (:require
   [app.util :as util]
   [clojure.string :as str]
   [jsonista.core :as j]
   [org.httpkit.client :as client]))

(defn lookup-number
  ([twi number]
   (lookup-number twi number nil))
  ([twi number country-code]
   (let [resp @(client/request {:url          (str "https://lookups.twilio.com/v2/PhoneNumbers/" (client/url-encode number))
                                :query-params (when country-code {"CountryCode" country-code})
                                :method       :get
                                :basic-auth   [(:twilio-account twi) (:twilio-token twi)]})]
     (if (= 200 (:status resp))
       (let [r (j/read-value (:body resp))]
         (tap> r)
         (if (seq (get r "validation_errors"))
           (throw (ex-info "Invalid number" {:number number :country-code country-code
                                             :twilio r
                                             :validation/error "Invalid phone number"}))
           (get r "phone_number")))

       (do
         (tap> {:lookup-error resp})
         (throw  (ex-info "Invalid number" {:number number :country-code country-code
                                            :validation/error "Invalid phone number"
                                            :body (j/read-value (:body resp))})))))))

(defn clean-number [env orig-number]
  (let [number (util/clean-number orig-number)]
    (when (not-empty number)
      (if (str/starts-with? number "+")
        (lookup-number (:twilio env) number)
        (or
         (lookup-number (:twilio env) number "AT")
         (lookup-number (:twilio env) number "DE")
         (throw (ex-info "Invalid Austrian or German number" {:orig-number orig-number :clean number
                                                              :validation/error "Invalid phone number"})))))))

(comment

  (do
    (require '[integrant.repl.state :as state])
    (require  '[app.datomic.shim :as datomic])
    (def env (-> state/system :app.ig/env))) ;; rcf

  ;;
  )
