(ns app.file-browser.actions-test
  (:require
   [app.file-browser.actions :as actions]
   [clojure.test :refer [deftest is testing]]))

(deftest remote-path-test
  (is (= {nil                "/"
          ""                 "/"
          "/"                "/"
          "foo/bar"          "/foo/bar"
          "/foo/../bar/"     "/bar"
          "/foo//bar"        "/foo//bar"
          "/foo/./bar"       "/foo/bar"
          "/../foo"          "foo"
          "/foo/../../bar"   "bar"}
         (into {}
               (map (fn [path] [path (actions/remote-path path)]))
               [nil "" "/" "foo/bar" "/foo/../bar/" "/foo//bar" "/foo/./bar"
                "/../foo" "/foo/../../bar"]))))

(deftest within-root-test
  (is (= [true true false false]
         (mapv #(apply actions/within-root? %)
               [["/root" "/root"]
                ["/root" "/root/child"]
                ["/root" "/root-other"]
                ["/root" "/other/root"]]))))

(deftest open-picker-action-test
  (testing "opens a picker with normalized root and current directories"
    (is (= [[:app.datastar/assoc-state
             [:file-browser :song-sheet-music]
             {:open?      true
              :root-dir   "/Noten - Scores"
              :current-dir "/Noten - Scores/aktuelle Stücke"
              :target     {:song-id "song-1" :section-name "Trumpets"}}]]
           (actions/open-picker-action
            {}
            {:file-browser {:picker-id   "song-sheet-music"
                            :root-dir    "/Noten - Scores/"
                            :current-dir "/Noten - Scores/aktuelle Stücke/../aktuelle Stücke/"
                            :target      {:song-id "song-1" :section-name "Trumpets"}}}))))

  (testing "falls back to the root when current dir escapes the root"
    (is (= [[:app.datastar/assoc-state
             [:file-browser :song-sheet-music]
             {:open?      true
              :root-dir   "/Noten - Scores"
              :current-dir "/Noten - Scores"
              :target     nil}]]
           (actions/open-picker-action
            {}
            {:file-browser {:picker-id   "song-sheet-music"
                            :root-dir    "/Noten - Scores"
                            :current-dir "/Other"}})))))

(deftest set-current-dir-action-test
  (testing "navigates within the open picker root"
    (is (= [[:app.datastar/assoc-state
             [:file-browser :song-sheet-music :current-dir]
             "/Noten - Scores/current"]]
           (actions/set-current-dir-action
            {:page-state {:file-browser {:song-sheet-music {:root-dir "/Noten - Scores"}}}}
            {:file-browser {:picker-id  "song-sheet-music"
                            :target-dir "/Noten - Scores/archive/../current/"}}))))

  (testing "rejects navigation outside the open picker root"
    (is (= [[:app.datastar/assoc-state
             [:file-browser :song-sheet-music :current-dir]
             "/Noten - Scores"]]
           (actions/set-current-dir-action
            {:page-state {:file-browser {:song-sheet-music {:root-dir "/Noten - Scores"}}}}
            {:file-browser {:picker-id  "song-sheet-music"
                            :target-dir "/Other"}})))))

(deftest close-picker-action-test
  (is (= [[:app.datastar/assoc-state [:file-browser :song-sheet-music] nil]
          [:app.datastar/respond-sse
           [[:app.datastar.sse/merge-signals
             {:file-browser {:selected-path nil
                             :target-dir nil}}]]]]
         (actions/close-picker-action
          {}
          {:file-browser {:picker-id "song-sheet-music"}}))))
