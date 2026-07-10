(ns app.insurance.coverage.create.views-test
  (:require
   [app.insurance.coverage.create.views :as sut]
   [app.urls :as urls]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]))

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

(def policy-id
  #uuid "00000000-0000-0000-0000-000000000123")

(def instrument-id
  #uuid "00000000-0000-0000-0000-000000000456")

(def image-id
  #uuid "00000000-0000-0000-0000-000000000789")

(def policy
  {:insurance.policy/policy-id policy-id
   :insurance.policy/name      "Test Policy"})

(defn request
  [instrument]
  {:path-params {:policy-id     policy-id
                 :instrument-id instrument-id}
   :params      {:redirect "/return"}
   :policy      policy
   :instrument  instrument
   :system      {:env {:app-base-url "https://example.test"}}
   :tr          tr})

(defn photos-view
  [instrument]
  (sut/photos-page-content
   (request instrument)
   policy
   instrument
   "/return"))

(deftest photo-upload
  (testing "The newly created instrument does not have any photos yet."
    (let [instrument {:instrument/instrument-id instrument-id
                      :instrument/name          "Test Trumpet"}
          view       (photos-view instrument)
          steps      (l/select 'li view)
          input      (l/select-one 'input view)]
      (testing "Photos is the current wizard step."
        (is (= {:steps        [{:label "Instrument" :state #{"complete"}}
                               {:label "Photos" :state #{"current"}}
                               {:label "Coverage" :state #{"empty"}}]
                :current-step {:aria-label "Photos" :aria-current "step"}}
               {:steps        (mapv (fn [step]
                                      {:label (l/text step)
                                       :state (:class (l/attrs step))})
                                    steps)
                :current-step (select-keys
                               (l/attrs (l/select-one "[aria-current=step]" view))
                               [:aria-label :aria-current])})))
      (testing "The empty photo state explains that nothing has been uploaded."
        (is (= "Images No photos have been uploaded yet."
               (-> (l/select-one 'wa-callout view) l/text))))
      (testing "The file input accepts multiple images and targets the instrument upload endpoint."
        (is (= {:type                 "file"
                :name                 "file"
                :multiple             true
                :accept               "image/*"
                :data-upload-endpoint (urls/link-instrument-image-upload instrument-id)}
               (select-keys (l/attrs input)
                            [:type :name :multiple :accept :data-upload-endpoint]))))
      (testing "The page installs the upload handler used by the file input."
        (is (= {:input-handler "window.InsuranceCoverageUpload && window.InsuranceCoverageUpload(evt.target)"
                :script-count  1
                :handler-name? true}
               {:input-handler (:data-on:change (l/attrs input))
                :script-count  (count (l/select 'script view))
                :handler-name? (boolean
                                (re-find #"window\.InsuranceCoverageUpload"
                                         (-> (l/select-one 'script view) l/text)))})))
      (testing "Back and Next preserve the wizard destination."
        (is (= [{:label "Back"
                 :href  (urls/link-coverage-create-edit
                         policy-id instrument-id "/return")}
                {:label "Next"
                 :href  (urls/link-coverage-create3
                         policy-id instrument-id "/return")}]
               (mapv (fn [button]
                       {:label (l/text button)
                        :href  (:href (l/attrs button))})
                     (l/select :app.ui2.button/button view))))))))

(deftest existing-photos
  (testing "The newly created instrument already has an uploaded photo."
    (let [instrument {:instrument/instrument-id instrument-id
                      :instrument/name          "Test Trumpet"
                      :instrument/images        [{:image/image-id image-id}]}
          view       (photos-view instrument)
          photo-link (l/select-one 'a.insurance-photo-link view)
          image      (l/select-one 'img photo-link)]
      (testing "The thumbnail links to the full image and describes the instrument."
        (is (= {:link  {:href   (urls/absolute-link-instrument-image-full
                                 {:app-base-url "https://example.test"}
                                 instrument-id
                                 image-id)
                        :target "_blank"}
                :image {:src     (urls/absolute-link-instrument-image-thumbnail
                                  {:app-base-url "https://example.test"}
                                  instrument-id
                                  image-id)
                        :loading "lazy"
                        :alt     "Test Trumpet"}}
               {:link  (select-keys (l/attrs photo-link) [:href :target])
                :image (select-keys (l/attrs image) [:src :loading :alt])}))))))
