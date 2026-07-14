(ns app.insurance.coverage.upload
  (:require
   [app.html :as html]
   [app.insurance.queries :as queries]
   [app.ui2 :as ui2]
   [app.ui2.icon :as ico]
   [app.urls :as urls]))

(defn- photo [instrument-name {:keys [thumbnail full]}]
  [:a {:href   full
       :target "_blank"
       :class  "insurance-photo-link"}
   [:img {:src     thumbnail
          :loading "lazy"
          :alt     instrument-name}]])

(defn upload-section
  [req instrument {:keys [complete-label drop-label empty-body empty-title
                          error-label help-label progress-label reload?
                          input-id subtitle title]}]
  (let [instrument-id (:instrument/instrument-id instrument)
        input-id      (or input-id (str "insurance-photo-upload-" instrument-id))
        hint-id       (str input-id "-hint")
        status-id     (str input-id "-status")
        photo-uris    (queries/image-uris req instrument)]
    (ui2/section-card
     {:title    title
      :subtitle subtitle
      :divider? true}
     [:div {:class "insurance-coverage-upload wa-stack wa-gap-m"}
      (if (seq photo-uris)
        (into [:div {:class "insurance-photo-grid wa-grid wa-gap-s"}]
              (map #(photo (:instrument/name instrument) %) photo-uris))
        (ui2/empty-state empty-title empty-body))
      [:label {:class "insurance-coverage-upload-zone" :for input-id}
       [ico/Icon {::ico/library :snoico
                  ::ico/name    :file-image-solid
                  :aria-hidden  true}]
       [:span drop-label]
       [:small {:id hint-id} help-label]
       [:input {:id                   input-id
                :type                 "file"
                :name                 "file"
                :multiple             true
                :accept               "image/*"
                :aria-describedby     hint-id
                :data-upload-endpoint (urls/link-instrument-image-upload instrument-id)
                :data-status-id       status-id
                :data-reload          (str (boolean reload?))
                :data-uploading-label progress-label
                :data-complete-label  complete-label
                :data-error-label     error-label
                :data-on:change       "window.InsuranceCoverageUpload && window.InsuranceCoverageUpload(evt.target)"}]]
      [:p {:id status-id :class "wa-caption-s wa-color-text-quiet"}]])))

(defn upload-script []
  [:script
   (html/raw
    "window.InsuranceCoverageUpload ||= async function(input) {
       const status = input.dataset.statusId ? document.getElementById(input.dataset.statusId) : null;
       const endpoint = input.dataset.uploadEndpoint;
       const files = Array.from(input.files || []);
       if (!endpoint || files.length === 0) return;
       if (status) status.textContent = input.dataset.uploadingLabel || '';
       try {
         for (const file of files) {
           const body = new FormData();
           body.append('file', file);
           const response = await fetch(endpoint, { method: 'POST', body });
           if (!response.ok) throw new Error('upload failed');
         }
         if (status) status.textContent = input.dataset.completeLabel || '';
         input.value = '';
         if (input.dataset.reload === 'true') window.location.reload();
       } catch (error) {
         if (status) status.textContent = input.dataset.errorLabel || '';
       }
     };")])
