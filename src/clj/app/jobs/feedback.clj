(ns app.jobs.feedback
  "Best-effort connection-scoped feedback for durable browser-initiated work."
  (:require [app.datastar :as datastar]
            [app.errors :as errors]
            [app.i18n :as i18n]
            [app.write-runner :as writer]))

(defn- with-origin! [{:keys [frame-loop] :as system} origin f]
  (when (and frame-loop (:tab-id origin) (:token origin))
    (try
      (writer/call!
       system
       (fn []
         (let [request (datastar/assoc-connection-token
                        frame-loop
                        {:body-params {:tab-id (:tab-id origin)}
                         :app/session {:session/member {:member/member-id (:member-id origin)}}})]
           (when (and (= (:token origin) (::datastar/state-token request))
                      (or (nil? (:action-id origin))
                          (= (:action-id origin) (get-in @(:clients frame-loop) [(:tab-id origin) :action-id]))))
             (f frame-loop request)))))
      (catch Exception e
        (errors/report-error! e {:operation ::failure}))))
  nil)

(defn failure!
  "Reports a first-attempt failure to the original connection, if still present.

  `origin` contains only server-captured tab, connection, member, and locale data.
  It grants no authority to change business data. Feedback failure must not hide
  the original job error or prevent Dollop from retrying it."
  [{:keys [i18n-langs] :as system} origin]
  (with-origin!
    system origin
    (fn [runtime request]
      (let [tr (i18n/tr-with i18n-langs [(:locale origin)])]
        (datastar/queue-sse-events!
         runtime request
         [[:app.datastar.sse/merge-signals {:loading false :targetid false}]
          [:app.datastar.sse/execute-script
           (str "window.alert(" (datastar/->signals (tr [:error/unknown-title])) ");")]])))))

(defn redirect!
  "Queues a post-completion redirect only for the original live connection."
  [system origin uri]
  (with-origin! system origin
    (fn [runtime request]
      (datastar/queue-sse-events! runtime request [[:app.datastar.sse/redirect uri]]))))
