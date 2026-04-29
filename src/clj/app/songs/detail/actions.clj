(ns app.songs.detail.actions
  (:require
   [app.file-browser.actions :as file-browser.actions]
   [app.file-utils :as fu]
   [app.queries :as q]
   [app.util :as util]))

(def sheet-music-picker-id :song-sheet-music)

(defn- picker-key [signals]
  (file-browser.actions/picker-key (get-in signals [:file-browser :picker-id])))

(defn- selected-path [signals]
  (some-> (get-in signals [:file-browser :selected-path])
          file-browser.actions/remote-path
          fu/strip-leading-slash))

(defn add-sheet-music-tx-data [song-id section-name selected-path]
  [{:sheet-music/sheet-id :db/gen-uuid
    :sheet-music/song     [:song/song-id song-id]
    :sheet-music/section  [:section/name section-name]
    :sheet-music/title    (fu/basename selected-path)
    :file/webdav-path     selected-path}])

(defn add-sheet-music-action
  [{:keys [db page-state]} signals]
  (let [picker-id      (picker-key signals)
        picker         (get-in page-state [:file-browser picker-id])
        selected-path  (selected-path signals)
        song-id        (some-> (get-in picker [:target :song-id]) util/ensure-uuid!)
        section-name   (get-in picker [:target :section-name])]
    (if (and selected-path song-id section-name (q/retrieve-song db song-id))
      [[:db/transact
        (add-sheet-music-tx-data song-id section-name selected-path)
        {:on-success [[:app.songs/trigger-song-edited song-id]]}]
       [:app.datastar/assoc-state [:file-browser picker-id] nil]
       file-browser.actions/clear-file-browser-signals]
      [file-browser.actions/clear-file-browser-signals])))

(defn remove-sheet-music-action
  [_state signals]
  (let [song-id  (util/ensure-uuid! (or (get-in signals [:file-browser :song-id])
                                        (get-in signals [:song-detail :song-id])))
        sheet-id (util/ensure-uuid! (or (get-in signals [:file-browser :sheet-id])
                                        (:targetid signals)))]
    [[:db/transact
      [[:db/retractEntity [:sheet-music/sheet-id sheet-id]]]
      {:on-success [[:app.songs/trigger-song-edited song-id]]}]]))

(def actions
  {::add-sheet-music    #'add-sheet-music-action
   ::remove-sheet-music #'remove-sheet-music-action})
