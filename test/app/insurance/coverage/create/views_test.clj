(ns app.insurance.coverage.create.views-test
  (:require
   [app.insurance.coverage.create.views]
   [app.urls :as urls]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def translations
  {[:action/back] "Back"
   [:action/next] "Next"
   [:insurance/no-photos] "No photos have been uploaded yet."
   [:instrument/images] "Images"
   [:instrument/photo-upload] "Photo Upload"
   [:instrument/photo-upload-subtitle] "Upload photos from several angles."
   [:instrument.coverage/create-step-coverage] "Coverage"
   [:instrument.coverage/create-step-instrument] "Instrument"
   [:instrument.coverage/create-step-photos] "Photos"
   [:instrument.coverage/create-steps] "Coverage creation steps"
   [:instrument.coverage/create-subtitle] "Add an instrument and register it for %1."
   [:instrument.coverage/create-title] "Add Instrument Coverage"
   [:instrument.coverage/upload-complete] "Upload complete."
   [:instrument.coverage/upload-drop-label] "Choose photos to upload"
   [:instrument.coverage/upload-error] "Upload failed."
   [:instrument.coverage/upload-help] "PNG, JPG, or GIF up to 10 MB."
   [:instrument.coverage/upload-progress] "Uploading photos…"
   [:nav/insurance] "Insurance"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path args]
   (reduce (fn [s [idx arg]]
             (str/replace s (str "%" (inc idx)) (str arg)))
           (tr path)
           (map-indexed vector args))))

(def policy-id #uuid "00000000-0000-0000-0000-000000000123")
(def instrument-id #uuid "00000000-0000-0000-0000-000000000456")
(def image-id #uuid "00000000-0000-0000-0000-000000000789")

(def policy
  {:insurance.policy/policy-id policy-id
   :insurance.policy/name      "Test Policy"})

(defn request [instrument]
  {:path-params {:policy-id     policy-id
                 :instrument-id instrument-id}
   :params      {:redirect "/return"}
   :policy      policy
   :instrument  instrument
   :system      {:env {:app-base-url "https://example.test"}}
   :tr          tr})

(defn render-photos-page [req]
  (if-let [page (ns-resolve 'app.insurance.coverage.create.views 'photos-page)]
    (page req)
    ""))

(deftest photos-page-renders-upload-controls-and-preserves-wizard-navigation
  (let [page-html (render-photos-page
                   (request {:instrument/instrument-id instrument-id
                             :instrument/name          "Test Trumpet"}))]
    (is (= {:photo-step?      true
            :empty-state?     true
            :file-input?      true
            :upload-endpoint? true
            :upload-script?   true
            :back-link?       true
            :next-link?       true}
           {:photo-step?      (and (str/includes? page-html "<li class=\"current\">")
                                   (str/includes? page-html "aria-label=\"Photos\" aria-current=\"step\""))
            :empty-state?     (str/includes? page-html "No photos have been uploaded yet.")
            :file-input?      (and (str/includes? page-html "type=\"file\"")
                                   (str/includes? page-html "name=\"file\"")
                                   (str/includes? page-html "multiple"))
            :upload-endpoint? (str/includes? page-html (str "data-upload-endpoint=\"" (urls/link-instrument-image-upload instrument-id) "\""))
            :upload-script?   (str/includes? page-html "window.InsuranceCoverageUpload")
            :back-link?       (str/includes? page-html (str "href=\""
                                                            (str/replace (urls/link-coverage-create-edit policy-id instrument-id "/return") "&" "&amp;")
                                                            "\""))
            :next-link?       (str/includes? page-html (str "href=\"" (urls/link-coverage-create3 policy-id instrument-id "/return") "\""))}))))

(deftest photos-page-renders-existing-instrument-photos
  (let [page-html (render-photos-page
                   (request {:instrument/instrument-id instrument-id
                             :instrument/name          "Test Trumpet"
                             :instrument/images        [{:image/image-id image-id}]}))]
    (is (= {:thumbnail? true
            :full?      true
            :alt?       true}
           {:thumbnail? (str/includes? page-html (urls/absolute-link-instrument-image-thumbnail {:app-base-url "https://example.test"} instrument-id image-id))
            :full?      (str/includes? page-html (urls/absolute-link-instrument-image-full {:app-base-url "https://example.test"} instrument-id image-id))
            :alt?       (str/includes? page-html "alt=\"Test Trumpet\"")}))))
