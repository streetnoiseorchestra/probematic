(ns app.gigs.answer-link.views-test
  (:require
   [app.gigs.answer-link.views :as views]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]])
  (:import
   [java.util UUID]))

(defn tr [[k] & _]
  (case k
    :gig/answer-link-submitted "Your answer has been submitted - thanks!"
    :email/invite-expired "Sorry, the invitation code has expired. Please ask for a new one!"
    :login "Login"
    (name k)))

(deftest answer-link-preview-is-a-standalone-page-test
  (let [{:keys [status body]} (views/answer-link-preview {:tr tr})]
    (is (= 200 status))
    (is (str/includes? body "Your answer has been submitted - thanks!"))))

(deftest answer-link-success-page-redirects-to-gig-detail-test
  (let [gig-id (UUID/randomUUID)
        {:keys [status body]} (#'views/success-page {:tr tr} {:gig/gig-id gig-id})]
    (is (= 200 status))
    (is (str/includes? body "Your answer has been submitted - thanks!"))))
