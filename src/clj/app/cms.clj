(ns app.cms
  (:require
   [app.errors :as errors]
   [app.queries :as q]
   [app.datomic.shim :as datomic]
   [jsonista.core :as j]
   [org.httpkit.client :as client]
   [tick.core :as t]))

(defn update-cms-req [cms-url token payload]
  {:method :post
   :url (str cms-url "/api/song/")
   :headers {"authorization" (str "Bearer " token)
             "content-type" "application/json"}
   :body (j/write-value-as-string payload)})

(defn song->wagtail [{:song/keys [title song-id composition-credits arrangement-credits active? origin lyrics arrangement-notes last-played-on]}]
  {:snorga_id song-id
   :title title
   :status (if active? "active" "retired")
   :arrangement_credits arrangement-credits
   :composition_credits composition-credits
   :lyrics lyrics
   :description origin
   :arrangement_notes arrangement-notes
   :last_played_date (when last-played-on (-> last-played-on t/date-time str))})

(defn sync-song! [{:keys [db] :as system} song-id]
  (try
    (let [{:keys [token cms-url]} (-> system :env :cms)
          song (q/retrieve-song db song-id)
          resp @(client/request (->> song
                                     song->wagtail
                                     (update-cms-req cms-url token)))]
      resp)
    (catch Exception e
      (errors/report-error! e))))

(defn sync-all-songs! [{:keys [datomic] :as system}]
  (try
    (let [conn (:conn datomic)
          {:keys [token cms-url]} (-> system :env :cms)
          songs (q/retrieve-all-songs (datomic/db conn) q/song-pattern-detail)
          requests (->> songs
                        (map song->wagtail)
                        (map (partial update-cms-req cms-url token)))]
      (doseq [req requests]
        (Thread/sleep 200)
        @(client/request req)))
    (catch Exception e
      (tap> e)
      (errors/report-error! e))))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def db  (datomic/db conn))
    (def system {:datomic {:conn conn}
                 :redis (-> state/system :app.ig/redis)
                 :i18n-langs (-> state/system :app.ig/i18n-langs)
                 :env (-> state/system :app.ig/env)})) ;; rcf

  (sync-song! system #uuid "01844740-3eed-856d-84c1-c26f0706820d")

  (song->wagtail (q/retrieve-song db #uuid "01844740-3eed-856d-84c1-c26f0706820d"))
  ;;
  )
