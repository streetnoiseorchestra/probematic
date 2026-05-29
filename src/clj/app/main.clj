(ns app.main
  (:gen-class)
  (:require
   ol.system
   [app.ig]
   [signal.handler :as signal]
   [com.brunobonacci.mulog :as μ]
   [integrant.core :as ig]))

(def system nil)

(defn profile []
  (if-let [p (System/getenv "APP_PROFILE")]
    (keyword p)
    :prod))

(defn stop-system! []
  (alter-var-root #'system ig/halt!)
  (shutdown-agents))

(defn -main [& args]
  (let [profile (profile)
        _ (μ/log ::pre-start :profile profile)
        system-config (ol.system/system-config {:profile profile})
        sys (ig/init system-config)]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread.
      (fn []
        (stop-system!))))
    (alter-var-root #'system (constantly sys)))
  @(promise))

(signal/with-handler :term
  (μ/log ::caught-signal :signal :term)
  (stop-system!)
  (μ/log ::shutdown-complete))
