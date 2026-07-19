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
   [app.ui2.core :as ui2-core]
   [com.fulcrologic.guardrails.malli.core]
   [app.system :as system]
   [app.ig]
   [integrant.repl.state :as state]
   [integrant.repl :as integrant.repl]))

;; --------------------------------------------------------------------------------------------
;; Toggle Dev-time flags

(set! *print-namespace-maps* false)
(ui2-core/enable-opts-validation!)

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
                  :no-reload '#{user integrant.repl.state}})

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
  (stop)
  (start)
  ;; much
  (clojure.repl.deps/sync-deps)
  ;;
  )

(comment

  (tap> 2)

  (first @(portal/my-taps)))

(comment
  (require '[mycelium.cell :as cell])
  (require '[mycelium.core :as myc])

  (defmethod cell/cell-spec :math/double [_]
    {:id      :math/double
     :handler (fn [_resources data]
                (assoc data :result (* 2 (:x data))))
     :schema  {:input  [:map [:x :int]]
               :output [:map [:result :int]]}})

  (defmethod cell/cell-spec :math/add-ten [_]
    {:id      :math/add-ten
     :handler (fn [_resources data]
                (assoc data :result (+ 10 (:result data))))
     :schema  {:input  [:map [:result :int]]
               :output [:map [:result :int]]}})

  (:mycelium/trace
   (myc/run-workflow
    {:cells      {:start :math/double
                  :add   :math/add-ten}
     :edges      {:start {:done :add}
                  :add   {:done :end}}
     :dispatches {:start [[:done (constantly true)]]
                  :add   [[:done (constantly true)]]}}
    {}          ;; resources
    {:x 5})))

(comment
  ;; A nested workflow has two normal business outcomes.
  (require '[mycelium.cell :as cell])
  (require '[mycelium.compose :as compose])
  (require '[mycelium.core :as myc])

  (defmethod cell/cell-spec :demo.nested/decide [_]
    {:id :demo.nested/decide
     :handler (fn [_resources _data] {})
     :schema {:input [:map [:approved? :boolean]]
              :output [:map]}})

  (def review-workflow
    {:cells {:start :demo.nested/decide}
     :edges {:start {:approved :end
                     :rejected :end}}
     :dispatches {:start [[:approved :approved?]
                          [:rejected #(not (:approved? %))]]}})

  ;; Vanilla composition exposes only :success and :failure to the parent.
  (compose/register-workflow-cell!
   :demo.nested/review
   review-workflow
   {:input [:map [:approved? :boolean]]
    :output :map})

  (defmethod cell/cell-spec :demo.nested/show-approved [_]
    {:id :demo.nested/show-approved
     :handler (fn [_resources _data] {:page :approved})
     :schema {:input [:map] :output [:map [:page [:= :approved]]]}})

  (defmethod cell/cell-spec :demo.nested/show-rejected [_]
    {:id :demo.nested/show-rejected
     :handler (fn [_resources _data] {:page :rejected})
     :schema {:input [:map] :output [:map [:page [:= :rejected]]]}})

  (def parent-workflow
    {:cells {:start :demo.nested/review
             :approved :demo.nested/show-approved
             :rejected :demo.nested/show-rejected}
     :edges {:start {:approved :approved
                     :rejected :rejected
                     :failure :error}
             :approved :end
             :rejected :end}})

  ;; This fails to compile: the nested cell supplies no :approved/:rejected dispatches.
  (myc/pre-compile parent-workflow)

  ;; Vanilla workaround: the parent knows and repeats the child's routing logic.
  (def coupled-parent-workflow
    (assoc parent-workflow
           :dispatches
           {:start [[:failure :mycelium/error]
                    [:approved :approved?]
                    [:rejected #(not (:approved? %))]]}))

  (mapv #(select-keys (myc/run-workflow coupled-parent-workflow {} {:approved? %})
                      [:approved? :page])
        [true false]))
