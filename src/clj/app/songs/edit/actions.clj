(ns app.songs.edit.actions
  (:require
   [app.discourse :as discourse]
   [app.form :as form]
   [app.nexus.actions :as support]
   [app.queries :as q]
   [app.urls :as urls]
   [app.util :as util]
   [clojure.string :as str]
   [com.yetanalytics.squuid :as sq]))

(defn- form-params [signals]
  (let [params (or (:song-edit signals) signals)]
    (dissoc params :tab-id :_error :validate-field)))

(defn normalize-form [params]
  (reduce
   (fn [params [k f]]
     (form/update-present params k f))
   params
   [[:song-id #(some-> % str)]
    [:title form/trim-value]
    [:active? form/normalize-bool]
    [:solo-info form/trim-value]
    [:composition-credits form/trim-value]
    [:arrangement-credits form/trim-value]
    [:arrangement-notes #(or % "")]
    [:origin #(or % "")]
    [:lyrics #(or % "")]
    [:topic-id form/trim-value]]))

(defn- song-update-map
  [{:keys [song-id title active? composition-credits arrangement-credits arrangement-notes origin lyrics solo-info topic-id]}]
  {:song/song-id              (util/ensure-uuid! song-id)
   :song/title                title
   :song/active?              (form/normalize-bool active?)
   :song/solo-info            (form/optional-text solo-info)
   :song/composition-credits  (form/optional-text composition-credits)
   :song/arrangement-credits  (form/optional-text arrangement-credits)
   :song/arrangement-notes    (form/optional-text arrangement-notes)
   :song/origin               (form/optional-text origin)
   :song/lyrics               (form/optional-text lyrics)
   :forum.topic/topic-id      (some-> topic-id form/optional-text discourse/parse-topic-id)})

(defn update-song-tx-data [params]
  [(song-update-map params)])

(defn create-song-tx-data [params]
  [(assoc (util/remove-nils (song-update-map params))
          :song/total-plays 0)])

(defn- required-error [tr field]
  {:error (tr [:error/is-required]
              {:field (if (= :title field)
                        (tr [:repertoire/song-title-label])
                        (name field))})})

(defn- top-error [message]
  {:_top {:error message}})

(defn- with-generic-top-error [tr errors]
  (cond-> errors
    (and (seq errors) (nil? (:_top errors)))
    (assoc :_top {:error (tr [:error/form-has-errors])})))

(defn validation-errors [{:keys [tr]} {:keys [title]}]
  (merge
   (when (str/blank? title)
     {:title (required-error tr :title)})))

(defn validate-song-field-action
  [{:keys [tr]} signals]
  (let [raw   (or (:song-edit signals) {})
        field (some-> (:validate-field raw) keyword)
        form  (dissoc (normalize-form raw) :_error :validate-field)
        error (get (validation-errors {:tr tr} form) field)]
    (cond-> [[:app.datastar/merge-state [:song-edit] form]]
      field (conj [:app.datastar/assoc-state
                   [:song-edit :_error field]
                   error]))))

(defn update-song-action
  [{:keys [db tr]} signals]
  (let [params (normalize-form (form-params signals))
        song-id (util/ensure-uuid! (:song-id params))
        song   (q/retrieve-song db song-id)
        errors (with-generic-top-error
                 tr
                 (merge
                  (when-not song
                    (top-error (tr [:error/not-found-title])))
                  (validation-errors {:tr tr} params)))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [:song-edit] (assoc params :_error errors)]]
      [[:db/transact
        (update-song-tx-data params)
        {:transact-w-nils? true
         :on-success       [[:app.songs/trigger-song-edited song-id]]}]
       [:app.datastar/respond-sse
        [[:app.datastar.sse/redirect (urls/link-song song-id)]]]])))

(defn create-song-action
  [{:keys [tr]} signals]
  (let [params (normalize-form (form-params signals))
        errors (with-generic-top-error tr (validation-errors {:tr tr} params))]
    (if (seq errors)
      [support/clear-loading
       [:app.datastar/assoc-state [:song-edit] (assoc params :_error errors)]]
      (let [song-id (sq/generate-squuid)
            params  (assoc params :song-id (str song-id))]
        [[:db/transact (create-song-tx-data params) {}]
         [:app.datastar/respond-sse
          [[:app.datastar.sse/redirect (urls/link-song song-id)]]]]))))

(defn delete-song-tx-data [db song-id]
  (let [song-ref    [:song/song-id song-id]
        played      (mapv (fn [{:played/keys [play-id]}]
                            [:db/retractEntity [:played/play-id play-id]])
                          (q/plays-by-song db song-id))
        sheet-music (mapv (fn [{:sheet-music/keys [sheet-id]}]
                            [:db/retractEntity [:sheet-music/sheet-id sheet-id]])
                          (q/sheet-music-by-song db song-id))]
    {:recalc-play-stats? (pos? (count played))
     :tx-data            (vec (concat played
                                      sheet-music
                                      [[:db/retractEntity song-ref]]))}))

(defn delete-song-action
  [{:keys [db tr]} signals]
  (let [params  (form-params signals)
        song-id (util/ensure-uuid! (or (:song-id params) (:targetid params)))
        song    (q/retrieve-song db song-id)]
    (if-not song
      [support/clear-loading
       [:app.datastar/assoc-state
        [:song-edit :_error :_top]
        {:error (tr [:error/not-found-title])}]]
      (let [{:keys [tx-data recalc-play-stats?]} (delete-song-tx-data db song-id)]
        [[:db/transact
          tx-data
          {:on-success (when recalc-play-stats?
                         [[:app.songs/recalc-play-stats]])}]
         [:app.datastar/respond-sse
          [[:app.datastar.sse/redirect (urls/link-songs-home)]]]]))))

(def actions
  {::validate-song-field #'validate-song-field-action
   ::create-song         #'create-song-action
   ::update-song         #'update-song-action
   ::delete-song         #'delete-song-action})
