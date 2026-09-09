(ns app.jobs.feedback
  "Best-effort connection-scoped feedback for durable browser-initiated work."
  (:require [app.datastar :as datastar]
            [app.errors :as errors]
            [app.i18n :as i18n]
            [app.write-runner :as writer]))

(defn- with-origin! [frame-loop origin f]
  (when (and frame-loop (:tab-id origin) (:token origin))
    (try
      (writer/call!
       (:write-runner frame-loop)
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
  [{:keys [frame-loop i18n-langs]} origin]
  (with-origin!
    frame-loop origin
    (fn [runtime request]
      (let [tr (i18n/tr-with i18n-langs [(:locale origin)])]
        (datastar/queue-sse-events!
         runtime request
         [[:app.datastar.sse/merge-signals {:loading false :targetid false}]
          [:app.datastar.sse/execute-script
           (str "window.alert(" (datastar/->signals (tr [:error/unknown-title])) ");")]])))))

(defn redirect!
  "Queues a post-completion redirect only for the original live connection."
  [{:keys [frame-loop]} origin uri]
  (with-origin! frame-loop origin
    (fn [runtime request]
      (datastar/queue-sse-events! runtime request [[:app.datastar.sse/redirect uri]]))))
