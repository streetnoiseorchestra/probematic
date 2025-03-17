(ns app.songs.views
  (:require
   [app.config :as config]
   [app.errors :as errors]
   [app.file-browser.views :as file.browser.view]
   [app.file-utils :as fu]
   [app.i18n :as i18n]
   [app.icons :as icon]
   [app.markdown :as markdown]
   [app.queries :as q]
   [app.sardine :as sardine]
   [app.songs.controller :as controller]
   [app.ui :as ui]
   [app.urls :as url]
   [app.util :as util]
   [app.util.http :as util.http]
   [clojure.string :as clojure.string]
   [ctmx.core :as ctmx]
   [ctmx.response :as response]
   [hiccup.util]
   [jsonista.core :as j]
   [medley.core :as m]))

(declare song-sheet-music)
(declare song-sheet-music-edit)

(ctmx/defcomponent ^:endpoint song-sheet-music-selected [{:keys [db] :as req} section-name selected-path]
  (when (util/post? req)
    (let [song-id (util.http/path-param-uuid! req :song-id)
          db-after (controller/add-sheet-music! req song-id  section-name selected-path)]
      (song-sheet-music-edit (assoc req :db db-after)))))

(declare song-sheet-music)
(ctmx/defcomponent ^:endpoint song-sheet-music-picker [{:keys [db] :as req} section-name selected-path]
  (when (util/post? req)
    (let [tr (i18n/tr-from-req req)
          env (-> req :system :env)
          song-id (util.http/path-param-uuid! req :song-id)
          song-dir (or (q/sheet-music-dir-for-song db song-id)
                       (config/nextcloud-path-current-songs env))]
      (file.browser.view/file-picker-panel req
                                           {:target-params
                                            {:endpoint (util/comp-name #'song-sheet-music-selected)
                                             :values {:section-name section-name}
                                             :target (util/hash :comp/song-sheet-music-picker)
                                             :cancel-endpoint (util/comp-name #'song-sheet-music)
                                             :cancel-target (util/hash :comp/song-sheet-music-picker)}
                                            :id :comp/song-sheet-music-picker
                                            :title (tr [:song/choose-sheet-music-title])
                                            :subtitle (tr [:song/choose-sheet-music-subtitle] [section-name])
                                            :root-dir (config/nextcloud-path-sheet-music env)
                                            :current-dir song-dir}))))
(ctmx/defcomponent ^:endpoint song-sheet-music-remove [req sheet-id]
  (when (util/delete? req)
    (let [db-after (controller/remove-sheet-music! req (parse-uuid sheet-id))]
      (song-sheet-music-edit (assoc req :db db-after)))))

(defn sheet-music-section-sheet-rw [target tr {:sheet-music/keys [title sheet-id]}]
  (ui/rich-li {:icon icon/file-pdf-outline}
              (ui/rich-li-text {} title)
              (ui/rich-li-action (ui/button :size :2xsmall :priority :white-destructive :label (tr [:action/remove])
                                            :hx-target target
                                            :hx-delete (util/comp-name #'song-sheet-music-remove) :hx-vals {:sheet-id (str sheet-id)}))))

(defn sheet-music-section-rw [target tr {:section/keys [name default?] :as section}]
  (ui/dl-item (if default? (tr [:song/other-sheet-music]) name)
              [:div {:class "flex flex-col"}
               (ui/rich-ul {}
                           (map (partial sheet-music-section-sheet-rw target tr) (:sheet-music/_section section)))
               [:div {:class "flex justify-end mt-2"}
                (ui/button :size :xsmall :priority :white :label (tr [:action/add]) :icon icon/plus :class ""
                           :hx-vals {:section-name name}
                           :hx-target target
                           :hx-post (util/comp-name #'song-sheet-music-picker))]]))

(ctmx/defcomponent ^:endpoint song-sheet-music-edit [{:keys [db] :as req}]
  song-sheet-music-picker
  song-sheet-music-remove
  (let [tr              (i18n/tr-from-req req)
        song-id         (util.http/path-param-uuid! req :song-id)
        sections        (q/sheet-music-for-song db song-id)
        default-section (m/find-first :section/default? sections)
        sections        (remove :section/default? sections)
        target          (util/hash :comp/song-sheet-music)]
    (ui/panel
     {:title   (tr [:song/sheet-music-title]) :id :comp/song-sheet-music
      :buttons (ui/button :label (tr [:action/done]) :priority :primary :centered? true
                          :attr {:hx-get (util/comp-name #'song-sheet-music)
                                 :hx-vals {:edit? false}
                                 :hx-target target})}
     [:dl {:class "grid grid-cols-1 gap-x-4 gap-y-8 sm:grid-cols-2"}

      (sheet-music-section-rw target tr default-section)
      (map (partial sheet-music-section-rw target tr) sections)])))

(ctmx/defcomponent ^:endpoint song-sheet-music [{:keys [db] :as req}]
  song-sheet-music-edit
  (let [song-id         (util.http/path-param-uuid! req :song-id)
        {:song/keys [title active?] :as song} (q/retrieve-song db song-id)
        tr                                    (i18n/tr-from-req req)
        sections-sheets                       (q/sheet-music-for-song db song-id)
        default-section                       (m/find-first :section/default? sections-sheets)
        sections-sheets                       (remove :section/default? sections-sheets)]

    (ui/panel {:title   (tr [:song/sheet-music-title]) :id :comp/song-sheet-music
               :buttons (ui/button :label (tr [:action/edit]) :priority :white :centered? true
                                   :attr {:hx-get (util/comp-name #'song-sheet-music-edit)
                                          :hx-vals {:edit? true}
                                          :hx-target (util/hash :comp/song-sheet-music)})}
              [:dl {:class "grid grid-cols-1 gap-x-4 gap-y-8 sm:grid-cols-2"}
               (ui/dl-item (tr [:song/other-sheet-music])
                           (ui/rich-ul {}
                                       (map (fn [{:sheet-music/keys [title] :file/keys [webdav-path]}]
                                              (ui/rich-li {:icon icon/file-pdf-outline}
                                                          (ui/rich-li-text {} title)
                                                          (ui/rich-li-action-a :href (url/link-file-download webdav-path) :label (tr [:action/download]))))
                                            (:sheet-music/_section default-section))))
               (map (fn [section-sheet]
                      (ui/dl-item (:section/name section-sheet)
                                  (ui/rich-ul {}
                                              (map (fn [{:sheet-music/keys [title] :file/keys [webdav-path]}]
                                                     (ui/rich-li {:icon icon/file-pdf-outline}
                                                                 (ui/rich-li-text {} title)
                                                                 (ui/rich-li-action-a :href (url/link-file-download webdav-path) :label (tr [:action/download]))))
                                                   (:sheet-music/_section section-sheet)))
                                  "sm:col-span-1")) sections-sheets)])))

(defn song-comments [req song]
  [:div {:class "mb-8"}
   (when-let [topic-id (:forum.topic/topic-id song)]
     (ui/panel {:title "Comments"}
               (list
                [:div {:id "discourse-comments" :class "mb-6"}]
                [:script {:type "text/javascript"}
                 (hiccup.util/raw-string
                  (format
                   "
  DiscourseEmbed = { discourseUrl: 'https://forum.streetnoise.at/',
                     topicId: '%s' };

  (function() {
    var d = document.createElement('script'); d.type = 'text/javascript'; d.async = true;
    d.src = DiscourseEmbed.discourseUrl + 'javascripts/embed.js';
    (document.getElementsByTagName('head')[0] || document.getElementsByTagName('body')[0]).appendChild(d);
  })();
" topic-id))])))])

(ctmx/defcomponent ^:endpoint song-detail-page [{:keys [tr db] :as req} ^:boolean edit?]
  (let [song-id (util.http/path-param-uuid! req :song-id)
        song                             (if (util/post? req) (controller/update-song! req song-id)
                                             (q/retrieve-song db song-id))
        {:song/keys [title active? composition-credits
                     arrangement-credits arrangement-notes
                     last-rehearsal last-performance
                     total-plays total-performances total-rehearsals
                     origin solo-info lyrics]
         :forum.topic/keys [topic-id]} song
        comp-name                        (util/comp-namer #'song-detail-page)
        buttons (list
                 (ui/button :label (tr [:action/delete]) :priority :white-destructive :centered? true
                            :hx-confirm (tr [:action/confirm-delete-song] [title])
                            :class "mt-10 sm:mt-0 sm:ml-8 md:ml-0 md:mr-10"
                            :hx-delete (comp-name) :hx-target (hash "."))
                 (ui/button :label (tr [:action/cancel]) :priority :white :centered? true
                            :attr {:hx-get (comp-name) :hx-vals {:edit? false} :hx-target (hash ".")})
                 (ui/button :label (tr [:action/save]) :priority :primary  :centered? true))]

    (if (util/delete? req)
      (do
        (controller/delete-song! req song-id)
        (response/hx-redirect (url/link-songs-home)))
      [:form {:id id :hx-post (comp-name)}
       (if edit?
         (ui/page-header-full :title
                              (ui/text :label (tr [:song/title]) :name (path "title") :value title)
                              :subtitle [:div {:class "mt-2"}
                                         (ui/toggle-checkbox :label (tr [:song/active]) :name (path "active?") :checked? active?)]

                              :buttons-class "hidden sm:flex"
                              :buttons buttons)

         (ui/page-header :title title
                         :subtitle (ui/song-active-bubble song)
                         :buttons (list
                                   (ui/button :label (tr [:action/edit]) :priority :white :centered? true
                                              :attr {:hx-get (comp-name) :hx-vals {:edit? true} :hx-target (hash ".")})

                                   #_(ui/button :label (tr [:song/log-play])
                                                :priority :primary
                                                :centered? true
                                                :class "items-center justify-center "
                                                :attr {:href (str "/song/log-play/" song-id "/")}))))

       (if edit?
         (list
          (ui/panel {:title (tr [:song/background-title])}
                    (list
                     (ui/dl
                      (ui/dl-item (tr [:song/solo-count]) (ui/text :label "" :name (path "solo-info") :value solo-info :required? false))
                      (ui/dl-item (tr [:song/composition-credits])
                                  (ui/text :label "" :name (path "composition-credits") :value composition-credits :required? false :fit-height? true))
                      (ui/dl-item (tr [:song/arrangement-credits])
                                  (ui/text :label "" :name (path "arrangement-credits") :value arrangement-credits :required? false :fit-height? true))
                      (ui/dl-item (tr [:song/origin])
                                  (ui/textarea :label "" :name (path "origin") :value origin :required? false :markdown? true :markdown-upload-endpoint (url/link-song-image-upload song-id))
                                  "sm:col-span-3")
                      (ui/dl-item (tr [:song/arrangement-notes])
                                  (ui/textarea :label "" :name (path "arrangement-notes") :value arrangement-notes :required? false :fit-height? true :markdown? true :markdown-upload-endpoint (url/link-song-image-upload song-id))
                                  "sm:col-span-3")
                      (ui/dl-item (tr [:song/lyrics])
                                  (ui/textarea :label "" :name (path "lyrics") :value lyrics :required? false :fit-height? true :markdown? true :markdown-upload-endpoint (url/link-song-image-upload song-id))
                                  "sm:col-span-3"))
                     [:div {:class "px-4 sm:px-6"}
                      [:div {:class "justify-stretch mt-6 flex flex-col space-y-4 space-y-4 sm:hidden"}
                       buttons]]))
          (ui/panel {:title "Advanced"}
                    (ui/dl
                     (ui/dl-item "Forum Topic ID" (ui/text :label "" :name (path "topic-id") :value topic-id :required? false)))))

         (ui/panel {:title (tr [:song/background-title])}
                   (ui/dl
                    (ui/dl-item (tr [:song/solo-count]) solo-info)
                    (ui/dl-item (tr [:song/composition-credits])
                                composition-credits
                                "whitespace-pre-wrap")
                    (ui/dl-item (tr [:song/arrangement-credits]) arrangement-credits
                                "whitespace-pre-wrap")
                    (ui/dl-item (tr [:song/origin])
                                (markdown/render origin)
                                "whitespace-pre-wrap sm:col-span-3")
                    (ui/dl-item (tr [:song/arrangement-notes])
                                (markdown/render arrangement-notes)
                                "whitespace-pre-wrap sm:col-span-3")
                    (when lyrics
                      (ui/dl-item (tr [:song/lyrics])
                                  (markdown/render lyrics)
                                  "whitespace-pre-wrap sm:col-span-3")))))
       (ui/panel {:title (tr [:song/play-stats-title])}
                 (ui/dl
                  (ui/dl-item (tr [:song/total-plays]) total-plays)
                  (ui/dl-item (tr [:song/gig-count]) total-performances)
                  (ui/dl-item (tr [:song/probe-count]) total-rehearsals)
                  (ui/dl-item (tr [:song/last-played-gig]) [:a {:class "link-blue" :href (url/link-gig last-performance)} (:gig/title last-performance)])
                  (ui/dl-item (tr [:song/last-played-probe]) [:a {:class "link-blue" :href (url/link-gig last-rehearsal)}
                                                              (ui/gig-date last-rehearsal) " "
                                                              (:gig/title last-rehearsal)])))
       (song-sheet-music req)
       (song-comments req song)])))

(ctmx/defcomponent ^:endpoint song-new [req]
  (let [tr (i18n/tr-from-req req)]
    (if (util/post? req)
      (response/hx-redirect (url/link-song (controller/create-song! req)))
      [:div
       (ui/page-header :title (tr [:song/create-title])
                       :subtitle (tr [:song/create-subtitle]))

       (ui/panel {}
                 [:form {:hx-post (util/comp-name #'song-new) :class "space-y-8 divide-y divide-gray-200"}
                  [:div {:class "space-y-8 divide-y divide-gray-200 sm:space-y-5"}
                   [:div {:class "space-y-6 sm:space-y-5"}
                    [:div
                     [:h3 {:class "text-lg font-medium leading-6 text-gray-900"}]
                     [:p {:class "mt-1 max-w-2xl text-sm text-gray-500"}]]
                    [:div {:class "space-y-6 sm:space-y-5"}
                     (ui/text-left :label (tr [:song/title]) :id (path "title") :placeholder "Watermelon Man" :value (value "title"))
                     (ui/toggle-checkbox-left :id (path "active?") :label (tr [:song/active]) :checked? true :name (path "active?"))]]]

                  [:div {:class "pt-5"}
                   [:div {:class "flex justify-end"}
                    [:a {:href "/songs" :class "rounded-md border border-gray-300 bg-white py-2 px-4 text-sm font-medium text-gray-700 shadow-xs hover:bg-gray-50 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2"}
                     (tr [:action/cancel])]
                    [:button {:type "submit" :class "ml-3 inline-flex justify-center rounded-md border border-transparent bg-sno-orange-600 py-2 px-4 text-sm font-medium text-white shadow-xs hover:bg-sno-orange-700 focus:outline-hidden focus:ring-2 focus:ring-sno-orange-500 focus:ring-offset-2"}
                     (tr [:action/create])]]]])])))

(defn song-row [tr {:song/keys [title last-played-on score total-plays] :as song}]
  (let [style-icon "mr-1.5 h-5 w-5 shrink-0 text-gray-400"]
    [:a {:href (url/link-song song) :class "block hover:bg-gray-50"}
     [:div {:class "px-4 py-4 sm:px-6"}
      [:div {:class "flex items-center justify-between"}
       [:p {:class "truncate text-sm font-medium text-sno-orange-600"}
        title]
       [:div {:class "ml-2 flex shrink-0"}
        (ui/song-active-bubble song)]]
      [:div {:class "mt-2 sm:flex sm:justify-between"}
       [:div {:class "flex"}
        [:p {:class "flex items-center text-sm text-gray-500"}
         (icon/hashtag {:class style-icon})
         (tr [:song/total-plays])
         " "
         total-plays]
        [:p {:class "mt-2 flex items-center text-sm text-gray-500 mt-0 ml-6"}

         (icon/star-outline {:class style-icon})
         (tr [:song/score])
         " "
         score]]
       [:div {:class "mt-2 flex items-center text-sm text-gray-500 sm:mt-0"}
        (icon/calendar {:class style-icon})
        [:p (tr [:song/last-played]) " "
         (ui/datetime last-played-on)]]]]]))

(defn song-list [tr songs]
  (if (empty? songs)
    [:div {:class "divide-y divide-gray-200 bg-white p-4 text-center text-lg"}
     (tr [:song/search-empty])
     [:p {:class "text-base"}
      [:span "Not finding what you're looking for? Try changing the "]
      (icon/music-note-solid {:class "inline-block w-5 h-5 text-gray-500"})
      [:span "Active/Inactive filter "]]]

    [:ul {:role "list" :class "divide-y divide-gray-200 bg-white"}
     (map (fn [song]
            [:li
             (song-row tr song)]) songs)]))

(defn norm [s]
  (-> s
      (clojure.string/upper-case)
      (clojure.string/trim)))

(defn search-songs [{:keys [preset search] :as wut} all-songs]
  (->> all-songs
       (filter (fn [s]
                 (case preset
                   "all" true
                   "active" (:song/active? s)
                   "inactive" (not (:song/active? s))
                   true)))
       (filter (fn [s]
                 (clojure.string/includes?
                  (norm (:song/title s))
                  (norm (or search "")))))))

(ctmx/defcomponent ^:endpoint songs-filter [req]
  (let [tr (i18n/tr-from-req req)]
    [:div ;; search container
     [:label {:for id, :class "sr-only"} (tr [:action/search])]
     [:div {:class "relative"}
      [:div {:class "absolute inset-y-0 left-0 flex items-center pl-3 pointer-events-none"}
       (icon/search {:class  "w-5 h-5 text-gray-500 "
                         ;; dark:text-gray-400
                     })]

      [:input {:type "text"
                   ;; dark:bg-gray-700 dark:border-gray-600 dark:placeholder-gray-400 dark:text-white dark:focus:ring-blue-500 dark:focus:border-blue-500
               :class "block p-2 pl-10 text-sm text-gray-900 border border-gray-300 rounded-md w-80 bg-gray-50 focus:ring-blue-500 focus:border-blue-500"
               :name "q"
               :id id
               :value (get-in req [:query-params "q"])
               :hx-vals (util/remove-nils {:filter-preset (get-in req [:query-params "filter-preset"])})

               :placeholder "Watermelon Man"
               :hx-get "songs-list"
              ;; :hx-push-url "true"
               :hx-trigger "keyup changed delay:500ms"
               :hx-target (hash "../songs-list")}]]]))

(defn filter-param [{:keys [query-params] :as req}]
  {:preset (get query-params "filter-preset" "active")
   :search (get query-params "q" nil)
   :fields []})

(defn song-table-action-button [{:keys [tr] :as req}]
  (let [filter-spec (filter-param req)
        active-preset (:preset filter-spec)
        active-preset-label (get {"all" :song/filter-all
                                  "active" :song/filter-active
                                  "inactive" :song/filter-inactive} active-preset :song/filter-active)]
    (ui/action-menu :button-icon icon/music-note-solid
                    :label (tr [active-preset-label])
                    :hx-boost "true"
                    :sections [{:items [{:label (tr [:song/filter-all]) :href "?filter-preset=all" :active? (= active-preset "all")}
                                        {:label (tr [:song/filter-active]) :href "?filter-preset=active"  :active? (= active-preset "active")}
                                        {:label (tr [:song/filter-inactive]) :href "?filter-preset=inactive" :active? (= active-preset "inactive")}]}]
                    :id "song-table-actions")))

(ctmx/defcomponent ^:endpoint songs-list [{:keys [db] :as req} song]
  (let [all-songs (q/retrieve-all-songs db)
        tr (i18n/tr-from-req req)
        filtered-songs (search-songs (filter-param req) all-songs)]
    [:div {:class "mt-4"
           :hx-boost "true"
           :id id}

     (song-list tr filtered-songs)]))

(ctmx/defcomponent songs-page [req]
  (let [tr (i18n/tr-from-req req)]
    [:div
     (ui/page-header :title (tr [:song/list-title])
                     :buttons (list
                               (ui/button :label "Sync songs"
                                          :priority :white
                                          :centered? true
                                          :hx-swap "none"
                                          :hx-post "/songs-sync")
                               (ui/button :tag :a :label (tr [:song/create-title])
                                          :priority :primary
                                          :centered? true
                                          :attr {:href "/songs/new"})))

     [:div {:class "px-4 sm:px-6 lg:px-8 mt-4"}
      [:div {:class "flex flex-col space-y-4 sm:flex-row sm:items-center justify-between pb-4 mt-4"}
       (songs-filter req)
       (song-table-action-button req)]]
     (songs-list req "")]))

(defn song-toggler [{:song/keys [title selected]}]
  [:li (comment {:class
                 (ui/cs
                  "rounded-sm border-4 mx-0 my-1 p-2 block basis-1/2 "
                  (if  selected "border-green-200" "border-gray-200"))
                 :_ "on click toggle between .border-gray-200 and .border-green-200"})
   [:label {:for title}
    [:input {:type "checkbox"
             :id title
             :class "sr-only peer"}]
    [:span {:class "rounded-sm border-4 mx-0 my-1 p-2 block basis-1/2 border-gray-200 peer-checked:border-green-200"}  title]]])

(defn song-toggle-list [all-songs]
  [:ul {:class "p-0 m-0 flex flex-wrap"}
   (map song-toggler all-songs)])

(defn songs-sync [req]
  (controller/sync-songs! req)
  {:status 201
   :headers {"Content-Type" "text/html"}
   :body "OK"})

(defn song-image-remote-path
  "Return the remote nextcloud path for the image upload dir or a specific file"
  ([req song-id]
   (fu/path-join (config/nextcloud-path-song-image-upload (-> req :system :env)) "song" (str song-id)))
  ([req song-id filename]
   (let [base-path (song-image-remote-path req song-id)
         path (fu/path-join base-path filename)]
     (fu/validate-base-path! base-path path)
     path)))

(defn list-image-uris [{:keys [system webdav] :as req} song-id]
  (->> (sardine/list-photos webdav (song-image-remote-path req song-id))
       (map #(url/absolute-link-song-image (:env system) song-id  %))))

(defn image-fetch-handler [{:keys [parameters] :as req}]
  (let [{:keys [filename song-id]} (:path parameters)]
    (tap> {:p parameters})
    (sardine/fetch-file-response req
                                 (song-image-remote-path req song-id filename)
                                 true)))

(defn image-upload-handler [{:keys [webdav parameters] :as req}]
  (try
    (let [song-id (-> parameters :path :song-id)
          file (->  parameters :multipart :file)
          filename (:filename file)]
      (assert song-id)
      (sardine/upload webdav
                      (song-image-remote-path req song-id)
                      file)
      {:status 201
       :headers {"content-type" "application/json"}
       :body (j/write-value-as-string {:file-url (url/absolute-link-song-image (-> req :system :env) song-id filename)})})
    (catch Exception e
      (errors/report-error! e)
      {:status 500
       :headers {"content-type" "application/json"}
       :body (j/write-value-as-string {:error (str e)})})))
