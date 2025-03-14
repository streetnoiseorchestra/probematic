(ns app.songs.controller
  (:require
   [app.datomic :as d]
   [app.discourse :as discourse]
   [app.file-utils :as fu]
   [app.jobs.gig-events :as gig.events]
   [app.probeplan.stats :as stats]
   [app.queries :as q]
   [app.util :as util]
   [com.yetanalytics.squuid :as sq]
   [ctmx.rt :as rt]
   [app.datomic.shim :as datomic]))

(defn create-song! [req]
  (let [{:keys [title active?]} (-> req util/unwrap-params)
        song-id (sq/generate-squuid)
        result (d/transact (:datomic-conn req)
                           {:tx-data [{:song/title title :song/song-id song-id
                                       :song/total-plays 0
                                       :song/active? (ctmx.rt/parse-boolean active?)}]})]
    (q/retrieve-song (:db-after result) song-id)))

(defn delete-song! [{:keys [db datomic-conn] :as req} song-id]
  (let [song-ref [:song/song-id song-id]
        played      (mapv (fn [{:played/keys [play-id]}]
                            [:db/retractEntity [:played/play-id play-id]]) (q/plays-by-song db song-id))

        sheet-music      (mapv (fn [{:played/keys [sheet-id]}]
                                 [:db/retractEntity [:sheet-music/sheet-id sheet-id]]) (q/sheet-music-by-song db song-id))
        txs (concat played sheet-music [[:db/retractEntity song-ref]])]
    (datomic/transact datomic-conn {:tx-data txs})
    (when (> (count played) 0)
      (stats/calc-play-stats-in-bg! datomic-conn))
    true))

(defn update-song! [{:keys [datomic-conn] :as req} song-id]
  (let [{:keys [topic-id title active? composition-credits arrangement-credits lyrics arrangement-notes origin solo-info] :as p} (util/unwrap-params req)
        tx (util/remove-nils {:song/song-id song-id
                              :song/composition-credits composition-credits
                              :song/arrangement-credits arrangement-credits
                              :song/arrangement-notes arrangement-notes
                              :song/title title
                              :song/lyrics lyrics
                              :song/active? (rt/parse-boolean active?)
                              :song/origin origin
                              :forum.topic/topic-id (discourse/parse-topic-id topic-id)
                              :song/solo-info solo-info})
        result (datomic/transact datomic-conn {:tx-data [tx]})]
    (gig.events/trigger-song-edited req song-id)
    (q/retrieve-song (:db-after result) song-id)))

(defn log-play! [req]
  (let [{:keys [gig-id song-id play-type feeling]} (util/unwrap-params req)
        rating (keyword feeling)
        emphasis (keyword play-type)
        play-id (sq/generate-squuid)
        result (d/transact (:datomic-conn req) {:tx-data
                                                [{:played/song [:gig/gig-id gig-id]
                                                  :played/gig  [:song/song-id song-id]
                                                  :played/rating rating
                                                  :played/play-id play-id
                                                  :played/emphasis emphasis}]})]
    (if (d/db-ok? result)
      {:play result}
      result)))

(defn add-sheet-music! [{:keys [datomic-conn]} song-id section-name selected-path]
  (let [tx {:sheet-music/sheet-id (sq/generate-squuid)
            :sheet-music/song [:song/song-id song-id]
            :sheet-music/section [:section/name section-name]
            :sheet-music/title (fu/basename selected-path)
            :file/webdav-path selected-path}]
    (:db-after
     (datomic/transact datomic-conn {:tx-data [tx]}))))

(defn remove-sheet-music! [{:keys [datomic-conn]} sheet-id]
  (:db-after
   (datomic/transact datomic-conn {:tx-data [[:db/retractEntity [:sheet-music/sheet-id sheet-id]]]})))

(comment

  (do
    (require '[integrant.repl.state :as state])

    (require  '[app.datomic.shim :as datomic])
    (def conn (-> state/system :app.ig/datomic-db :conn))
    (def req {:datomic-conn conn
              :db (datomic/db conn)
              :params {}}))

  (def _titles
    ["Kingdom Come"
     "Surfin"
     "Asterix" ,
     "Bella Ciao" ,
     "Cumbia Sobre el Mar" ,
     "Der Zug um 7.40" ,
     "Grenzrenner" ,
     "Inner Babylon" ,
     "Kids Aren't Alright" ,
     "Klezma 34" ,
     "Laisse Tomber Les Filles" ,
     "L’estaca del pueblo" ,
     "Metanioa" ,
     "Monkeys Rally" ,
     "Montserrat Serrat" ,
     "Rasta Funk" ,
     "Tammurriata Nera" ,
     "Tschufittl Cocek" ,
     "You Move You Lose" ,
     "Odessa Bulgar"])

  (d/transact conn {:tx-data
                    (mapv (fn [title]
                            {:song/title title
                             :song/active? true
                             :song/song-id (sq/generate-squuid)
                             :song/total-plays 0}) _titles)})

  (q/retrieve-all-songs (d/db conn))

  (let [res
        (create-song! (assoc-in req [:params :song-new_song] "Asterix"))]
    (unique-error? res))

  (d/transact conn {:tx-data [{:db/ident :song/title
                               :db/doc "The title of the song"
                               :db/valueType :db.type/string
                               :db/unique :db.unique/value
                               :db/cardinality :db.cardinality/one}]})
  ;;
  )
(defn sync-songs! [req]
  (gig.events/trigger-sync-all-songs req))
