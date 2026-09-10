(ns app.members.invite.stream
  "Read-only, capability-scoped invitation status streams on the frame runtime."
  (:require
   [app.datastar :as datastar]
   [app.game-loop :as game]
   [app.game-loop.storage :as storage]
   [app.members.invite.queries :as queries]
   [starfederation.datastar.clojure.adapter.http-kit :as hk-gen]
   [starfederation.datastar.clojure.api :as d*]
   [tick.core :as t])
  (:import [java.util.concurrent ConcurrentHashMap]))

(defn response
  "Opens a read-only stream after authorizing the bearer in a committed frame.

  `render-fn` receives the request with the current frame database and the safe
  status projection. It returns HTML. Revalidates on every frame; no browser tab
  ID, authenticated member ownership, or action signals are attached to this stream."
  [{:keys [frame-loop job-queue]} request invite-code render-fn]
  (let [status-in-frame (fn [frame]
                          (queries/status-for-code (:db frame) (:client job-queue)
                                                   (get-in frame [::storage/read-dbs :jobs])
                                                   (t/inst) invite-code))
        initial         (if frame-loop
                          (datastar/render-in-frame! frame-loop status-in-frame)
                          {:status 503 :headers {} :body ""})]
    (cond
      (number? (:status initial)) initial
      (= :unavailable (:status initial)) {:status 404 :headers {"Cache-Control" "no-store"} :body ""}
      :else
      (let [id      [::connection (random-uuid)]
            clients (:clients frame-loop)
            conns   ^ConcurrentHashMap (::game/conns frame-loop)]
        (hk-gen/->sse-response
         request
         {:headers             {"X-Accel-Buffering" "no" "Cache-Control" "no-store" "Referrer-Policy" "no-referrer"}
          hk-gen/write-profile datastar/brotli-write-profile
          hk-gen/on-open
          (fn [sse]
            (locking clients
              (if @(:stopped? frame-loop)
                (d*/close-sse! sse)
                (do
                  (swap! clients assoc id {:close! #(d*/close-sse! sse)})
                  (.put conns id
                        (game/render-callback
                         (fn [frame]
                           (render-fn (assoc request :db (:db frame)) (status-in-frame frame)))
                         #(d*/patch-elements! sse % {d*/id (datastar/digest %)})))))))
          hk-gen/on-close
          (fn [_ _]
            (locking clients
              (.remove conns id)
              (swap! clients dissoc id)))})))))
