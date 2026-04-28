(ns app.gigs.probeplan.views
  (:require
   [app.datastar :as d*]
   [app.gigs.domain :as gig.domain]
   [app.gigs.probeplan.actions :as actions]
   [app.gigs.ui :as gigs.ui]
   [app.html :as html]
   [app.probeplan.domain :as probeplan.domain]
   [app.queries :as q]
   [app.ui2 :as ui2]
   [app.urls :as urls]
   [app.util.http :as http.util]
   [clojure.string :as str]))

(def extra-head [])

(defn- js-value [value]
  (pr-str (str value)))

(defn- set-probeplan-js [m]
  (str/join "; "
            (for [[k v] m]
              (str "$gig-probeplan." (name k) " = " (js-value v)))))

(defn- action-js [req action m]
  (str (set-probeplan-js m)
       "; @post('" (d*/act req action) "')"))

(defn- selected-song-ids [songs]
  (set (map :song/song-id songs)))

(defn- intensive? [{:keys [emphasis]}]
  (= :probeplan.emphasis/intensive emphasis))

(defn- page-summary [{:keys [tr]} gig]
  [:header {:class "gigs-probeplan-editor-header wa-stack wa-gap-m"}
   [:wa-breadcrumb
    [:wa-icon {:slot "separator" :name "nav-arrow-right"}]
    [:wa-breadcrumb-item {:href (urls/link-gigs-home)}
     (tr [:nav/gigs])]
    [:wa-breadcrumb-item {:href (urls/link-gig gig)}
     (gigs.ui/gig-breadcrumb-label gig)]
    [:wa-breadcrumb-item (tr [:gig/probeplan])]]
   [:div {:class "wa-flank:end wa-align-items-start"}
    [:div {:class "wa-stack wa-gap-2xs"}
     [:h1 (tr [:gig/probeplan])]
     [:span {:class "wa-caption-s"} (:gig/title gig)]]
    [:wa-button {:appearance "outlined"
                 :href       (urls/link-gig gig)}
     (tr [:action/cancel])]]])

(defn- error-callout [error]
  (when-let [message (:error error)]
    [:wa-callout {:variant "danger"}
     message]))

(defn- selected-count [songs]
  [:span {:class "gigs-probeplan-editor-count"}
   (str (count songs) " / " probeplan.domain/MAX-SONGS)])

(defn- selected-song-signals [songs]
  (mapv (fn [idx {:song/keys [song-id] :keys [emphasis]}]
          {:song-id  (str song-id)
           :position idx
           :emphasis (if (intensive? {:emphasis emphasis}) "intensive" "none")})
        (range)
        songs))

(defn- toggle-song-client-js [song-id]
  (let [song-id (js-value song-id)]
    (str "$gig-probeplan.songs = Array.isArray($gig-probeplan.songs) ? $gig-probeplan.songs : []"
         "; if (evt.target.checked) {"
         "if (!$gig-probeplan.songs.some(song => song['song-id'] == " song-id ")) "
         "$gig-probeplan.songs = [...$gig-probeplan.songs, {'song-id': " song-id ", position: $gig-probeplan.songs.length, emphasis: 'none'}]"
         " } else { "
         "$gig-probeplan.songs = $gig-probeplan.songs.filter(song => song['song-id'] != " song-id ").map((song, idx) => ({...song, position: idx}))"
         " }")))

(defn- song-choice [req gig-id selected-ids {:song/keys [song-id title]}]
  [:wa-checkbox (cond-> {:class          "gigs-probeplan-editor-choice"
                         :data-on:change (str (set-probeplan-js {:gig-id  gig-id
                                                                 :song-id song-id})
                                              "; $gig-probeplan.selected = evt.target.checked"
                                              "; " (toggle-song-client-js song-id)
                                              "; @post('" (d*/act req ::actions/toggle-probeplan-song) "')")}
                  (selected-ids song-id) (assoc :checked true))
   [:span title]])

(defn- song-choices [req gig-id active-songs selected-songs]
  (let [selected-ids (selected-song-ids selected-songs)]
    (ui2/section-card
     {:title    ((:tr req) [:gig/probeplan-choose])
      :divider? true
      :actions  [(selected-count selected-songs)]}
     [:div {:class "gigs-probeplan-editor-choices"}
      (for [song active-songs]
        (song-choice req gig-id selected-ids song))])))

(defn- intensive-button [req gig-id {:song/keys [song-id]}]
  [:wa-button {:appearance    "plain"
               :size          "small"
               :class         "gigs-probeplan-editor-intensive-button"
               :aria-label    "Toggle intensive"
               :data-on:click (action-js req
                                         ::actions/toggle-probeplan-intensive
                                         {:gig-id  gig-id
                                          :song-id song-id})}
   [:wa-icon {:library "snoico"
              :name    "fist-punch"}]])

(defn- selected-song-row [req gig-id _total _idx {:song/keys [song-id title] :as song}]
  [:li {:id           (str "gig-probeplan-selected-" (ui2/safe-dom-id song-id))
        :class        (str "gigs-probeplan-editor-row"
                           (when (intensive? song)
                             " gigs-probeplan-editor-row--intensive"))
        :data-song-id (str song-id)}
   [:button {:type       "button"
             :class      "gigs-probeplan-editor-drag-handle"
             :aria-label "Drag to reorder"}
    [:wa-icon {:library "snoico"
               :name    "bars"}]]
   [:div {:class "gigs-probeplan-editor-row-main"}
    [:a {:href  (urls/link-song song)
         :class "gigs-song-link"}
     title]]
   [:div {:class "gigs-probeplan-editor-row-action"}
    (intensive-button req gig-id song)]])

(defn- selected-songs-list [req gig-id selected-songs]
  (ui2/section-card
   {:title    ((:tr req) [:gig/probeplan-sort])
    :divider? true}
   (if (seq selected-songs)
     (list
      [:ol {:id                          "gig-probeplan-selected-songs"
            :class                       "gigs-probeplan-editor-selected"
            :data-on:probeplan-reordered (str (set-probeplan-js {:gig-id gig-id})
                                              "; $gig-probeplan.order = evt.detail.order"
                                              "; @post('" (d*/act req ::actions/reorder-probeplan-songs) "')")}
       (map-indexed (partial selected-song-row req gig-id (count selected-songs)) selected-songs)]
      (html/squint-inline
       (require '["/js/sortable@1.15.7-esm.js" :as s])
       (let [sort-container (.getElementById js/document "gig-probeplan-selected-songs")]
         (when sort-container
           (new s/Sortable
                sort-container
                {:animation  150
                 :ghostClass "gigs-probeplan-editor-row--ghost"
                 :handle     ".gigs-probeplan-editor-drag-handle"
                 :onEnd
                 (fn []
                   (let [order (new js/Array)]
                     (.forEach
                      (.querySelectorAll sort-container "[data-song-id]")
                      (fn [row]
                        (.push order (.-songId (.-dataset row)))))
                     (.dispatchEvent sort-container
                                     (new js/CustomEvent "probeplan-reordered"
                                          {:bubbles true
                                           :detail  {:order order}}))))})))))
     [:div {:class "gigs-empty"} "—"])))

(defn- save-actions [req gig-id]
  [:div {:class "wa-cluster wa-gap-xs wa-justify-content-end gigs-probeplan-editor-save"}
   [:wa-button {:appearance "outlined"
                :href       (urls/link-gig gig-id)}
    ((:tr req) [:action/cancel])]
   [:wa-button {:appearance    "filled"
                :variant       "brand"
                :data-on:click (action-js req ::actions/save-probeplan {:gig-id gig-id})}
    ((:tr req) [:action/save])]])

(defn page [{:keys [db page-state] :as req}]
  (let [gig-id         (http.util/path-param-uuid! req :gig/gig-id)
        gig            (q/retrieve-gig db gig-id)
        active-songs   (q/retrieve-active-songs db)
        selected-songs (actions/selected-songs-for-page db page-state gig-id)
        error          (get-in page-state [:gig-probeplan :_error])]
    (cond
      (nil? gig)
      (throw (ex-info "Gig not found" {:app/error-type :app.error.type/not-found
                                       :gig/gig-id     gig-id}))

      (not (gig.domain/probe? gig))
      (throw (ex-info "Probeplan is only available for probes" {:app/error-type :app.error.type/not-found
                                                                :gig/gig-id     gig-id}))

      :else
      (ui2/datastar-page
       [:div {:class        "wa-stack wa-gap-xl gigs-probeplan-editor-page"
              :data-signals (d*/->signals {:gig-probeplan {:gig-id (str gig-id)
                                                           :songs  (selected-song-signals selected-songs)
                                                           :order  []}})}
        (page-summary req gig)
        (error-callout error)
        (selected-songs-list req gig-id selected-songs)
        (song-choices req gig-id active-songs selected-songs)
        (save-actions req gig-id)]))))

(d*/refresh-all!)
