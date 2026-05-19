(ns dev
  {:clj-kondo/config '{:linters       {:unused-namespace     {:level :off}
                                       :unresolved-namespace {:level :off}
                                       :unused-referred-var  {:level :off}}
                       :skip-comments true}}
  (:require
   [app.errors :as error]
   [com.brunobonacci.mulog :as mu]
   [clj-reload.core :as clj-reload]
   [ol.dev.portal :as portal]
   [app.ui.core :as ui-core]
   [com.fulcrologic.guardrails.malli.core]
   [app.system :as system]
   [app.ig]
   [integrant.repl.state :as state]
   [integrant.repl :as integrant.repl]))

;; --------------------------------------------------------------------------------------------
;; Toggle Dev-time flags

(set! *print-namespace-maps* false)
(ui-core/enable-opts-validation!)

;; --------------------------------------------------------------------------------------------
;; Portal & Logging

(defonce portal! (portal/open-portals))
(defonce pub! (mu/start-publisher! {:type         :custom
                                    :fqn-function "user/tap-publisher"
                                    :transform    error/redact-mulog-events}))

(defn logs
  "Query debug log: (logs), (logs 5), (logs :label), (logs :label 3)"
  ([] (portal/logs))
  ([n-or-label] (portal/logs n-or-label))
  ([label n] (portal/logs label n)))

(defn log-values
  "Like logs, but returns just the values."
  ([] (portal/log-values))
  ([n-or-label] (portal/log-values n-or-label))
  ([label n] (portal/log-values label n)))

(defn clear-logs! [] (portal/clear-logs!))

(defn last-log
  "Most recent entry, or just value with (last-log :v)."
  ([] (portal/last-log))
  ([_] (portal/last-log :v)))

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
  ;; normal reload
  (reset) ;; rcf

  ;; when the above fails
  (do
    (clj-reload/reload {:only :loaded})
    (reset))

  ;; other
  (clj-reload/reload)
  (clj-reload/reload {:only :loaded})
  (stop)
  (reset)
  (restart)
  (reload-all)
  ;; much
  (clojure.repl.deps/sync-deps)
  ;;
  )

(comment

  (tap> 2)

  (first @(portal/my-taps)))
