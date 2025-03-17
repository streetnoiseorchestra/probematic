(ns app.file-browser.views
  (:require
   [app.icons :as icon]
   [app.sardine :as sardine]
   [app.humanize :as humanize]
   [app.i18n :as i18n]
   [app.ui :as ui]
   [app.file-utils :as fu]
   [ctmx.core :as ctmx]))

(defn file-icon-for [{:keys [content-type name directory?]}]
  (if directory?
    icon/folder-solid
    (condp contains? content-type
      #{"application/pdf"} icon/file-pdf-solid
      #{"application/vnd.oasis.opendocument.text" "application/vnd.openxmlformats-officedocument.wordprocessingml.document"} icon/file-word-solid
      #{"application/vnd.oasis.opendocument.spreadsheet" "application/vnd.oasis.opendocument.text"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"} icon/file-excel-solid
      #{"audio/mpeg" "audio/flac" "audio/ogg"} icon/file-audio-solid
      icon/file-solid)))

(defn file-row [{:keys [endpoint target values] :as target-params} root-dir current-dir idx {:keys [directory? file? name path full-path content-type content-length] :as file}]
  [:tr
   [:td {:class "whitespace-nowrap py-4 pl-4 pr-3 text-sm font-medium text-gray-900 sm:pl-6 lg:pl-8  hover:bg-gray-100 "}
    [:button (merge {:class "cursor-pointer flex w-full"}
                    (if directory? {:hx-get "/choose-file/traverse-dir" :hx-target "#file-picker"
                                    :hx-vals
                                    {:current-dir current-dir :root-dir root-dir :target-dir (str "/" path) :target-params (pr-str target-params)}}
                        {:hx-post endpoint
                         :hx-target (or target "#file-picker")
                         :hx-vals (merge values {:selected-path path})}))

     ((file-icon-for file) {:class "h-5 w-5 mr-2"}) name]]
   [:td {:class "whitespace-nowrap px-3 py-4 text-sm text-gray-500"}
    (when file?
      (humanize/filesize content-length))]])

(defn file-table [tr target-params root-dir current-dir files]
  [:table {:class "min-w-full divide-y divide-gray-300"}
   [:thead {:class "bg-gray-50"}
    [:th {:class "py-3.5 pl-4 pr-3 text-left text-sm font-semibold text-gray-900 sm:pl-6 lg:pl-8"} (tr [:file/name])]
    [:th {:class "px-3 py-3.5 text-left text-sm font-semibold text-gray-900"} (tr [:file/size])]]
   [:tbody {:class "divide-y divide-gray-200 bg-white"}
    (map-indexed (partial file-row target-params root-dir current-dir) files)]])

(defn file-breadcrumb [tr target-params root-dir current-dir]
  [:nav {:class "bg-white flex pl-4 sm:pl-6 py-4", :aria-label "Breadcrumb"}
   [:ol {:role "list", :class "flex items-center space-x-0"}
    (map-indexed (fn [idx path]
                   (let [name (fu/basename path)]
                     [:li
                      [:div {:class "flex items-center"}
                       [:svg {:class "h-5 w-5 shrink-0 text-gray-300", :xmlns "http://www.w3.org/2000/svg", :fill "currentColor", :viewbox "0 0 20 20", :aria-hidden "true"}
                        [:path {:d "M5.555 17.776l8-16 .894.448-8 16-.894-.448z"}]]
                       [:button {:hx-get "/choose-file/traverse-dir" :hx-target "#file-picker"
                                 :hx-vals {:current-dir current-dir :root-dir root-dir :target-dir path :target-params (pr-str target-params)}
                                 :class "ml-0 text-sm font-medium text-gray-500 hover:text-gray-700"} name]]]))
                 (fu/component-paths current-dir))]])

(defn- file-picker-main
  [req target-params root-dir current-dir]
  (let [tr (i18n/tr-from-req req)
        current-dir-exists? (sardine/dir-exists? (:webdav req) current-dir)
        files (if current-dir-exists?
                (sardine/list-directory (:webdav req) current-dir)
                (sardine/list-directory (:webdav req) root-dir))
        current-dir (if current-dir-exists? current-dir root-dir)]
    [:div {:id "file-picker"}
     (file-breadcrumb tr target-params root-dir current-dir)
     (file-table tr target-params root-dir current-dir files)]))

(defn file-picker
  "Renders a file picker interface.
  The directory tree is limited to root-dir and children.
     WARNING: the root dir limitation is purely a UI thing. A malicious user could craft a request to the server to traverse
              any arbitrary directory.
  The current-dir is the open directory.
  target-params is a map containing
     :endpoint - the endpoint to POST the :selected-path too
     :values   - a map of values that are POSTed alongside the :selected-path
     :cancel-endpoint  - endpoint to GET to abort the file picking process
     :target (optional) - the hx-target to swap at after the file is selected
  "
  [req target-params root-dir current-dir]
  (let [tr (i18n/tr-from-req req)]
    [:div {}
     [:div {:class "sm:flex sm:items-center  pl-4 sm:pl-6 mb-2"}
      [:div {:class "sm:flex-auto"}
       [:h1 {:class "text-xl font-semibold text-gray-900"}
        (tr [:file/choose-file])]
       [:p {:class "mt-2 text-sm text-gray-700"} "A table of placeholder stock market data that does not make any sense."]]
      [:div {:class "mt-4 sm:mt-0 sm:ml-16 sm:flex-none"}
       (ui/button :label (tr [:action/cancel]) :priority :white)]]
     (file-picker-main req target-params root-dir current-dir)]))

(defn file-picker-panel
  "Like file-picker but rendered inside a ui/panel"
  [req {:keys [target-params id title subtitle root-dir current-dir]}]
  (let [tr (i18n/tr-from-req req)]
    (ui/panel {:title title :id id
               :subtitle subtitle
               :buttons (ui/link-button :class "cursor-pointer" :label (tr [:action/cancel]) :priority :white
                                        :hx-target (:cancel-target target-params)
                                        :hx-get (:cancel-endpoint target-params))}
              (file-picker-main req target-params root-dir current-dir))))

(ctmx/defcomponent ^:endpoint traverse-dir [req root-dir current-dir target-dir ^:edn target-params]
  (file-picker-main req target-params root-dir (fu/strip-trailing-slash (fu/normalize-path target-dir))))

(ctmx/defcomponent ^:endpoint choose-file-page [req]
  traverse-dir
  ;; for dev only
  ;; (file-picker req {:endpoint "/foo-bar" :values {:song-id "123"}} "/Noten - Scores" "/Noten - Scores/aktuelle Stücke/Kingdom Come")
  )
