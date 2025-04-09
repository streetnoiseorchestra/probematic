(ns dev
  {:clj-kondo/config '{:linters       {:unused-namespace     {:level :off}
                                       :unresolved-namespace {:level :off}
                                       :unused-referred-var  {:level :off}}
                       :skip-comments true}}
  (:require
   [app.errors :as error]
   [com.brunobonacci.mulog :as mu]
   [clj-reload.core :as clj-reload]
   [portal-helpers :as portal-repl]
   [app.ui.core :as ui-core]
   [com.fulcrologic.guardrails.malli.core]
   [ol.system :as system]
   [app.ig]
   [integrant.repl.state :as state]
   [integrant.repl :as integrant.repl]))

;; --------------------------------------------------------------------------------------------
;; Toggle Dev-time flags

(set! *print-namespace-maps* false)
(ui-core/enable-opts-validation!)

;; --------------------------------------------------------------------------------------------
;; Portal & Logging

(add-tap portal-repl/submit)
(defonce pub! (mu/start-publisher! {:type         :custom
                                    :fqn-function "user/tap-publisher"
                                    :transform    error/redact-mulog-events}))

;; --------------------------------------------------------------------------------------------
;; System Control

(integrant.repl/set-prep! #(system/system-config {:profile :dev}))

(defn start []
  (integrant.repl/go)
  :started)

(defn stop []
  (integrant.repl/halt)
  :halted)

(defn restart []
  (stop)
  (start))

(defn reset []
  (stop)
  (clj-reload/reload)
  (start))

(defn reload-all []
  (clj-reload/reload {:only :all}))

;; --------------------------------------------------------------------------------------------
;; Code Reloading

(clj-reload/init {:dirs      ["src" "dev" "test"]
                  :no-reload '#{dev user integrant.repl.state}})

(comment
  (stop)
  (restart) ;; rcf
  (reload-all)
  ;; much
  (clojure.repl.deps/sync-deps)
  ;;
  )
