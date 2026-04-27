(ns app.gigs.edit.views
  (:require
   [app.datastar :as d*]
   [app.html :as html]
   [app.gigs.domain :as domain]
   [app.gigs.ui :as gigs.ui]
   [app.queries :as q]
   [app.ui :as ui]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]
   [tick.core :as t]))

(def extra-head
  [[:link {:rel  "stylesheet"
           :href "/css/easymde.min@2.18.0.css"}]
   [:script {:src "/js/easymde.min@2.18.0.js"}]
   [:script
    (html/raw
     "window.MarkdownEditor = function MarkdownEditor(target) {
        if (!target || target.dataset.easymdeInitialized === 'true') return;
        target.dataset.easymdeInitialized = 'true';
        const imageUploadEndpoint = target.getAttribute('data-image-upload-endpoint');
        const hasUpload = !!imageUploadEndpoint;
        const maxSizeMB = 10;
        const maxSizeB = 1024 * 1024 * maxSizeMB;
        const easyMDE = new EasyMDE({
          element: target,
          spellChecker: false,
          forceSync: true,
          promptURLs: true,
          uploadImage: hasUpload,
          autoDownloadFontAwesome: false,
          previewImagesInEditor: true,
          toolbar: [
            'bold',
            'italic',
            'strikethrough',
            'heading',
            '|',
            'quote',
            'code',
            'unordered-list',
            'ordered-list',
            'clean-block',
            '|',
            'link',
            'upload-image',
            'table',
            'horizontal-rule',
            '|',
            'preview',
            'side-by-side',
            'fullscreen',
          ],
          imageUploadFunction: (file, onSuccess, onError) => {
            if (file.size > maxSizeB) {
              onError(`File is too big! Maximum size is ${maxSizeMB}MB.`);
              return;
            }

            const validMimeTypes = [
              'image/jpeg',
              'image/png',
              'image/gif',
              'image/jpg',
            ];
            if (!validMimeTypes.includes(file.type)) {
              onError('Invalid file type. Only JPG, PNG, and GIF files are allowed.');
              return;
            }

            let formData = new FormData();
            formData.append('file', file);

            let xhr = new XMLHttpRequest();

            xhr.onreadystatechange = function () {
              if (xhr.readyState !== 4) return;
              if (xhr.status === 201) {
                const response = JSON.parse(xhr.responseText);
                onSuccess(response['file-url']);
              } else {
                const response = JSON.parse(xhr.responseText);
                onError(response.error);
              }
            };

            xhr.onerror = function () {
              onError('XMLHttpRequest error.');
            };

            xhr.open('POST', imageUploadEndpoint, true);
            xhr.send(formData);
          },
        });
        return easyMDE;
      };

      window.InitializeMarkdownEditors = function InitializeMarkdownEditors(target) {
        const root = target || document;
        root.querySelectorAll('textarea.markdown-editor').forEach(window.MarkdownEditor);
      };")]])

(defn- date-value [value]
  (some-> value t/date str))

(defn- time-value [value]
  (some-> value str))

(defn- text-value [value]
  (or value ""))

(defn- option [value label selected-value]
  [:wa-option {:value    value
               :selected (= value selected-value)}
   label])

(defn- status-select [{:keys [tr]} status]
  (let [selected (some-> status name)]
    (into
     [:wa-select {:label      (tr [:gig/status])
                  :name       "status"
                  :value      selected
                  :required   true
                  :appearance "outlined"}]
     (for [status domain/statuses]
       (option (name status) (tr [status]) selected)))))

(defn- gig-type-select [{:keys [tr]} gig-type]
  (let [selected (some-> gig-type name)]
    (into
     [:wa-select {:label      (tr [:gig/gig-type])
                  :name       "gig-type"
                  :value      selected
                  :required   true
                  :appearance "outlined"}]
     (for [gig-type domain/gig-types]
       (option (name gig-type) (tr [gig-type]) selected)))))

(defn- member-option [selected-member-id member]
  (let [member-id (:member/member-id member)
        value     (str member-id)]
    (option value (ui/member-nick member) (some-> selected-member-id str))))

(defn- member-select [label name selected-member members]
  (into
   [:wa-select {:label      label
                :name       name
                :value      (some-> selected-member :member/member-id str)
                :appearance "outlined"}
    [:wa-option {:value ""} " - "]]
   (for [member members]
     (member-option (:member/member-id selected-member) member))))

(defn- input [label name value attrs]
  [:wa-input (merge {:label      label
                     :name       name
                     :value      (text-value value)
                     :appearance "outlined"}
                    attrs)])

(defn- textarea [label name value attrs]
  [:div {:class "gigs-edit-textarea-field"}
   [:label {:for name} label]
   [:textarea (merge {:id         name
                      :name       name
                      :class      "gigs-edit-textarea"
                      :rows       6
                      :data-auto-size "true"}
                     attrs)
    (text-value value)]])

(defn- element-ref [name suffix]
  (str (str/replace name #"[^A-Za-z0-9_]" "_") "_" suffix))

(defn- markdown-textarea [label name value attrs]
  (let [ref (element-ref name "markdown_editor")]
    (textarea label name value (merge {:class          "gigs-edit-textarea markdown-editor hidden"
                                       :data-auto-size "true"
                                       :data-ref       ref
                                       :data-init      (str "MarkdownEditor($" ref ")")}
                                      attrs))))

(defn- form-actions [{:keys [tr]} gig]
  [:div {:class "wa-cluster wa-gap-xs wa-justify-content-end"}
   [:wa-button {:appearance "outlined"
                :href       (urls/link-gig gig)}
    (tr [:action/cancel])]
   [:wa-button {:appearance "outlined"
                :variant    "danger"
                :disabled   true}
    (tr [:action/delete])]
   [:wa-button {:appearance "filled"
                :variant    "brand"
                :disabled   true}
    (tr [:action/save])]])

(defn- edit-header [{:keys [tr] :as req} {:gig/keys [title gig-type status] :as gig}]
  [:header {:class "gigs-detail-header wa-stack wa-gap-m"}
   [:wa-breadcrumb
    [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
    [:wa-breadcrumb-item {:href (urls/link-gigs-home)}
     (tr [:nav/gigs])]
    [:wa-breadcrumb-item {:href (urls/link-gig gig)}
     title]
    [:wa-breadcrumb-item (tr [:action/edit])]]
   [:section {:class "wa-stack wa-gap-l"}
    [:div {:class "wa-flank:end wa-align-items-start"}
     [:div {:class "wa-stack wa-gap-2xs"}
      [:div {:class "wa-cluster wa-gap-xs wa-align-items-center gigs-detail-title"}
       [:h1 (tr [:action/edit])]
       (when status
         (gigs.ui/gig-status-icon status {:class "gigs-detail-status-icon"}))]
      [:span {:class "wa-caption-s"}
       (str title " · " (tr [gig-type]))]]
     (form-actions req gig)]]])

(defn- main-fields [{:keys [db tr] :as req} {:gig/keys [call-time contact date description end-date end-time gig-type leader location more-details outfit pay-deal post-gig-plans rehearsal-leader1 rehearsal-leader2 set-time status title] :as gig}]
  (let [members (q/members-for-select-active db)]
    (ui2/section-card
     {:title    (tr [:gig/gig-info])
      :divider? true}
     [:div {:class "gigs-edit-form-grid"}
      (input (tr [:gig/title]) "title" title {:required true})
      (status-select req status)
      (gig-type-select req gig-type)
      (input (tr [:gig/date]) "date" (date-value date) {:type "date" :required true})
      (input (tr [:gig/end-date]) "end-date" (date-value end-date) {:type "date"})
      (member-select (tr [:gig/contact]) "contact" contact members)
      (input (tr [:gig/call-time]) "call-time" (time-value call-time) {:type "time" :required true})
      (input (tr [:gig/set-time]) "set-time" (time-value set-time) {:type "time"})
      (input (tr [:gig/end-time]) "end-time" (time-value end-time) {:type "time"})
      (input (tr [:gig/location]) "location" location {})
      (input (tr [:gig/outfit]) "outfit" (or outfit (tr [:orange-and-green])) {})
      (input (tr [:gig/pay-deal]) "pay-deal" pay-deal {})
      (input (tr [:gig/leader]) "leader" leader {})
      (when (domain/probe? gig)
        (list
         (member-select (tr [:gig/rehearsal-leader1]) "rehearsal-leader1" rehearsal-leader1 members)
         (member-select (tr [:gig/rehearsal-leader2]) "rehearsal-leader2" rehearsal-leader2 members)))
      (input (tr [:gig/post-gig-plans]) "post-gig-plans" post-gig-plans {:class "gigs-edit-wide"})
      (markdown-textarea (tr [:gig/more-details]) "more-details" more-details {:placeholder (tr [:gig/more-details-placeholder])
                                                                               :class       "gigs-edit-textarea markdown-editor hidden gigs-edit-wide"})
      (textarea (tr [:gig/description]) "description" description {:class "gigs-edit-textarea gigs-edit-wide"})])))

(defn- notification-fields [{:keys [tr]}]
  (ui2/section-card
   {:title    "Notifications"
    :divider? true}
   [:div {:class "wa-stack wa-gap-s"}
    [:wa-checkbox {:name  "notify?"
                   :value "true"}
     (tr [:gig/email-about-change?])]]))

(defn- forum-fields [_req {:forum.topic/keys [topic-id]}]
  (ui2/section-card
   {:title    "Advanced"
    :subtitle "Forum topic controls."
    :divider? true}
   [:div {:class "gigs-edit-form-grid"}
    [:wa-checkbox {:name  "takeover-topic?"
                   :value "true"}
     "Takeover Forum Topic"]
    (input "Forum Topic ID" "topic-id" topic-id {:class "gigs-edit-wide"})]))

(defn- edit-form [req gig]
  [:form {:id       "gig-edit-form"
          :class    "wa-stack wa-gap-xl"
          :onsubmit "event.preventDefault();"}
   [:input {:type  "hidden"
            :name  "gig-id"
            :value (:gig/gig-id gig)}]
   (main-fields req gig)
   (notification-fields req)
   (forum-fields req gig)
   (form-actions req gig)])

(defn page [{:keys [db] :as req}]
  (let [gig-id (http.util/path-param-uuid! req :gig/gig-id)
        gig    (q/retrieve-gig db gig-id)]
    (if gig
      (ui2/plain-page
       [:div {:class "wa-stack wa-gap-2xl gigs-edit-page"}
        (edit-header req gig)
        (edit-form req gig)])
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id})))))

(d*/refresh-all!)
