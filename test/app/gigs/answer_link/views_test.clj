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
  (let [{:keys [status headers body]} (views/answer-link-preview {:tr tr})]
    (is (= 200 status))
    (is (= "text/html" (get headers "Content-Type")))
    (is (str/includes? body "Your answer has been submitted - thanks!"))
    (is (str/includes? body "<svg"))
    (is (str/includes? body "logotype-snoman"))
    (is (str/includes? body "logotype-text"))
    (is (not (str/includes? body "Login")))
    (is (not (str/includes? body "href=\"/login\"")))
    (is (str/includes? body "<style>"))
    (is (str/includes? body "main > header"))
    (is (not (str/includes? body "main &gt; header")))
    (is (not (str/includes? body "<script")))
    (is (not (str/includes? body "<link")))
    (is (not (str/includes? body "<wa-")))))

(deftest answer-link-success-page-redirects-to-gig-detail-test
  (let [gig-id (UUID/randomUUID)
        {:keys [status body]} (#'views/success-page {:tr tr} {:gig/gig-id gig-id})]
    (is (= 200 status))
    (is (str/includes? body "Your answer has been submitted - thanks!"))
    (is (str/includes? body "<script>"))
    (is (str/includes? body "window.setTimeout"))
    (is (str/includes? body "window.location.assign"))
    (is (str/includes? body "1000"))
    (is (str/includes? body (pr-str (str "/gig/" gig-id))))))
