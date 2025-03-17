(ns app.server
  (:require
   [com.brunobonacci.mulog :as μ]
   [integrant.core :as ig]
   [org.httpkit.server :as hk-server]))

(defn start-hk [handler hk-opts]
  (hk-server/run-server handler
                        (-> hk-opts
                            (assoc  :legacy-return-value? false))))

(defn stop-hk [instance]
  (when instance
    (hk-server/server-stop! instance)))

(defmethod ig/init-key ::http-kit [_ {:keys [handler env options] :as system}]
  (let [start-msg (format "Starting %s on %s:%d" (str (:name env "app")) (:ip options) (:port options))
        instance  (start-hk handler options)]
    (μ/log ::init-http :msg start-msg)
    instance))

(defmethod ig/halt-key! ::http-kit [_ instance]
  (stop-hk instance))
