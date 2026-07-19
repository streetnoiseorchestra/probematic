(ns app.members.domain
  (:require
   [clojure.string :as str]
   [app.schemas :as s]
   [app.members.phone-number :as phone-number]
   [tick.core :as t]))

(defn revision []
  (t/format :iso-instant (t/instant)))

(defn generate-vcard [{:member/keys [name email nick phone member-id section]}]
  (format "BEGIN:VCARD
VERSION:3.0
PRODID;VALUE=TEXT://%s/NONSGML snorga//EN
UID:%s
FN:%s
NICKNAME:%s
N:;%s;;;
ORG:%s
TITLE:%s
TEL;TYPE=PREF,mobile;VALUE=UNKNOWN:%s
REV;VALUE=DATE-AND-OR-TIME:%s
EMAIL;TYPE=HOME:%s
END:VCARD"
          "streetnoise.at"
          (str member-id)
          name
          nick
          name
          "SNO"
          (:section/name section)
          phone
          (revision)
          email))

;; text/x-vcard

(comment
  (spit "test.vcf"
        (generate-vcard {:member/name      "Casey"
                         :member/nick      "casey"
                         :member/member-id "e362d49e-5b1e-4eb6-b7e5-7953879ae74f"
                         :member/email     "test@example.com"
                         :member/phone     "+43000000000"})) ;; rcf
  ;;
  )
(def username-regex #"^(?=[a-zA-Z0-9_.@\-]{3,20}$)(?!.*[_.]{2})[^_.].*[^_.]$")

(def member-attribute-label-key
  {:member/active?     :members/active-label
   :member/email       :members/email
   :member/keycloak-id :members/sno-uuid
   :member/name        :members/name
   :member/nick        :members/nickname
   :member/phone       :members/phone
   :member/section     :members/section
   :member/username    :members/username})

(def sno-id-status-label-key
  {true  :members/sno-id-enabled
   false :members/sno-id-disabled})

(defn validate-username [{:keys [tr]} username]
  (if (re-matches username-regex username)
    username
    (throw (ex-info "Validation error" {:validation/error (tr [:error/member-username-validation])}))))

(defn clean-email [email]
  (str/trim (str/lower-case email)))

(defn email-valid? [email]
  (boolean (s/valid? ::s/email-address email)))

(defn clean-username [username]
  (str/trim (str/lower-case username)))

(defn clean-phone-number [n]
  (-> n (phone-number/canonical nil) phone-number/normalize))

(defn phone-valid? [n]
  (phone-number/valid? n nil))

(defn NewMemberForm [tr]
  [:map
   [:username [:and ::s/non-blank-string [:re {:error/message (tr [:error/member-username-format])} username-regex]]]
   [:name ::s/non-blank-string]
   [:email ::s/non-blank-string]
   [:nick ::s/non-blank-string]
   [:section-name ::s/non-blank-string]
   [:active :boolean]
   [:create-sno-id :boolean]
   [:phone [:and ::s/non-blank-string [:fn {:error/message (tr [:error/member-phone-format])}
                                       phone-valid?]]]])
