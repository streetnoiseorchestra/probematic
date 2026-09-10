(ns app.email
  (:require
   [app.email.email-worker :as email-worker]
   [app.email.mailers :as mailers]
   [app.email.messages :as messages]
   [app.email.templates :as tmpl]
   [app.i18n :as i18n]
   [app.poll.queries :as poll.queries]
   [app.queries :as q]
   [app.datomic.shim :as datomic]))

(defn queue-email! [sys email]
  (email-worker/queue-mail! (:job-queue sys) email))

(defn- sys-from-req [req]
  {:tr           (:tr req)
   :env          (-> req :system :env)
   :i18n-langs   (-> req :system :i18n-langs)
   :job-queue    (-> req :system :job-queue)
   :datomic-conn (-> req :datomic-conn)})

(defn send-gig-created! [req gig-id]
  (let [db      (datomic/db (:datomic-conn req))
        gig     (q/retrieve-gig  db gig-id)
        members (q/active-members db)
        sys     (sys-from-req req)]
    (queue-email! sys (messages/build-gig-created-email sys gig members))))

(defn send-gig-reminder-to! [{:keys [datomic-conn i18n-langs env job-queue]} gig-id members]
  (assert datomic-conn)
  (assert env)
  (assert job-queue)
  ;; (tap> {:i18n i18n-langs :k (keys sys)})
  (assert i18n-langs)
  (let [db  (datomic/db datomic-conn)
        tr  (i18n/tr-with i18n-langs [:de])
        sys {:tr tr :env env :job-queue job-queue}
        gig (q/retrieve-gig db gig-id)]
    (queue-email! sys (messages/build-gig-reminder-email sys gig members))))

(defn send-gig-reminder-to-all! [{:keys [db] :as req}  gig-id]
  (let [attendance (q/attendance-for-gig-with-all-active-members db gig-id)
        members    (->> (q/attendance-plans-by-section-for-gig db attendance
                                                               :no-response?)
                        (mapcat :members)
                        (map :attendance/member))]
    ;; (tap> {:attendance attendance :members members})
    (send-gig-reminder-to! (sys-from-req req) gig-id members)
    :done))

(defn send-gig-updated!
  "Queues a gig-update notification from the successful transaction report."
  [req tx-result gig-id edited-attrs]
  (when (seq edited-attrs)
    (email-worker/queue-mailer!
     (assoc (sys-from-req req) :current-locale (:current-locale req))
     tx-result
     ::mailers/gig-updated
     {:gig-id       gig-id
      :member-ids   (mapv :member/member-id
                          (sort-by :member/member-id (q/active-members (:db-after tx-result))))
      :edited-attrs (vec (sort edited-attrs))})))

(defn send-poll-opened! [req poll-id]
  (let [db      (datomic/db (:datomic-conn req))
        poll    (poll.queries/retrieve-poll db poll-id)
        members (q/active-members db)
        sys     (sys-from-req req)]
    (queue-email! sys (messages/build-new-poll-opened sys poll members))))

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

(comment

  (do

    (require '[integrant.repl.state :as state])
    (require '[tick.core :as t])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn))
    (def gig (q/retrieve-gig db "01863829-2527-89fb-a582-4bd00f40c6b2"))
    (def gig2 {:gig/status    :gig.status/confirmed
               :gig/call-time (t/time "18:47")
               :gig/title     "Probe"
               :gig/gig-id    "0185a673-9f2d-8b0e-8f1a-e70db25c9add"
               :gig/contact   {:member/name      "SNOrchestra"
                               :member/member-id "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA6K70hwoM"
                               :member/nick      "SNO"}
               :gig/gig-type  :gig.type/probe
               :gig/set-time  (t/time "19:00")
               :gig/date      (t/date "2023-01-30")
               :gig/location  "Proberaum in den Bögen"})
    (require '[app.config :as config])

    (def member {:member/email     "me@example.com"
                 :member/member-id "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA2NP7ggoM"})

    (def member2 {:member/email     "me+test@example.com"
                  :member/member-id "ag1zfmdpZy1vLW1hdGljchMLEgZNZW1iZXIYgICA2NP7ggoM"})

    (def env (-> state/system :app.ig/env))

    (def poll (poll.queries/retrieve-poll db #uuid "018b60ab-32f5-8c78-9c67-28da6b48ec4c"))

    (def tr (i18n/tr-with (i18n/read-langs) ["en"]))
    (def sys {:tr tr :env env})) ;; rcf

  (spit "plain-email.txt"
        (get-in (messages/build-new-poll-opened
                 sys
                 (assoc poll :poll/description "")
                 [member])
                [:email/messages 0 :text]))

  (spit "plain-email.html"
        (get-in (messages/build-new-poll-opened
                 sys
                 (assoc poll :poll/description "")
                 [member])
                [:email/messages 0 :html]))

  (spit "plain-email.txt"
        (str
         "\n++++\n"
         (tmpl/gig-updated-email-plain sys gig [:gig/status])
         "\n++++\n"
         (tmpl/gig-updated-email-plain sys gig2 [:gig/status])))

  (:email/messages (messages/build-gig-created-email sys gig [member]))

  (tmpl/payload-for-attendance env (:gig/gig-id gig) (:member/member-id
                                                      (q/member-by-email db "CHANGEME")) :plan/definitely)

  ;;
  )
