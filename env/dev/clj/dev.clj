(ns dev
  (:require
   [app.ig]
   ;; [browser :as browser]
   [ol.app.dev.dev-extras :as dev-extra]))

;; (repl/disable-reload! (find-ns 'browser))
;; (repl/disable-reload! *ns*)

(set! *print-namespace-maps* false)

;; (mr/set-default-registry! schemas/registry)

#_(defn go-with-browser
    []
    (dev-extra/go)
    (browser/open-browser "http://localhost:4180/")
    :done)

(defn go
  []
  (dev-extra/go)
  :done)

(defn halt
  []
  (dev-extra/halt)
  :done)

(defn restart
  []
  (dev-extra/halt)
  (dev-extra/go))

(defn reset
  []
  (dev-extra/reset)
  #_(browser/refresh))

(comment

  (repl/clear)

  ;; Run go to start the system
  (dev-extra/go)
  ;; Run halt to shutdown the system
  (dev-extra/halt)
  ;; Run reset to reload all code and restart the system
  ;; if the browser is connected it will refresh the page
  (refresh)

  ;; If you have chromium  and chromium driver installed
  ;; you can get code reloading by using this version of go
  ;; see readme for more info
  #_(go-with-browser)

;;;; Setup Integrant Repl State
;;; Run this before running either of the seeds below
  (do
    (require '[integrant.repl.state :as state])
    (def kc (-> state/system :app.ig/keycloak))
    (def env (:app.ig/env state/system))
    (def conn (-> state/system :app.ig/datomic-db :conn))) ;; rcf

;;;; DEMO SEEDS
;;;  Run this to seed the instance with demo data
  (do
    (require '[app.demo :as demo])
    (demo/seed-random-members! conn))

;;; REAL  SEEDS
;;; only run this if you have access to the external systems
;;; DO NOT run this in demo mode

;;;; Scratch pad
  ;;  everything below is notes/scratch

  (require '[clojure.tools.namespace.repl :refer [refresh]])
  (refresh)

  (require '[datomic.local :as dl])
  (dl/release-db {:system "app" :db-name "probematic"})
  (md/start! schemas/malli-opts)
  (md/stop!)

  (set-prep! {:profile :dev})
  (keys state/system)
  (-> state/system :app.ig/pedestal)
  (-> state/system :app.ig/env)
  (-> state/system :app.ig/profile)

  (system/config {:profile :dev})

  (system/system-config {:profile :dev})

  (d/transact conn {:tx-data [{:db/ident       :song/arrangement-notes
                               :db/doc         "Notes for the arrangement"
                               :db/valueType   :db.type/string
                               :db/cardinality :db.cardinality/one}]})

  (def datomic (-> state/system :app.ig/datomic-db))
  (def conn (:conn datomic))
  (def app (-> state/system :app.ig.router/routes))

  (do
    (require '[integrant.repl.state :as state])
    (require '[keycloak.admin :as admin])
    (require '[datomic.client.api :as datomic])
    (def env (-> state/system :app.ig/env))
    (def kc (-> state/system :app.ig/keycloak))
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db (datomic/db conn)))
  ;; rcf
  (q/find-all-gigs db)

  (def user0 (second))

  (do
    (require '[portal.api :as p])
    (require '[com.brunobonacci.mulog :as mu])
    (def p (p/open {:theme :portal.colors/gruvbox}))
    (add-tap #'p/submit)

    (def pub! (mu/start-publisher! {:type :custom, :fqn-function "user/tap-publisher"})))

  (mu/log ::my-event ::ns (ns-publics *ns*))
  (do
    (remove-tap #'p/submit)
    (p/close))
  (p/clear)
  (tap> 1)
  (reset)
  (halt)
  (go)
  (clojure.repl.deps/sync-deps)
  (restart) ;; rcf
  ;;
  )
