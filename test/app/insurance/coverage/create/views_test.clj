(ns app.insurance.coverage.create.views-test
  (:require
   [app.insurance.coverage.create.actions :as actions]
   [app.insurance.coverage.create.views :as sut]
   [app.test-common :as tc]
   [app.urls :as urls]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [jsonista.core :as j]
   [lookup.core :as l]
   [reitit.core :as r]))

(def translations
  {[:action/back] "Back"
   [:action/next] "Next"
   [:action/save] "Save"
   [:band-private] "Band or private"
   [:band-instrument] "Band instrument"
   [:band-instrument-description] "Played by the band."
   [:private-instrument] "Private instrument"
   [:private-instrument-description] "Paid for by the owner."
   [:insurance/coverage-for] "Coverage details for %1."
   [:insurance/coverage-types] "Coverage types"
   [:insurance/instrument-coverage] "Instrument Coverage"
   [:insurance/item-count] "Count"
   [:insurance/item-count-hint] "How many identical items are being insured?"
   [:insurance/no-photos] "No photos have been uploaded yet."
   [:insurance/value] "Value"
   [:insurance.policy-settings/no-coverage-types] "No coverage types configured."
   [:instrument/build-year] "Build year"
   [:instrument/category] "Category"
   [:instrument/category-hint] "What type of item is this?"
   [:instrument/create-subtitle] "Describe the instrument in detail."
   [:instrument/description] "Description"
   [:instrument/description-hint] "e.g., color or material"
   [:instrument/if-available] "if available"
   [:instrument/images] "Images"
   [:instrument/instrument] "Instrument"
   [:instrument/make] "Make"
   [:instrument/make-hint] "Which company manufactures the item?"
   [:instrument/model] "Model"
   [:instrument/model-hint] "What is the model number?"
   [:instrument/name] "Instrument name"
   [:instrument/name-hint] "e.g., trumpet, Yamaha, or tenor sax mouthpiece"
   [:instrument/owner] "Owner"
   [:instrument/photo-upload] "Photo Upload"
   [:instrument/photo-upload-subtitle] "Upload photos from several angles."
   [:instrument/separate-warning] "Use a separate form for each item."
   [:instrument/serial-number] "Serial number"
   [:instrument.coverage/insurer-id] "Harmonia ID"
   [:instrument.coverage/insurer-id-hint] "Enter the identifier assigned by Harmonia."
   [:instrument.coverage/private?-hint] "Is this a band instrument or private instrument?"
   [:instrument.coverage/value-hint] "The estimated replacement market value of the item."
   [:instrument.coverage/create-step-coverage] "Coverage"
   [:instrument.coverage/create-step-instrument] "Instrument"
   [:instrument.coverage/create-step-photos] "Photos"
   [:instrument.coverage/create-steps] "Coverage creation steps"
   [:instrument.coverage/create-title] "Add Instrument Coverage"
   [:instrument.coverage/create-subtitle] "Add an instrument and register it for %1."
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

(def base-coverage-type-id
  #uuid "00000000-0000-0000-0000-000000000abc")

(def extra-coverage-type-id
  #uuid "00000000-0000-0000-0000-000000000def")

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def policy
  {:insurance.policy/policy-id policy-id
   :insurance.policy/name      "Test Policy"
   :insurance.policy/coverage-types
   [{:insurance.coverage.type/type-id     base-coverage-type-id
     :insurance.coverage.type/name        "Base"
     :insurance.coverage.type/description "Base coverage"}
    {:insurance.coverage.type/type-id     extra-coverage-type-id
     :insurance.coverage.type/name        "Extended"
     :insurance.coverage.type/description "Extended coverage"}]})

(defn request
  [instrument]
  {:path-params {:policy-id     policy-id
                 :instrument-id instrument-id}
   :params      {:redirect "/return"}
   :policy      policy
   :instrument  instrument
   :system      {:env {:app-base-url "https://example.test"}}
   :tr          tr
   ::r/router   router})

(defn seed-insurance-team!
  [conn member-id]
  @(d/transact conn [{:team/team-id   (random-uuid)
                      :team/name      "Insurance Team"
                      :team/team-type :team.type/insurance
                      :team/members   [[:member/member-id member-id]]}]))

(defn member-request
  [instrument insurance-team-member?]
  (let [{:keys [conn member-id]} (tc/new-system "insurance-coverage-create-view")]
    (when insurance-team-member?
      (seed-insurance-team! conn member-id))
    (assoc (request instrument)
           :db (d/db conn)
           :session {:session/member {:member/member-id member-id}})))

(defn described-text
  [control view]
  (some->> control
           l/attrs
           :aria-describedby
           (str "#")
           (#(l/select-one % view))
           l/text))

(defn instrument-view
  []
  (let [{:keys [conn member-id]} (tc/new-system "insurance-coverage-create-instrument-view")
        category-id (random-uuid)]
    @(d/transact conn [{:member/member-id member-id
                        :member/name      "Ada"
                        :member/active?   true}
                       {:instrument.category/category-id category-id
                        :instrument.category/name        "Brass"
                        :instrument.category/code        "1"}])
    (let [req (assoc (request nil)
                     :db (d/db conn)
                     :session {:session/member {:member/member-id member-id}}
                     :path-params {:policy-id policy-id})]
      (sut/instrument-page-content req policy nil "/return"))))

(deftest instrument-step-hints
  (testing "A member is entering the instrument details in Step 1."
    (let [view   (instrument-view)
          fields [["input[name=instrument-name]"
                   "instrument-name-hint"
                   "e.g., trumpet, Yamaha, or tenor sax mouthpiece"]
                  ["select[name=category-id]"
                   "category-id-hint"
                   "What type of item is this?"]
                  ["input[name=make]"
                   "make-hint"
                   "Which company manufactures the item?"]
                  ["input[name=model]"
                   "model-hint"
                   "What is the model number? if available"]
                  ["input[name=serial-number]"
                   "serial-number-hint"
                   "if available"]
                  ["input[name=build-year]"
                   "build-year-hint"
                   "if available"]
                  ["textarea[name=description]"
                   "description-hint"
                   "e.g., color or material"]]]
      (testing "Each existing instrument hint is associated with its control."
        (is (= (mapv (fn [[selector hint-id hint]]
                       {:control      selector
                        :described-by hint-id
                        :hint         hint})
                     fields)
               (mapv (fn [[selector]]
                       (let [control (l/select-one selector view)]
                         {:control      selector
                          :described-by (:aria-describedby (l/attrs control))
                          :hint         (described-text control view)}))
                     fields)))))))

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
          input      (l/select-one 'input view)
          label      (l/select-one 'label.insurance-coverage-upload-zone view)]
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
      (testing "The upload label and existing format hint are associated with the file control."
        (is (= {:label-for    "coverage-create-photo-upload"
                :label        "Choose photos to upload"
                :described-by "coverage-create-photo-upload-hint"
                :hint         "PNG, JPG, or GIF up to 10 MB."}
               {:label-for    (:for (l/attrs label))
                :label        (-> (l/select-one 'span label) l/text)
                :described-by (:aria-describedby (l/attrs input))
                :hint         (described-text input view)})))
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

(def coverage-instrument
  {:instrument/instrument-id instrument-id
   :instrument/name          "Test Trumpet"
   :instrument/make          "Yamaha"
   :instrument/model         "YTR-8335"
   :instrument/owner         {:member/member-id #uuid "00000000-0000-0000-0000-000000000111"
                              :member/name      "Ada"}
   :instrument/category      {:instrument.category/category-id #uuid "00000000-0000-0000-0000-000000000222"
                              :instrument.category/name        "Brass"}})

(defn coverage-view
  ([policy]
   (coverage-view (member-request coverage-instrument false) policy))
  ([req policy]
   (sut/coverage-page-content req policy coverage-instrument "/return")))

(defn signals [view]
  (-> (l/select-one "form#coverage-create-coverage-form" view)
      l/attrs
      :data-signals
      j/read-value))

(defn action-keyword [value]
  (when (string? value)
    (let [action-ns   (second (re-find #"[?&]ns=([^&'\")]+)" value))
          action-name (second (re-find #"[?&]kw=([^&'\")]+)" value))]
      (when (and action-ns action-name)
        (keyword action-ns action-name)))))

(defn form-submit-button [form-id view]
  (some #(when (= {:type "submit" :form form-id}
                  (select-keys (l/attrs %) [:type :form]))
           %)
        (l/select :app.ui2.button/button view)))

(deftest coverage-step-form
  (testing "An insurance-team member is entering coverage details for the saved instrument."
    (let [view        (coverage-view (member-request coverage-instrument true) policy)
          form        (l/select-one "form#coverage-create-coverage-form" view)
          steps       (l/select 'li view)
          count-input (l/select-one "input[name=item-count]" view)
          value-input (l/select-one "input[name=value]" view)
          insurer-input (l/select-one "input[name=insurer-id]" view)
          ownership   (l/select-one "wa-radio-group[name=private-band]" view)
          checkboxes  (l/select 'wa-checkbox view)
          buttons     (l/select :app.ui2.button/button view)]
      (testing "Coverage is the current wizard step."
        (is (= {:steps        [{:label "Instrument" :state #{"complete"}}
                               {:label "Photos" :state #{"complete"}}
                               {:label "Coverage" :state #{"current"}}]
                :current-step {:aria-label "Coverage" :aria-current "step"}}
               {:steps        (mapv (fn [step]
                                      {:label (l/text step)
                                       :state (:class (l/attrs step))})
                                    steps)
                :current-step (select-keys
                               (l/attrs (l/select-one "[aria-current=step]" view))
                               [:aria-label :aria-current])})))
      (testing "The form initializes server-owned ids and safe defaults as Datastar signals."
        (is (= {"coverage-create"
                {"policy-id"      (str policy-id)
                 "instrument-id"  (str instrument-id)
                 "redirect"       "/return"
                 "item-count"     "1"
                 "value"          ""
                 "private-band"   "band"
                 "coverage-types" [(str base-coverage-type-id)]
                 "insurer-id"     ""}}
               (signals view))))
      (testing "The form dispatches the qualified create action through /act."
        (is (= ::actions/create-coverage
               (-> form l/attrs :data-action action-keyword))))
      (testing "Numeric and identifier inputs use native controls with Datastar bindings."
        (is (= [{:name "item-count" :type "number" :min 1 :step 1 :required true :data-bind "coverage-create.item-count"}
                {:name "value" :type "number" :min 1 :step 1 :required true :data-bind "coverage-create.value"}
                {:name "insurer-id" :type "text" :data-bind "coverage-create.insurer-id"}]
               (mapv #(select-keys (l/attrs %) [:name :type :min :step :required :data-bind])
                     [count-input value-input insurer-input]))))
      (testing "Step 3 hints are associated with their controls."
        (is (= {:count-hint     "How many identical items are being insured?"
                :value-hint     "The estimated replacement market value of the item."
                :ownership-hint "Is this a band instrument or private instrument?"
                :insurer-hint   "Enter the identifier assigned by Harmonia."}
               {:count-hint     (described-text count-input view)
                :value-hint     (described-text value-input view)
                :ownership-hint (-> (l/select-one "[slot=hint]" ownership) l/text)
                :insurer-hint   (described-text insurer-input view)})))
      (testing "Ownership is required and updates the ownership signal before validation."
        (let [attrs (l/attrs ownership)]
          (is (= {:value                  "band"
                  :required               true
                  :data-bind              "coverage-create.private-band"
                  :options                ["band" "private"]
                  :change-updates-signal? true}
                 {:value                  (:value attrs)
                  :required               (:required attrs)
                  :data-bind              (:data-bind attrs)
                  :options                (mapv (comp :value l/attrs) (l/select 'wa-radio ownership))
                  :change-updates-signal? (str/starts-with? (:data-on:change attrs)
                                                            "$coverage-create.private-band = evt.target.value;")}))))
      (testing "Coverage type choices are shown only for private ownership."
        (is (= "$coverage-create.private-band === 'private'"
               (-> (l/select-one 'div.insurance-coverage-edit-choice-list view)
                   l/attrs
                   :data-show))))
      (testing "The base type is enforced and each choice retains its explanatory text."
        (let [optional-handler (:data-on:change (l/attrs (second checkboxes)))
              extra-id        (str extra-coverage-type-id)]
          (is (= {:checkboxes [{:value       (str base-coverage-type-id)
                                :checked     true
                                :disabled    true
                                :description "Base coverage"}
                               {:value       extra-id
                                :description "Extended coverage"}]
                  :optional-handler
                  (str "if (evt.target.checked) { "
                       "$coverage-create.coverage-types = Array.from(new Set([...$coverage-create.coverage-types, '" extra-id "'])); "
                       "} else { "
                       "$coverage-create.coverage-types = $coverage-create.coverage-types.filter((id) => id !== '" extra-id "'); "
                       "}")}
                 {:checkboxes (mapv (fn [checkbox]
                                      (assoc (select-keys (l/attrs checkbox) [:value :checked :disabled])
                                             :description (-> (l/select-one 'small checkbox) l/text)))
                                    checkboxes)
                  :optional-handler optional-handler}))))
      (testing "Back preserves the redirect and Save submits the coverage form."
        (is (= [{:label "Back"
                 :href  (urls/link-coverage-create2 policy-id instrument-id "/return")}
                {:label "Save"
                 :type  "submit"
                 :form  "coverage-create-coverage-form"}]
               (mapv (fn [button]
                       (into {:label (l/text button)}
                             (remove (comp nil? val))
                             (select-keys (l/attrs button) [:href :type :form])))
                     buttons)))))))

(deftest harmonia-id-visibility
  (testing "Step 3 is rendered for insurance-team and ordinary members."
    (let [team-view     (coverage-view (member-request coverage-instrument true) policy)
          ordinary-view (coverage-view (member-request coverage-instrument false) policy)
          team-input    (l/select-one "input[name=insurer-id]" team-view)
          ordinary-form (l/select-one "form#coverage-create-coverage-form" ordinary-view)]
      (testing "Only the insurance-team member can see and edit the Harmonia ID."
        (is (= {:insurance-team {:name      "insurer-id"
                                 :data-bind "coverage-create.insurer-id"}
                :ordinary       nil}
               {:insurance-team (select-keys (l/attrs team-input) [:name :data-bind])
                :ordinary       (l/select-one "input[name=insurer-id]" ordinary-view)})))
      (testing "The ordinary member can still submit coverage without a Harmonia ID."
        (is (= {:insurer-id ""
                :action     ::actions/create-coverage
                :save?      true}
               {:insurer-id (get-in (signals ordinary-view) ["coverage-create" "insurer-id"])
                :action     (-> ordinary-form l/attrs :data-action action-keyword)
                :save?      (some? (form-submit-button "coverage-create-coverage-form" ordinary-view))}))))))

(deftest missing-coverage-types
  (testing "The policy does not have any configured coverage types."
    (let [view        (coverage-view (assoc policy :insurance.policy/coverage-types []))
          warning     (l/select-one 'wa-callout view)
          save-button (form-submit-button "coverage-create-coverage-form" view)]
      (testing "The page explains why coverage cannot be created."
        (is (= "No coverage types configured."
               (l/text warning))))
      (testing "The Save action is disabled."
        (is (true? (:disabled (l/attrs save-button))))))))

(deftest validation-errors
  (testing "Coverage validation failed after the member submitted invalid values."
    (let [req  (assoc (member-request coverage-instrument false)
                      :page-state
                      {:coverage-create
                       {:value "invalid"
                        :_error {:value {:error "Value must be a whole number greater than zero."}
                                 :_top  {:error "Please fix the errors in the form."}}}})
          view (coverage-view req policy)
          value-field (l/select-one
                       "div.insurance-coverage-create-field:has(input[name=value])"
                       view)
          value-input (l/select-one "input[name=value]" value-field)]
      (testing "The submitted value remains available for correction."
        (is (= "invalid"
               (:value (l/attrs value-input)))))
      (testing "The value error is attached to the value field."
        (is (= {:invalid "true"
                :message "Value must be a whole number greater than zero."}
               {:invalid (:data-invalid (l/attrs value-input))
                :message (-> (l/select-one '.text-danger value-field) l/text)})))
      (testing "The form-level error remains visible."
        (is (= "Please fix the errors in the form."
               (-> (l/select-one "wa-callout[variant=danger]" view) l/text)))))))
