(ns app.songs.edit.views
  (:require
   [app.datastar :as d*]
   [app.form :as form]
   [app.queries :as q]
   [app.songs.edit.actions :as actions]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]))

(defn- validate-field-action [req field]
  (str "$song-edit.validate-field = '"
       (name field)
       "'; @post('"
       (d*/act req ::actions/validate-song-field)
       "')"))

(defn- validate-field-attrs [req form-state field]
  (let [error (form/field-error form-state field)]
    {:hint                            error
     :data-invalid                    (when error "true")
     :data-bind                       (str "song-edit." (name field))
     :data-on:blur                    (validate-field-action req field)
     :data-on:keydown__debounce.500ms (validate-field-action req field)}))

(defn- input [label name value attrs]
  [:wa-input (merge {:label      label
                     :name       name
                     :value      (form/text-value value)
                     :appearance "outlined"}
                    attrs)])

(defn- textarea [label name value attrs]
  (let [error         (:hint attrs)
        wrapper-attrs (:wrapper-attrs attrs)
        attrs         (dissoc attrs :hint :wrapper-attrs)]
    [:div (merge {:class "songs-edit-textarea-field"} wrapper-attrs)
     [:label {:for name} label]
     [:textarea (merge {:id             name
                        :name           name
                        :class          "songs-edit-textarea"
                        :rows           6
                        :data-auto-size "true"}
                       attrs)
      (form/text-value value)]
     (when error
       [:span {:class "wa-caption-s text-danger"}
        error])]))

(defn- element-ref [name suffix]
  (str (str/replace name #"[^A-Za-z0-9_]" "_") "_" suffix))

(defn- markdown-textarea [label name value attrs]
  (let [ref (element-ref name "markdown_editor")]
    (textarea label name value (merge {:class                 "songs-edit-textarea markdown-editor hidden songs-edit-wide"
                                       :data-auto-size        "true"
                                       :data-ref              ref
                                       :data-init__delay.10ms (str "MarkdownEditor($" ref ")")
                                       :wrapper-attrs         {:data-ignore-morph ""}}
                                      attrs))))

(defn- active-input [{:keys [tr]} form-state]
  [:label {:class "wa-cluster wa-gap-xs wa-align-items-center songs-edit-active songs-edit-wide"}
   [:input (cond-> {:type      "checkbox"
                    :name      "active?"
                    :value     "true"
                    :data-bind "song-edit.active?"}
             (:active? form-state) (assoc :checked true))]
   [:span (tr [:song/active])]])

(defn- song-remove-dialog-id [{:song/keys [song-id]}]
  (ui2/remove-dialog-id "song" song-id))

(defn song-remove-dialog [{:keys [tr] :as req} {:song/keys [title] :as song}]
  (ui2/remove-dialog
   {:id            (song-remove-dialog-id song)
    :label         (tr [:action/confirm-generic])
    :cancel-label  (tr [:action/cancel])
    :confirm-label (tr [:action/confirm-delete])
    :confirm-attrs {:data-id     "song-edit-delete"
                    :data-action (d*/act req ::actions/delete-song)}}
   [:p (tr [:action/confirm-delete-song] [title])]))

(defn- save-button []
  [button/Button {:appearance         "filled"
                  :variant            "brand"
                  :type               "submit"
                  :form               "song-edit-form"
                  :data-attr:disabled "!!$loading && $loading !== 'song-edit'"
                  :data-attr:loading  "$loading === 'song-edit'"}
   [:i18n/tr :action/save]])

(defn- create-toolbar []
  [page-toolbar/PageToolbar
   {::page-toolbar/breadcrumb
    [breadcrumb/Breadcrumb
     {}
     [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-songs-home)}
      [:i18n/tr :repertoire/title]]
     [breadcrumb/BreadcrumbItem [:i18n/tr :repertoire/add-song]]]
    ::page-toolbar/mobile-back
    [button/Button {:appearance "plain"
                    :href       (urls/link-songs-home)}
     [ico/Icon {::ico/library :phosphor
                ::ico/name    :arrow-left
                :slot         "start"}]
     [:i18n/tr :repertoire/title]]
    ::page-toolbar/actions
    [[button/Button {:appearance "plain"
                     :href       (urls/link-songs-home)}
      [:i18n/tr :action/cancel]]
     (save-button)]
    :aria-label [:i18n/tr :repertoire/edit-toolbar-label]}])

(defn- edit-toolbar [song]
  (let [song-url (urls/link-song song)]
    [page-toolbar/PageToolbar
     {::page-toolbar/breadcrumb
      [breadcrumb/Breadcrumb
       {}
       [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-songs-home)}
        [:i18n/tr :repertoire/title]]
       [breadcrumb/BreadcrumbItem {::breadcrumb/href song-url}
        (:song/title song)]
       [breadcrumb/BreadcrumbItem [:i18n/tr :action/edit]]]
      ::page-toolbar/mobile-back
      [button/Button {:appearance "plain"
                      :href       song-url}
       [ico/Icon {::ico/library :phosphor
                  ::ico/name    :arrow-left
                  :slot         "start"}]
       (:song/title song)]
      ::page-toolbar/actions
      [[button/Button {:appearance "plain"
                       :href       song-url}
        [:i18n/tr :action/cancel]]
       (save-button)]
      ::page-toolbar/overflow-items
      [[:wa-dropdown-item {:variant     "danger"
                           :data-dialog (str "open " (song-remove-dialog-id song))}
        [:i18n/tr :action/delete]]]
      ::page-toolbar/overflow-label [:i18n/tr :action/more-actions]
      :aria-label                    [:i18n/tr :repertoire/edit-toolbar-label]}]))

(defn- edit-header [{:keys [tr]} {:song/keys [active? title]}]
  [page-header/PageHeader
   {:title [:span {:class "wa-cluster wa-gap-xs wa-align-items-center songs-edit-title"}
            title
            (ui2/active-badge tr active?)]}])

(defn create-header [_req]
  [page-header/PageHeader {:title [:i18n/tr :repertoire/add-song]}])

(defn- song->form [{:song/keys [active? arrangement-credits arrangement-notes composition-credits lyrics origin solo-info song-id title]
                    :forum.topic/keys [topic-id]}]
  {:song-id             (str song-id)
   :title               (form/text-value title)
   :active?             (boolean active?)
   :solo-info           (form/text-value solo-info)
   :composition-credits (form/text-value composition-credits)
   :arrangement-credits (form/text-value arrangement-credits)
   :arrangement-notes   (form/text-value arrangement-notes)
   :origin              (form/text-value origin)
   :lyrics              (form/text-value lyrics)
   :topic-id            (form/text-value topic-id)
   :_error              {}})

(defn- create->form []
  {:song-id             ""
   :title               ""
   :active?             true
   :solo-info           ""
   :composition-credits ""
   :arrangement-credits ""
   :arrangement-notes   ""
   :origin              ""
   :lyrics              ""
   :topic-id            ""
   :_error              {}})

(defn- form-state [{:keys [page-state]} song]
  (merge (song->form song) (:song-edit page-state)))

(defn- create-form-state [{:keys [page-state]}]
  (merge (create->form) (:song-edit page-state)))

(defn- with-upload-endpoint [attrs song-id]
  (cond-> attrs
    song-id (assoc :data-image-upload-endpoint (urls/link-song-image-upload song-id))))

(defn- main-fields
  ([req form-state]
   (main-fields req form-state nil))
  ([{:keys [tr] :as req} form-state song-id]
   (let [field          #(validate-field-attrs req form-state %)
         markdown-field #(with-upload-endpoint (field %) song-id)]
     (ui2/section-card
      {:title    (tr [:song/background-title])
       :divider? true}
      [:div {:class "songs-edit-form-grid"}
       (input (tr [:song/title]) "title" (:title form-state) (merge {:required true} (field :title)))
       (active-input req form-state)
       (input (tr [:song/solo-count]) "solo-info" (:solo-info form-state) (field :solo-info))
       (textarea (tr [:song/composition-credits]) "composition-credits" (:composition-credits form-state) (merge {:class "songs-edit-textarea"}
                                                                                                                 (field :composition-credits)))
       (textarea (tr [:song/arrangement-credits]) "arrangement-credits" (:arrangement-credits form-state) (merge {:class "songs-edit-textarea"}
                                                                                                                 (field :arrangement-credits)))
       (markdown-textarea (tr [:song/origin]) "origin" (:origin form-state) (markdown-field :origin))
       (markdown-textarea (tr [:song/arrangement-notes]) "arrangement-notes" (:arrangement-notes form-state) (markdown-field :arrangement-notes))
       (markdown-textarea (tr [:song/lyrics]) "lyrics" (:lyrics form-state) (markdown-field :lyrics))]))))

(defn- forum-fields [req form-state]
  [:wa-details {:summary            ((:tr req) [:nav/forum])
                :data-preserve-attr "open"}
   [:div {:class "songs-edit-form-grid"}
    (input ((:tr req) [:nav/forum]) "topic-id" (:topic-id form-state) (merge {:class "songs-edit-wide"}
                                                                             (validate-field-attrs req form-state :topic-id)))]])

(defn- song-form [{:keys [action create? form-state req]} & children]
  (into
   [:form {:id             "song-edit-form"
           :class          "wa-stack wa-gap-xl"
           :data-id        "song-edit"
           :data-action    (d*/act req action)
           :data-on:submit "evt.preventDefault();"
           :data-signals   (d*/->signals {:song-edit (dissoc form-state :_error)})}
    (when-not create?
      [:input {:type      "hidden"
               :name      "song-id"
               :value     (:song-id form-state)
               :data-bind "song-edit.song-id"}])]
   children))

(defn edit-form [req {:song/keys [song-id] :as song}]
  (let [form-state (form-state req song)]
    (song-form {:req        req
                :action     ::actions/update-song
                :form-state form-state}
               (main-fields req form-state song-id)
               (forum-fields req form-state)
               (when-let [top-error (form/field-error form-state :_top)]
                 [:wa-callout {:appearance "outlined"
                               :variant    "danger"}
                  top-error]))))

(defn create-form [req]
  (let [form-state (create-form-state req)]
    (song-form {:req        req
                :action     ::actions/create-song
                :create?    true
                :form-state form-state}
               (main-fields req form-state)
               (forum-fields req form-state)
               (when-let [top-error (form/field-error form-state :_top)]
                 [:wa-callout {:appearance "outlined"
                               :variant    "danger"}
                  top-error]))))

(defn- edit-page [{:keys [db] :as req}]
  (let [song-id (http.util/path-param-uuid! req :song-id)
        song    (q/retrieve-song db song-id)]
    (if song
      (ui2/datastar-page*
       (ui2/markdown-editor-scripts)
       [page-surface/PageSurface
        {::page-surface/width   :standard
         ::page-surface/toolbar (edit-toolbar song)}
        [:div {:class "wa-stack wa-gap-2xl"}
         (edit-header req song)
         (edit-form req song)]]
       (song-remove-dialog req song))
      (throw (ex-info "Song not found" {:app/error-type :app.error.type/not-found
                                        :song/song-id   song-id})))))

(defn- create-page [req]
  (ui2/datastar-page*
   (ui2/markdown-editor-scripts)
   [page-surface/PageSurface
    {::page-surface/width   :standard
     ::page-surface/toolbar (create-toolbar)}
    [:div {:class "wa-stack wa-gap-2xl"}
     (create-header req)
     (create-form req)]]))

(defn page [req]
  (if (http.util/path-param req :song-id)
    (edit-page req)
    (create-page req)))

(d*/refresh-all!)
