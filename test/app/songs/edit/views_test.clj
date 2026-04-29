(ns app.songs.edit.views-test
  (:require
   [app.songs.edit.views :as views]
   [app.test-common :as tc]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [reitit.core :as r]))

(def translations
  {[:action/cancel]              "Cancel"
   [:action/confirm-delete]      "Yes, delete it"
   [:action/confirm-delete-song] "Are you sure you want to delete the song %1?"
   [:action/confirm-generic]     "Are you sure?"
   [:action/delete]              "Delete"
   [:action/edit]                "Edit"
   [:action/save]                "Save"
   [:nav/forum]                  "Forum"
   [:nav/songs]                  "Repertoire"
   [:song/active]                "Active?"
   [:song/arrangement-credits]   "Arranged By"
   [:song/arrangement-notes]     "Arrangement Info"
   [:song/background-title]      "Background"
   [:song/composition-credits]   "Composition By"
   [:song/create-subtitle]       ""
   [:song/create-title]          "Add Song"
   [:song/lyrics]                "Lyrics"
   [:song/origin]                "Origin"
   [:song/solo-count]            "# Solos"
   [:song/title]                 "Song Title"})

(defn tr
  ([k]
   (get translations k (name (last k))))
  ([k args]
   (reduce-kv (fn [s idx arg]
                (str/replace s (str "%" (inc idx)) (str arg)))
              (tr k)
              (vec args))))

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(defn req
  ([conn]
   {::r/router  router
    :db         (d/db conn)
    :tr         tr
    :page-state {}})
  ([conn song-id]
   (assoc (req conn) :path-params {:song-id (str song-id)})))

(defn seed-song! [conn song]
  @(d/transact conn [song]))

(deftest create-page-renders-datastar-form
  (let [{:keys [conn]} (tc/new-system "songs-edit-create-view")
        html           (views/page (req conn))]
    (is (str/includes? html "songs-edit-page"))
    (is (str/includes? html "Add Song"))
    (is (str/includes? html "data-action=\"/act?ns=app.songs.edit.actions&amp;kw=create-song\""))
    (is (str/includes? html "data-bind=\"song-edit.title\""))
    (is (str/includes? html "data-bind=\"song-edit.active?\""))))

(deftest edit-page-renders-existing-song-and-delete-dialog
  (let [{:keys [conn]} (tc/new-system "songs-edit-edit-view")
        song-id        (random-uuid)]
    (seed-song! conn {:song/song-id             song-id
                      :song/title               "Watermelon Man"
                      :song/active?             true
                      :song/solo-info           "Alto"
                      :song/composition-credits "Herbie Hancock"})
    (let [html (views/page (req conn song-id))]
      (testing "renders the edit form"
        (is (str/includes? html "Watermelon Man"))
        (is (str/includes? html "data-action=\"/act?ns=app.songs.edit.actions&amp;kw=update-song\""))
        (is (str/includes? html (str "value=\"" song-id "\"")))
        (is (str/includes? html "Herbie Hancock")))
      (testing "renders markdown upload wiring for existing songs"
        (is (str/includes? html (str "data-image-upload-endpoint=\"/song-media/" song-id "\""))))
      (testing "renders the delete action"
        (is (str/includes? html "data-action=\"/act?ns=app.songs.edit.actions&amp;kw=delete-song\""))
        (is (str/includes? html "Are you sure you want to delete the song Watermelon Man?"))))))
