(ns app.cms
  (:require
   [app.queries :as q]
   [app.datomic.shim :as datomic]
   [jsonista.core :as j]
   [org.httpkit.client :as client]
   [tick.core :as t]))

(defn update-cms-req [cms-url token payload]
  {:method  :post
   :url     (str cms-url "/api/song/")
   :headers {"authorization" (str "Bearer " token)
             "content-type"  "application/json"}
   :body    (j/write-value-as-string payload)})

(defn song->wagtail [{:song/keys [title song-id composition-credits arrangement-credits active? origin lyrics arrangement-notes last-played-on]}]
  {:snorga_id           song-id
   :title               title
   :status              (if active? "active" "retired")
   :arrangement_credits arrangement-credits
   :composition_credits composition-credits
   :lyrics              lyrics
   :description         origin
   :arrangement_notes   arrangement-notes
   :last_played_date    (when last-played-on (-> last-played-on t/date-time str))})

(defn- send-song! [{:keys [cms]} song]
  (let [{:keys [status error] :as response}
        @(client/request (assoc (update-cms-req (:cms-url cms) (:token cms) (song->wagtail song)) :timeout 20000))]
    (when (or error (not (<= 200 (or status 0) 299)))
      (throw (ex-info "CMS request failed" {:status status :song-id (:song/song-id song)} error)))
    response))

(defn sync-song! [{:keys [db env]} song-id]
  (send-song! env (q/retrieve-song db song-id)))

(defn sync-all-songs! [{:keys [datomic db env]}]
  (let [db (or db (datomic/db (:conn datomic)))]
    (doseq [song (q/retrieve-all-songs db q/song-pattern-detail)]
      (Thread/sleep 200)
      (send-song! env song))))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))
    (def system {:datomic    {:conn conn}
                 :i18n-langs (-> state/system :app.ig/i18n-langs)
                 :env        (-> state/system :app.ig/env)})) ;; rcf

  (sync-song! system #uuid "01844740-3eed-856d-84c1-c26f0706820d")

  (song->wagtail (q/retrieve-song db #uuid "01844740-3eed-856d-84c1-c26f0706820d"))
  ;;
  )
