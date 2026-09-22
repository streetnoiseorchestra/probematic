(ns app.email
  (:require
   [app.email.email-worker :as email-worker]
   [app.email.messages :as messages]
   [app.email.templates :as tmpl]
   [app.i18n :as i18n]))

(defn queue-email! [sys email]
  (email-worker/queue-mail! (:job-queue sys) email))

(defn- sys-from-req [req]
  {:tr           (:tr req)
   :env          (-> req :system :env)
   :i18n-langs   (-> req :system :i18n-langs)
   :job-queue    (-> req :system :job-queue)
   :datomic-conn (-> req :datomic-conn)})

(defn send-new-user-email! [req new-member invite-code]
  (queue-email! (sys-from-req req)
                (messages/build-new-user-invite (sys-from-req req) new-member invite-code)))

(defn send-admin-email! [req from-member req-human-id]
  (let [member-name (:member/name from-member)
        admin-email (-> req :system :env :admin-email)]
    (assert admin-email)
    (queue-email! (sys-from-req req)
                  (messages/build-generic-email (sys-from-req req)
                                                admin-email
                                                (str "SNOrga Error from " member-name)
                                                (format "There was a SNOrga error that needs attention from %s with human-id %s" member-name req-human-id)
                                                nil
                                                nil))))
(defn send-rehearsal-leader-email! [{:keys [i18n-langs env job-queue]} gig leader-member]
  (assert gig)
  (assert leader-member)
  (let [tr  (i18n/tr-with i18n-langs [:de])
        sys {:tr tr :env env :job-queue job-queue}]
    (queue-email! sys (messages/build-rehearsal-leader-email sys gig leader-member))))

(defn send-insurance-debt-notifications! [req sender-name time-range member-data]
  (let [sys    (sys-from-req req)
        emails (messages/build-insurance-debt-notification-emails sys sender-name time-range member-data)]
    (doseq [email emails]
      (queue-email! sys email))))

(defn render-insurance-debt-email-template [req sender-name time-range sample-data]
  (let [sys (sys-from-req req)]
    (tmpl/insurance-debt-hiccup sys
                                (tmpl/build-insurance-debt-args sys {:member/name      (:member/name (:member sample-data))
                                                                     :member/member-id (:member/member-id (:member sample-data))}
                                                                (:private-coverages sample-data)
                                                                sender-name
                                                                time-range
                                                                (:private-cost-total sample-data)))))

(defn send-survey-notifications! [req sender-name policy members email-data]
  (let [sys (sys-from-req req)]
    (queue-email! sys
                  (messages/build-survey-notifications sys sender-name policy members email-data))))
