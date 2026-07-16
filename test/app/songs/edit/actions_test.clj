(ns app.songs.edit.actions-test
  (:require
   [app.songs.edit.actions :as actions]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(def translations
  {[:action/save]          "Save"
   [:error/form-has-errors] "Please fix the errors in the form."
   [:error/is-required]    "%field is required."
   [:error/not-found-title]          "Not Found"
   [:repertoire/song-title-label]    "Song Title"})

(defn tr
  ([k]
   (get translations k (name (last k))))
  ([k args]
   (reduce-kv (fn [s key arg]
                (str/replace s (str "%" (name key)) (str arg)))
              (tr k)
              args)))

(defn new-system []
  (tc/new-system "songs-edit-actions"))

(defn state-for [{:keys [conn]}]
  {:db (d/db conn)
   :tr tr})

(defn seed-song! [conn song]
  @(d/transact conn [song]))

(deftest update-song-action-test
  (testing "updates an existing song and clears blank optional fields"
    (let [{:keys [conn] :as system} (new-system)
          song-id                   (random-uuid)]
      (seed-song! conn {:song/song-id             song-id
                        :song/title               "Old Title"
                        :song/active?             true
                        :song/solo-info           "Solo"
                        :song/composition-credits "Composer"})
      (is (= [[:db/transact
               [{:song/song-id             song-id
                 :song/title               "New Title"
                 :song/active?             false
                 :song/solo-info           nil
                 :song/composition-credits nil
                 :song/arrangement-credits "Arranger"
                 :song/arrangement-notes   "Notes"
                 :song/origin              nil
                 :song/lyrics              "Lyrics"
                 :forum.topic/topic-id     nil}]
               {:transact-w-nils? true
                :on-success       [[:app.songs/trigger-song-edited song-id]]}]
              [:app.datastar/respond-sse
               [[:app.datastar.sse/redirect (str "/song/" song-id)]]]]
             (actions/update-song-action
              (state-for system)
              {:song-edit {:song-id             (str song-id)
                           :title               " New Title "
                           :active?             false
                           :solo-info           ""
                           :composition-credits " "
                           :arrangement-credits " Arranger "
                           :arrangement-notes   "Notes"
                           :origin              ""
                           :lyrics              "Lyrics"
                           :topic-id            ""}})))))

  (testing "returns validation errors for a blank title"
    (let [{:keys [conn] :as system} (new-system)
          song-id                   (random-uuid)]
      (seed-song! conn {:song/song-id song-id
                        :song/title   "Old Title"
                        :song/active? true})
      (is (= [[:app.datastar/respond-sse [[:app.datastar.sse/merge-signals {:loading false :targetid false}]]]
              [:app.datastar/assoc-state
               [:song-edit]
               {:song-id (str song-id)
                :title   ""
                :active? true
                :_error  {:title {:error "Song Title is required."}
                          :_top  {:error "Please fix the errors in the form."}}}]]
             (actions/update-song-action
              (state-for system)
              {:song-edit {:song-id (str song-id)
                           :title   ""
                           :active? true}}))))))

(deftest create-song-tx-data-test
  (testing "builds create tx data with initial play count"
    (let [song-id (random-uuid)]
      (is (= [{:song/song-id     song-id
               :song/title       "Watermelon Man"
               :song/active?     true
               :song/total-plays 0}]
             (actions/create-song-tx-data
              {:song-id (str song-id)
               :title   "Watermelon Man"
               :active? true}))))))

(deftest delete-song-tx-data-test
  (testing "retracts the song, its sheet music, and logged plays"
    (let [{:keys [conn] :as system} (new-system)
          song-id                   (random-uuid)
          gig-id                    (random-uuid)
          play-id                   (random-uuid)
          sheet-id                  (random-uuid)]
      @(d/transact conn [{:song/song-id song-id
                          :song/title   "Bella Ciao"
                          :song/active? true}
                         {:gig/gig-id   gig-id
                          :gig/title    "Probe"
                          :gig/date     #inst "2026-04-29T00:00:00.000-00:00"
                          :gig/gig-type :gig.type/probe
                          :gig/status   :gig.status/confirmed}
                         {:section/name    "Trumpets"
                          :section/active? true}])
      @(d/transact conn [{:played/play-id  play-id
                          :played/song     [:song/song-id song-id]
                          :played/gig      [:gig/gig-id gig-id]
                          :played/rating   :play-log/okay
                          :played/emphasis :play-log/not-played}
                         {:sheet-music/sheet-id sheet-id
                          :sheet-music/song     [:song/song-id song-id]
                          :sheet-music/section  [:section/name "Trumpets"]
                          :sheet-music/title    "Bella Ciao Trumpet.pdf"
                          :file/webdav-path     "/songs/Bella Ciao Trumpet.pdf"}])
      (is (= {:recalc-play-stats? true
              :tx-data            [[:db/retractEntity [:played/play-id play-id]]
                                   [:db/retractEntity [:sheet-music/sheet-id sheet-id]]
                                   [:db/retractEntity [:song/song-id song-id]]]}
             (actions/delete-song-tx-data (:db (state-for system)) song-id))))))
