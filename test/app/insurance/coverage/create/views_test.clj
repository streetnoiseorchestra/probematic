(ns app.insurance.coverage.create.views-test
  (:require
   [app.i18n :as i18n]
   [app.insurance.coverage.create.actions :as actions]
   [app.insurance.coverage.create.views :as sut]
   [app.test-common :as tc]
   [app.ui2.page-shell-test-support :as page-shell]
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
   [:insurance/add-coverage-separate-warning] "Use a separate form for each item."
   [:insurance/add-coverage-subtitle] "Add an instrument and register it for %1."
   [:insurance/add-coverage-title] "Add Instrument Coverage"
   [:insurance/coverage-for] "Coverage details for %1."
   [:insurance/coverage-create-steps] "Coverage creation steps"
   [:insurance/coverage-step] "Coverage"
   [:insurance/coverage-types] "Coverage types"
   [:insurance/insured-value-hint] "The estimated replacement market value of the item."
   [:insurance/instrument-coverage] "Instrument Coverage"
   [:insurance/instrument-step] "Instrument"
   [:insurance/insurer-id] "Harmonia ID"
   [:insurance/insurer-id-hint] "Enter the identifier assigned by Harmonia."
   [:insurance/item-count] "Count"
   [:insurance/item-count-hint] "How many identical items are being insured?"
   [:insurance/no-coverage-types] "No coverage types configured."
   [:insurance/no-photos] "No photos have been uploaded yet."
   [:insurance/ownership] "Band or private"
   [:insurance/ownership-band] "Band instrument"
   [:insurance/ownership-band-description] "Played by the band."
   [:insurance/ownership-hint] "Is this a band instrument or private instrument?"
   [:insurance/ownership-private] "Private instrument"
   [:insurance/ownership-private-description] "Paid for by the owner."
   [:insurance/photo-upload] "Photo Upload"
   [:insurance/photo-upload-subtitle] "Upload photos from several angles."
   [:insurance/photos] "Photos"
   [:insurance/photos-step] "Photos"
   [:insurance/upload-complete] "Upload complete."
   [:insurance/upload-drop-label] "Choose photos to upload"
   [:insurance/upload-error] "Upload failed."
   [:insurance/upload-help] "PNG, JPG, or GIF up to 10 MB."
   [:insurance/upload-progress] "Uploading photos…"
   [:insurance/value] "Value"
   [:instrument/build-year] "Build year"
   [:instrument/build-year-hint] "if available"
   [:instrument/category] "Category"
   [:instrument/category-hint] "What type of item is this?"
   [:instrument/create-subtitle] "Describe the instrument in detail."
   [:instrument/description] "Description"
   [:instrument/description-hint] "e.g., color or material"
   [:instrument/instrument] "Instrument"
   [:instrument/make] "Make"
   [:instrument/make-hint] "Which company manufactures the item?"
   [:instrument/model] "Model"
   [:instrument/model-hint-optional] "What is the model number? if available"
   [:instrument/name] "Instrument name"
   [:instrument/name-hint] "e.g., trumpet, Yamaha, or tenor sax mouthpiece"
   [:instrument/owner] "Owner"
   [:instrument/serial-number] "Serial number"
   [:instrument/serial-number-hint] "if available"
   [:nav/insurance] "Insurance"})

(defn tr
  ([path]
   (get translations path (name (last path))))
  ([path data]
   (reduce (fn [s [idx arg]]
             (str/replace s (str "%" (inc idx)) (str arg)))
           (tr path)
           (map-indexed vector
                        (if (map? data)
                          [(:policy-name data)]
                          data)))))

(defn resolve-view
  [view]
  (i18n/resolve-translations tr view))

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

(def third-coverage-type-id
  #uuid "00000000-0000-0000-0000-000000000fed")

(def router
  (r/router ["/act" {:name :app.routes.datastar/act}]))

(def policy
  {:insurance.policy/policy-id policy-id
   :insurance.policy/name      "Test Policy"
   :insurance.policy/coverage-types
   [{:insurance.coverage.type/type-id   base-coverage-type-id
     :insurance.coverage.type/name      "Base"
     :insurance.coverage.type/description "Base coverage"
     :insurance.coverage.type/required? true}
    {:insurance.coverage.type/type-id   extra-coverage-type-id
     :insurance.coverage.type/name      "Extended"
     :insurance.coverage.type/description "Extended coverage"
     :insurance.coverage.type/required? false}]})

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
      (resolve-view
       (sut/instrument-page-content req policy nil "/return")))))

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
  (resolve-view
   (sut/photos-page-content (request instrument) instrument)))

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
        (is (= "Photos No photos have been uploaded yet."
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
                                         (-> (l/select-one 'script view) l/text)))}))))))

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
   (resolve-view
    (sut/coverage-page-content req policy coverage-instrument "/return"))))

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

(defn coverage-choice-state
  [view]
  (let [checkboxes (l/select 'wa-checkbox view)]
    {:selected (set (get-in (signals view)
                            ["coverage-create" "coverage-types"]))
     :checked  (->> checkboxes
                    (filter #(= true (:checked (l/attrs %))))
                    (map (comp :value l/attrs))
                    set)
     :disabled (->> checkboxes
                    (filter #(= true (:disabled (l/attrs %))))
                    (map (comp :value l/attrs))
                    set)}))

(deftest coverage-step-form
  (testing "An insurance-team member is entering coverage details for the saved instrument."
    (let [view        (coverage-view (member-request coverage-instrument true) policy)
          form        (l/select-one "form#coverage-create-coverage-form" view)
          steps       (l/select 'li view)
          count-input (l/select-one "input[name=item-count]" view)
          value-input (l/select-one "input[name=value]" view)
          insurer-input (l/select-one "input[name=insurer-id]" view)
          ownership   (l/select-one "wa-radio-group[name=private-band]" view)
          checkboxes  (l/select 'wa-checkbox view)]
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
      (testing "The required type is enforced and each choice retains its explanatory text."
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
                  :optional-handler optional-handler})))))))

(deftest required-coverage-type-controls
  (testing "zero, one, or multiple explicit required types are selected in any policy order"
    (let [optional-a {:insurance.coverage.type/type-id base-coverage-type-id
                      :insurance.coverage.type/name "Optional A"
                      :insurance.coverage.type/required? false}
          optional-b {:insurance.coverage.type/type-id extra-coverage-type-id
                      :insurance.coverage.type/name "Optional B"
                      :insurance.coverage.type/required? false}
          required-a (assoc optional-a
                            :insurance.coverage.type/name "Required A"
                            :insurance.coverage.type/required? true)
          required-b {:insurance.coverage.type/type-id third-coverage-type-id
                      :insurance.coverage.type/name "Required B"
                      :insurance.coverage.type/required? true}
          cases      [{:label    "zero required"
                       :orders   [[optional-a optional-b]
                                  [optional-b optional-a]]
                       :expected #{}}
                      {:label    "one required"
                       :orders   [[optional-b required-a]
                                  [required-a optional-b]]
                       :expected #{(str base-coverage-type-id)}}
                      {:label    "multiple required"
                       :orders   [[required-a optional-b required-b]
                                  [required-b required-a optional-b]
                                  [optional-b required-b required-a]]
                       :expected #{(str base-coverage-type-id)
                                   (str third-coverage-type-id)}}]]
      (doseq [{:keys [label orders expected]} cases
              coverage-types orders]
        (is (= {:selected expected
                :checked  expected
                :disabled expected}
               (-> policy
                   (assoc :insurance.policy/coverage-types coverage-types)
                   coverage-view
                   coverage-choice-state))
            (str label " with policy order "
                 (mapv :insurance.coverage.type/name coverage-types)))))))

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
                :action     ::actions/create-coverage}
               {:insurer-id (get-in (signals ordinary-view) ["coverage-create" "insurer-id"])
                :action     (-> ordinary-form l/attrs :data-action action-keyword)}))))))

(deftest missing-coverage-types
  (testing "The policy does not have any configured coverage types."
    (let [empty-policy (assoc policy :insurance.policy/coverage-types [])
          req          (assoc (member-request coverage-instrument false)
                              :policy empty-policy)
          view         (resolve-view (sut/coverage-page req))
          warning      (l/select-one 'wa-callout view)
          save-action  (-> view page-shell/page-contract :actions second)]
      (testing "The page explains why coverage cannot be created."
        (is (= "No coverage types configured."
               (l/text warning))))
      (testing "The Save action is disabled."
        (is (true? (:disabled save-action)))))))

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
