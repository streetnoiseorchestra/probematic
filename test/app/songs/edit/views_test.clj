(ns app.songs.edit.views-test
  (:require
   [app.songs.edit.views :as views]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
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

(def request
  {::r/router  router
   :page-state {}
   :tr         tr})

(def song-id
  #uuid "00000000-0000-0000-0000-000000000201")

(def song
  {:song/song-id             song-id
   :song/title               "Watermelon Man"
   :song/active?             true
   :song/solo-info           "Alto"
   :song/composition-credits "Herbie Hancock"})

(defn action-keyword [value]
  (when (string? value)
    (let [action-ns   (second (re-find #"[?&]ns=([^&'\")]+)" value))
          action-name (second (re-find #"[?&]kw=([^&'\")]+)" value))]
      (when (and action-ns action-name)
        (keyword action-ns action-name)))))

(defn action-keywords [view]
  (->> (l/select '* view)
       (mapcat #(vals (or (l/attrs %) {})))
       (keep action-keyword)
       set))

(defn button-labelled [label view]
  (some #(when (= label (l/text %)) %)
        (l/select :app.ui2.button/button view)))

(deftest create-song
  (testing "A member is adding a new song."
    (let [header (views/create-header request)
          form   (views/create-form request)
          title  (l/select-one "wa-input[name=title]" form)
          active (l/select-one "input[name=active?]" form)]
      (testing "The page identifies the song-creation flow."
        (is (= "Add Song"
               (:title (l/attrs header)))))
      (testing "The form starts with an active blank song and submits the create action."
        (is (= {:action :app.songs.edit.actions/create-song
                :title  {:value ""
                         :data-bind "song-edit.title"
                         :required true}
                :active {:data-bind "song-edit.active?"
                         :checked   true}}
               {:action (-> (l/attrs form) :data-action action-keyword)
                :title  (select-keys (l/attrs title)
                                     [:value :data-bind :required])
                :active (select-keys (l/attrs active)
                                     [:data-bind :checked])}))))))

(deftest edit-song
  (testing "A member is editing an existing active song."
    (let [form          (views/edit-form request song)
          dialog        (views/song-remove-dialog request song)
          title         (l/select-one "wa-input[name=title]" form)
          song-id-input (l/select-one "input[name=song-id]" form)
          credits       (l/select-one "textarea[name=composition-credits]" form)
          delete-button (button-labelled "Delete" form)]
      (testing "The form contains the current song values and submits the update action."
        (is (= {:action  :app.songs.edit.actions/update-song
                :song-id (str song-id)
                :title   "Watermelon Man"
                :credits "Herbie Hancock"}
               {:action  (-> (l/attrs form) :data-action action-keyword)
                :song-id (:value (l/attrs song-id-input))
                :title   (:value (l/attrs title))
                :credits (l/text credits)})))
      (testing "Each markdown field uploads images to the existing song."
        (is (= [(str "/song-media/" song-id)
                (str "/song-media/" song-id)
                (str "/song-media/" song-id)]
               (mapv #(get (l/attrs %) :data-image-upload-endpoint)
                     (l/select 'textarea.markdown-editor form)))))
      (testing "Delete opens a matching confirmation dialog that submits the delete action."
        (is (= {:button-label "Delete"
                :opens        (str "open " (:id (l/attrs dialog)))
                :message      "Are you sure you want to delete the song Watermelon Man?"
                :actions      #{:app.songs.edit.actions/delete-song}}
               {:button-label (l/text delete-button)
                :opens        (:data-dialog (l/attrs delete-button))
                :message      (-> (l/select-one 'p dialog) l/text)
                :actions      (action-keywords dialog)}))))))
