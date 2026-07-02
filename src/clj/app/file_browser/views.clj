(ns app.file-browser.views
  (:require
   [app.config :as config]
   [app.datastar :as d*]
   [app.file-browser.actions :as actions]
   [app.humanize :as humanize]
   [app.sardine :as sardine]
   [app.ui2 :as ui2]
   [app.ui2.button :as button]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.icon :as ico]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(def content-type->filetype-icon
  {"application/pdf" "file-pdf-solid"
   "application/vnd.oasis.opendocument.text" "file-word-solid"
   "application/vnd.openxmlformats-officedocument.wordprocessingml.document" "file-word-solid"
   "application/vnd.oasis.opendocument.spreadsheet" "file-excel-solid"
   "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" "file-excel-solid"
   "audio/mpeg" "file-audio-solid"
   "audio/flac" "file-audio-solid"
   "audio/ogg" "file-audio-solid"})

(def extension->filetype-icon
  {"csv"  "file-csv-solid"
   "doc"  "file-word-solid"
   "docx" "file-word-solid"
   "gif"  "file-image-solid"
   "jpg"  "file-image-solid"
   "jpeg" "file-image-solid"
   "mp3"  "file-audio-solid"
   "mp4"  "file-video-solid"
   "ods"  "file-excel-solid"
   "odt"  "file-word-solid"
   "pdf"  "file-pdf-solid"
   "png"  "file-image-solid"
   "ppt"  "file-powerpoint-solid"
   "pptx" "file-powerpoint-solid"
   "svg"  "file-image-solid"
   "xls"  "file-excel-solid"
   "xlsx" "file-excel-solid"
   "zip"  "file-zipper-solid"})

(defn- extension [filename]
  (some-> filename
          fs/extension
          str/lower-case))

(defn file-icon-name [{:keys [content-type directory? name]}]
  (if directory?
    "folder-open"
    (or (get content-type->filetype-icon content-type)
        (get extension->filetype-icon (extension name))
        "file-solid")))

(defn- directory-target-dir [{:keys [path]}]
  (actions/remote-path path))

(defn- strip-leading-slash [path]
  (when path
    (str/replace path #"^/" "")))

(defn- selected-file-path [{:keys [path]}]
  (strip-leading-slash (actions/remote-path path)))

(defn- directory-action [req picker-id target-dir]
  (->expr
   (evt.preventDefault)
   (set! $file-browser.picker-id ~(name picker-id))
   (set! $file-browser.target-dir ~target-dir)
   (@post ~(d*/act req ::actions/set-current-dir))))

(defn- select-action [req picker-id action-key selected-path]
  (->expr
   (evt.preventDefault)
   (set! $file-browser.picker-id ~(name picker-id))
   (set! $file-browser.selected-path ~selected-path)
   (@post ~(d*/act req action-key))))

(defn file-row [req picker-id select-action-key file]
  (let [{:keys [content-length directory? file? name]} file]
    [:tr {:class "file-browser-row"}
     [:td
      [:a {:href          "#"
           :class         "file-browser-row-link"
           :data-on:click (if directory?
                            (directory-action req picker-id (directory-target-dir file))
                            (select-action req picker-id select-action-key (selected-file-path file)))}
       [ico/Icon {::ico/library :snoico
                  ::ico/name    (file-icon-name file)}]
       [:span {:class "file-browser-row-name"} name]]]
     [:td {:class "file-browser-row-size"}
      (when file?
        (humanize/filesize content-length))]]))

(defn file-table [{:keys [tr] :as req} picker-id select-action-key files]
  [:div {:class "table-shell"}
   [:table {:class "file-browser-table"}
    [:thead
     [:tr
      [:th (tr [:file/name])]
      [:th (tr [:file/size])]]]
    [:tbody
     (for [file files]
       (file-row req picker-id select-action-key file))]]])

(defn- breadcrumb-action [req picker-id target-dir]
  (->expr
   (evt.preventDefault)
   (set! $file-browser.picker-id ~(name picker-id))
   (set! $file-browser.target-dir ~target-dir)
   (@post ~(d*/act req ::actions/set-current-dir))))

(defn- component-paths [path]
  (assert (str/starts-with? path "/") "Path must be absolute")
  (assert (not (str/ends-with? path "/")) "Path must not end with slash")
  (let [sub (str/split path #"/")]
    (map (fn [i]
           (str "/" (str/join "/" (subvec sub 1 (inc i)))))
         (range 1 (inc (count (re-seq #"/" path)))))))

(defn file-breadcrumb [req picker-id root-dir current-dir]
  (into [breadcrumb/Breadcrumb {::breadcrumb/separator "/"}]
        (for [path (filter #(actions/within-root? root-dir %)
                           (component-paths current-dir))]
          [breadcrumb/BreadcrumbItem {::breadcrumb/href "#"
                                      :data-on:click (breadcrumb-action req picker-id path)}
           (fs/file-name path)])))

(defn- picker-files [req root-dir current-dir]
  (let [current-dir-exists? (sardine/dir-exists? (:webdav req) current-dir)
        current-dir         (if current-dir-exists? current-dir root-dir)]
    {:current-dir current-dir
     :files       (sardine/list-directory (:webdav req) current-dir)}))

(defn- close-action [req picker-id]
  (->expr
   (evt.preventDefault)
   (set! $file-browser.picker-id ~(name picker-id))
   (@post ~(d*/act req ::actions/close-picker))))

(defn- close-control [{:keys [tr] :as req} picker-id]
  [button/Button {:appearance    "outlined"
                  :class         "file-browser-back"
                  :data-on:click (close-action req picker-id)}
   (tr [:action/back])])

(defn file-picker-panel
  "Renders an open Datastar-backed file picker panel.

  Required: `:picker-id`, `:state`, and `:select-action`.
  `:state` is the picker state stored in page state by `app.file-browser.actions`.
  The select action receives `:file-browser.selected-path` in the Datastar signals."
  [req {:keys [picker-id select-action state subtitle title]}]
  (when (:open? state)
    (let [picker-id              (actions/picker-key picker-id)
          {:keys [root-dir current-dir]} state
          {:keys [files current-dir]} (picker-files req root-dir current-dir)]
      [:div {:class "file-browser-panel"}
       [:div {:class "file-browser-header"}
        [:div {:class "file-browser-title-block"}
         [:h3 {:class "file-browser-title"} title]
         (when subtitle
           [:p {:class "file-browser-subtitle"} subtitle])]
        (close-control req picker-id)]
       (file-breadcrumb req picker-id root-dir current-dir)
       (file-table req picker-id select-action files)])))

(defn- open-demo-action [req root-dir current-dir]
  (->expr
   (set! $file-browser.picker-id ~(name actions/default-picker-id))
   (set! $file-browser.root-dir ~root-dir)
   (set! $file-browser.current-dir ~current-dir)
   (@post ~(d*/act req ::actions/open-picker))))

(defn page [{:keys [page-state system tr] :as req}]
  (let [env         (:env system)
        root-dir    (config/nextcloud-path-sheet-music env)
        current-dir (or (config/nextcloud-path-current-songs env) root-dir)
        picker      (get-in page-state [:file-browser actions/default-picker-id])]
    (ui2/datastar-page
     [:div {:class        "wa-stack wa-gap-l"
            :data-signals (d*/->signals {:file-browser {:picker-id     nil
                                                        :target-dir    nil
                                                        :selected-path nil}})}
      (ui2/page-header
       {:heading (tr [:file/choose-file])
        :actions [[button/Button {:appearance    "filled"
                                  :variant       "brand"
                                  :data-on:click (open-demo-action req root-dir current-dir)}
                   (tr [:file/choose-file])]]})
      (file-picker-panel req {:picker-id     actions/default-picker-id
                              :state         picker
                              :title         (tr [:file/choose-file])
                              :select-action ::actions/select-file})])))

(d*/refresh-all!)
