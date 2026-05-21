(ns app.songs.detail.views
  (:require
   [app.auth :as auth]
   [app.config :as config]
   [app.datastar :as d*]
   [app.file-browser.actions :as file-browser.actions]
   [app.file-browser.views :as file-browser.view]
   [app.html :as html]
   [app.markdown :as markdown]
   [app.queries :as q]
   [app.songs.detail.actions :as actions]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]
   [starfederation.datastar.clojure.expressions :refer [->expr]]))

(defn- blankish? [value]
  (str/blank? (str value)))

(defn- muted [value]
  (if (blankish? value)
    [:span {:class "wa-color-text-quiet"} "—"]
    value))

(defn- detail-item
  ([label value]
   (detail-item label value nil))
  ([label value attrs]
   (into [:div (or attrs {})]
         [[:dt label]
          [:dd value]])))

(defn- optional-item
  ([label value]
   (optional-item label value nil))
  ([label value attrs]
   (when-not (blankish? value)
     (detail-item label value attrs))))

(defn- optional-markdown-item [label markdown-text]
  (when-not (str/blank? markdown-text)
    (detail-item label
                 (markdown/render markdown-text)
                 {:class "songs-detail-wide"})))

(defn- song-summary [{:keys [tr]} {:song/keys [active? title] :as _song}]
  (ui2/page-header
   {:breadcrumb [:wa-breadcrumb
                 [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
                 [:wa-breadcrumb-item {:href (urls/link-songs-home)}
                  (tr [:nav/songs])]
                 [:wa-breadcrumb-item title]]
    :heading    [:div {:class "wa-cluster wa-gap-xs wa-align-items-center songs-detail-title"}
                 [:h1 title]
                 (ui2/active-badge tr active?)]
    :actions    [[:wa-button {:appearance "outlined"
                              :href       (urls/link-songs-home)}
                  (tr [:action/back])]
                 [:wa-button {:appearance "outlined"
                              :href       (urls/link-song-edit _song)}
                  (tr [:action/edit])]]}))

(defn- background-section [{:keys [tr]} {:song/keys [arrangement-credits arrangement-notes composition-credits lyrics origin solo-info]}]
  (ui2/section-card
   {:title (tr [:song/background-title])}
   [:dl {:class "particulars songs-detail-info-list"}
    (optional-item (tr [:song/solo-count]) solo-info)
    (optional-item (tr [:song/composition-credits]) composition-credits)
    (optional-item (tr [:song/arrangement-credits]) arrangement-credits)
    (optional-markdown-item (tr [:song/origin]) origin)
    (optional-markdown-item (tr [:song/arrangement-notes]) arrangement-notes)
    (optional-markdown-item (tr [:song/lyrics]) lyrics)]))

(defn- gig-link [req gig]
  (when gig
    [:a {:href  (urls/link-gig gig)
         :class "songs-detail-link"}
     (or (:gig/title gig)
         (ui2/format-date req :compact-with-weekday (:gig/date gig)))]))

(defn- play-stats-section [{:keys [tr] :as req} {:song/keys [last-played-on last-performance last-rehearsal total-performances total-plays total-rehearsals]}]
  (ui2/section-card
   {:title (tr [:song/play-stats-title])}
   [:dl {:class "particulars songs-detail-stats-list"}
    (detail-item (tr [:song/total-plays]) (muted total-plays))
    (detail-item (tr [:song/gig-count]) (muted total-performances))
    (detail-item (tr [:song/probe-count]) (muted total-rehearsals))
    (detail-item (tr [:song/last-played]) (muted (ui2/format-date req :compact-with-weekday last-played-on)))
    (detail-item (tr [:song/last-played-gig]) (muted (gig-link req last-performance)))
    (detail-item (tr [:song/last-played-probe]) (muted (gig-link req last-rehearsal)))]))

(defn- sheet-section-title [tr {:section/keys [default? name]}]
  (if default?
    (tr [:song/other-sheet-music])
    name))

(def extension->filetype-icon
  {"aac"  "file-audio-solid"
   "csv"  "file-csv-solid"
   "doc"  "file-word-solid"
   "docx" "file-word-solid"
   "flac" "file-audio-solid"
   "gif"  "file-image-solid"
   "jpg"  "file-image-solid"
   "jpeg" "file-image-solid"
   "m4a"  "file-audio-solid"
   "mov"  "file-video-solid"
   "mp3"  "file-audio-solid"
   "mp4"  "file-video-solid"
   "mscz" "file-zipper-solid"
   "ods"  "file-excel-solid"
   "odt"  "file-word-solid"
   "ogg"  "file-audio-solid"
   "pdf"  "file-pdf-solid"
   "png"  "file-image-solid"
   "ppt"  "file-powerpoint-solid"
   "pptx" "file-powerpoint-solid"
   "svg"  "file-image-solid"
   "wav"  "file-audio-solid"
   "xls"  "file-excel-solid"
   "xlsx" "file-excel-solid"
   "zip"  "file-zipper-solid"})

(defn- file-extension [filename]
  (some-> (or filename "")
          (str/split #"/")
          last
          (str/split #"\.")
          last
          str/lower-case))

(defn- filetype-icon-name [{:sheet-music/keys [title]
                            :file/keys        [webdav-path]}]
  (get extension->filetype-icon
       (file-extension (or webdav-path title))
       "file-solid"))

(defn- sheet-remove-dialog-id [sheet-id]
  (ui2/remove-dialog-id "sheet-music" sheet-id))

(defn- sheet-remove-dialog [req {:sheet-music/keys [sheet-id title]}]
  (let [tr (:tr req)]
    (ui2/remove-dialog
     {:id            (sheet-remove-dialog-id sheet-id)
      :label         (tr [:action/confirm-generic])
      :cancel-label  (tr [:action/cancel])
      :confirm-label (tr [:action/confirm-delete])
      :confirm-attrs {:data-id     sheet-id
                      :data-action (d*/act req ::actions/remove-sheet-music)}}
     [:p (str (tr [:action/remove]) " " title "?")])))

(defn- sheet-link [req {:sheet-music/keys [sheet-id title]
                        :file/keys        [webdav-path]
                        :as               sheet}]
  (let [tr (:tr req)]
    (list
     [:div {:class "songs-detail-sheet-row"}
      [:wa-icon {:library "snoico"
                 :name    (filetype-icon-name sheet)}]
      [:a {:href  (urls/link-file-download webdav-path)
           :class "songs-detail-sheet-title"}
       title]
      [:wa-button {:appearance "plain"
                   :size       "s"
                   :href       (urls/link-file-download webdav-path)
                   :class      "songs-detail-sheet-download"
                   :aria-label (tr [:action/download])}
       [:wa-icon {:library "default"
                  :name    "download"}]]
      [:wa-button {:appearance  "plain"
                   :variant     "danger"
                   :size        "s"
                   :type        "button"
                   :class       "songs-detail-sheet-remove"
                   :aria-label  (tr [:action/remove])
                   :data-dialog (str "open " (sheet-remove-dialog-id sheet-id))}
       [:wa-icon {:library "snoico"
                  :name    "xmark"}]]]
     (sheet-remove-dialog req sheet))))

(defn- current-member-section-name [req]
  (get-in (auth/get-current-member req) [:member/section :section/name]))

(defn- current-member-section? [req {:section/keys [name]}]
  (and (seq name)
       (= name (current-member-section-name req))))

(defn- open-sheet-music-picker-action [req song-id section-name root-dir current-dir]
  (->expr
   (evt.preventDefault)
   (set! $file-browser.picker-id ~(name actions/sheet-music-picker-id))
   (set! $file-browser.root-dir ~root-dir)
   (set! $file-browser.current-dir ~current-dir)
   (set! $file-browser.target {"song-id" ~(str song-id)
                               "section-name" ~section-name})
   (@post ~(d*/act req ::file-browser.actions/open-picker))))

(defn- sheet-add-button [req {:section/keys [name]} song-id root-dir current-dir]
  [:a {:href          "#"
       :class         "songs-detail-sheet-add"
       :data-on:click (open-sheet-music-picker-action req song-id name root-dir current-dir)}
   [:wa-icon {:library "snoico" :name "circle-plus-solid"}]
   ((:tr req) [:action/add])])

(defn- sheet-section [req song-id root-dir current-dir section]
  (let [tr (:tr req)]
    [:section {:class (ui2/cs "songs-detail-sheet-section"
                              (when (current-member-section? req section)
                                "songs-detail-sheet-section--current"))}
     [:div {:class "songs-detail-sheet-section-header"}
      [:h3 {:class "songs-detail-sheet-section-title"}
       (sheet-section-title tr section)]
      (sheet-add-button req section song-id root-dir current-dir)]
     [:div {:class "songs-detail-sheet-rows"}
      (if (seq (:sheet-music/_section section))
        (for [sheet (:sheet-music/_section section)]
          (sheet-link req (assoc sheet :song/song-id song-id)))
        [:a {:href          "#"
             :class         "songs-detail-sheet-empty-state"
             :data-on:click (open-sheet-music-picker-action req song-id (:section/name section) root-dir current-dir)}
         [:wa-icon {:library "snoico" :name "circle-plus-solid"}]
         [:span ((:tr req) [:action/add])]])]]))

(defn- sheet-music-content
  ([req song-id root-dir current-dir sections picker]
   (sheet-music-content req song-id root-dir current-dir sections picker file-browser.view/file-picker-panel))
  ([req song-id root-dir current-dir sections picker render-picker]
   (let [tr (:tr req)]
     (if (:open? picker)
       (render-picker
        req
        {:picker-id     actions/sheet-music-picker-id
         :state         picker
         :title         (tr [:song/choose-sheet-music-title])
         :subtitle      (some->> picker :target :section-name vector (tr [:song/choose-sheet-music-subtitle]))
         :select-action ::actions/add-sheet-music})
       (if (seq sections)
         [:div {:class "wa-grid songs-detail-sheet-grid"}
          (for [section sections]
            (sheet-section req song-id root-dir current-dir section))]
         [:div {:class "songs-detail-empty"} "—"])))))

(defn- nextcloud-env [req]
  (-> req :system :env))

(defn- sheet-music-section [{:keys [db page-state tr] :as req} {:song/keys [song-id]}]
  (let [sections    (q/sheet-music-for-song db song-id)
        root-dir    (or (config/nextcloud-path-sheet-music (nextcloud-env req)) "/")
        current-dir (or (q/sheet-music-dir-for-song db song-id)
                        (config/nextcloud-path-current-songs (nextcloud-env req))
                        root-dir)
        picker      (get-in page-state [:file-browser actions/sheet-music-picker-id])]
    (ui2/section-card
     {:title    (tr [:song/sheet-music-title])
      :divider? true}
     [:div {:class        "wa-stack wa-gap-m"
            :data-signals (d*/->signals {:file-browser {:picker-id     nil
                                                        :root-dir      root-dir
                                                        :current-dir   current-dir
                                                        :target-dir    nil
                                                        :selected-path nil
                                                        :song-id       nil
                                                        :sheet-id      nil
                                                        :target        nil}
                                         :song-detail  {:song-id (str song-id)}})}
      (sheet-music-content req song-id root-dir current-dir sections picker)])))

(defn- discourse-url [forum-url]
  (cond-> forum-url
    (not (str/ends-with? forum-url "/")) (str "/")))

(defn- discourse-embed-script [forum-url topic-id]
  (html/raw
   (format
    "
window.DiscourseEmbed = %s;

(function() {
  var d = document.createElement('script');
  d.type = 'text/javascript';
  d.async = true;
  d.src = window.DiscourseEmbed.discourseUrl + 'javascripts/embed.js';
  (document.getElementsByTagName('head')[0] || document.getElementsByTagName('body')[0]).appendChild(d);
})();
"
    (d*/->signals {"discourseUrl" (discourse-url forum-url)
                   "topicId"      topic-id}))))

(defn- discourse-comments-section [{:keys [system tr]} {:forum.topic/keys [topic-id]}]
  (when-let [forum-url (and topic-id (config/discourse-forum-url (:env system)))]
    (ui2/section-card
     {:id       "song-forum-comments"
      :title    (tr [:nav/forum])
      :divider? true}
     [:div {:id                "discourse-comments"
            :data-ignore-morph ""}]
     [:script {:type "text/javascript"}
      (discourse-embed-script forum-url topic-id)])))

(defn page [{:keys [db] :as req}]
  (let [song-id (http.util/path-param-uuid! req :song-id)
        song    (q/retrieve-song db song-id)]
    (if song
      (ui2/datastar-page
       [:div {:class "wa-stack wa-gap-2xl songs-detail-page"}
        (song-summary req song)
        (background-section req song)
        (play-stats-section req song)
        (sheet-music-section req song)
        (discourse-comments-section req song)])
      (throw (ex-info "Song not found" {:app/error-type :app.error.type/not-found
                                        :song/song-id   song-id})))))

(d*/refresh-all!)
