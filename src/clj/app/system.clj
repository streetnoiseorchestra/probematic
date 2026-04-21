(ns app.system
  "Application config loading and Integrant config preparation."
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [integrant.core :as ig]))

(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(defmethod aero/reader 'ig/refset [_ _ value]
  (ig/refset value))

(let [lock (Object.)]
  (defn- load-namespaces [system-config]
    (locking lock
      (ig/load-namespaces system-config))))

(defn config
  "Read EDN config with the given Aero options."
  [opts]
  (-> (io/resource "config.edn")
      (aero/read-config opts)))

(defn system-config
  "Construct an expanded Integrant config for the given Aero options."
  [opts]
  (let [config (config opts)
        system-config (:ig/system config)]
    (load-namespaces system-config)
    (ig/expand system-config)))
