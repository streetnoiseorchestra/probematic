(ns app.songs.detail.actions-test
  (:require
   [app.songs.detail.actions :as actions]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]))

(defn new-system []
  (tc/new-system "songs-detail-actions"))

(defn state-for [{:keys [conn]} page-state]
  {:db         (d/db conn)
   :env        {:ig/system {:app.ig/profile :prod}}
   :page-state page-state})

(defn seed-song-and-section! [conn song-id]
  @(d/transact conn [{:song/song-id song-id
                      :song/title   "Bella Ciao"
                      :song/active? true}
                     {:section/name     "Trumpets"
                      :section/active?  true
                      :section/position 1}]))

(deftest add-sheet-music-tx-data-test
  (is (= [{:sheet-music/sheet-id :db/gen-uuid
           :sheet-music/song     [:song/song-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"]
           :sheet-music/section  [:section/name "Trumpets"]
           :sheet-music/title    "Bella Ciao Trumpet.pdf"
           :file/webdav-path     "Noten - Scores/Bella Ciao/Bella Ciao Trumpet.pdf"}]
         (actions/add-sheet-music-tx-data
          #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          "Trumpets"
          "Noten - Scores/Bella Ciao/Bella Ciao Trumpet.pdf"))))

(deftest selected-path-test
  (is (= {"/foo/bar"       "foo/bar"
          "/foo//bar"      "foo//bar"
          "/../foo"        "foo"
          "/foo/../../bar" "bar"}
         (into {}
               (map (fn [path]
                      [path (@#'actions/selected-path {:file-browser {:selected-path path}})]))
               ["/foo/bar" "/foo//bar" "/../foo" "/foo/../../bar"]))))

(deftest add-sheet-music-action-test
  (testing "adds the selected file to the picker target section and closes the picker"
    (let [{:keys [conn] :as system} (new-system)
          song-id                   (random-uuid)
          page-state                {:file-browser
                                     {:song-sheet-music
                                      {:root-dir "/Noten - Scores"
                                       :target   {:song-id      (str song-id)
                                                  :section-name "Trumpets"}}}}]
      (seed-song-and-section! conn song-id)
      (is (= [[:db/transact
               [{:sheet-music/sheet-id :db/gen-uuid
                 :sheet-music/song     [:song/song-id song-id]
                 :sheet-music/section  [:section/name "Trumpets"]
                 :sheet-music/title    "Bella Ciao Trumpet.pdf"
                 :file/webdav-path     "Noten - Scores/Bella Ciao/Bella Ciao Trumpet.pdf"}]
               {:jobs [["sync-song" {:song-id song-id}
                        {:queue "start-within-15m" :max-attempts 25}]]}]
              [:app.datastar/assoc-state [:file-browser :song-sheet-music] nil]
              [:app.datastar/respond-sse
               [[:app.datastar.sse/merge-signals
                 {:file-browser {:selected-path nil
                                 :target-dir    nil}}]]]]
             (actions/add-sheet-music-action
              (state-for system page-state)
              {:file-browser {:picker-id     "song-sheet-music"
                              :selected-path "/Noten - Scores/Bella Ciao/Bella Ciao Trumpet.pdf"}})))))

  (testing "does not transact when no selected file is present"
    (is (= [[:app.datastar/respond-sse
             [[:app.datastar.sse/merge-signals
               {:file-browser {:selected-path nil
                               :target-dir    nil}}]]]]
           (actions/add-sheet-music-action
            (state-for (new-system)
                       {:file-browser {:song-sheet-music {:target {:song-id      (str (random-uuid))
                                                                   :section-name "Trumpets"}}}})
            {:file-browser {:picker-id "song-sheet-music"}})))))

(deftest remove-sheet-music-action-test
  (let [song-id  #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        sheet-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        signals  {:file-browser {:song-id (str song-id) :sheet-id (str sheet-id)}}]
    (is (= [[:db/transact
             [[:db/retractEntity [:sheet-music/sheet-id sheet-id]]]
             {:jobs [["sync-song" {:song-id song-id}
                      {:queue "start-within-15m" :max-attempts 25}]]}]]
           (actions/remove-sheet-music-action
            {:env {:ig/system {:app.ig/profile :prod}}}
            signals)))
    (is (= {}
           (get-in (actions/remove-sheet-music-action
                    {:env {:ig/system {:app.ig/profile :dev}}}
                    signals)
                   [0 2])))))
