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

(add-tap portal-repl/submit)
(defonce pub! (mu/start-publisher! {:type         :custom
                                    :fqn-function "user/tap-publisher"
                                    :transform    error/redact-mulog-events}))

(defn logs
  "Query debug log: (logs), (logs 5), (logs :label), (logs :label 3)"
  ([] @portal-helpers/my-taps)
  ([n-or-label]
   (if (number? n-or-label)
     (vec (take-last n-or-label @portal-helpers/my-taps))
     (vec (filter #(= n-or-label (first %)) @portal-helpers/my-taps))))
  ([label n]
   (vec (take-last n (filter #(= label (first %)) @portal-helpers/my-taps)))))

(defn log-values
  "Like logs, but returns just the values"
  ([] (mapv :value @portal-helpers/my-taps))
  ([n-or-label] (mapv :value (logs n-or-label)))
  ([label n] (mapv :value (logs label n))))

(defn clear-logs! [] (reset! portal-helpers/my-taps []))

(defn last-log
  "Most recent entry, or just value with (last-log :v)"
  ([] (last @portal-helpers/my-taps))
  ([_] (:value (last @portal-helpers/my-taps))))

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
  (clj-reload/reload)
  (clj-reload/reload {:only :loaded})
  (stop)
  (reset) ;; rcf
  (restart)
  (reload-all)
  ;; much
  (clojure.repl.deps/sync-deps)
  ;;
  )

(comment

  (tap> 2)

  (first @portal-helpers/my-taps))
