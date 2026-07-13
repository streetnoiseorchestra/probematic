(ns app.probeplan.views
  (:require
   [app.datastar :as d*]
   [app.probeplan.actions :as actions]
   [app.probeplan.queries :as queries]
   [app.ui2 :as ui2]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.icon :as ico]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [app.urls :as urls]))

(defn- intensive-position? [position]
  (< position 2))

(defn- playthrough-number [position]
  (inc (- position 2)))

(defn- song-position [idx song]
  (or (:position song) idx))

(defn- intensive-emphasis? [emphasis]
  (= :probeplan.emphasis/intensive emphasis))

(defn- default-emphasis [position]
  (if (intensive-position? position)
    :probeplan.emphasis/intensive
    :probeplan.emphasis/none))

(defn- song-emphasis [song]
  (or (:emphasis song)
      (default-emphasis (:probeplan/position song))))

(defn- song-emphasis-signal [song]
  (if (intensive-emphasis? (song-emphasis song))
    "intensive"
    "none"))

(defn- song-at-slot-position [songs slot-position]
  (some #(when (= slot-position (:probeplan/slot-position %)) %) songs))

(defn- assign-position [idx song]
  (assoc song :probeplan/position (song-position idx song)))

(defn- assign-slot-positions [songs]
  (let [positioned (->> songs
                        (map-indexed assign-position)
                        (sort-by :probeplan/position))]
    (if (some #(contains? % :emphasis) positioned)
      (let [intensive (filter #(intensive-emphasis? (:emphasis %)) positioned)
            normal    (remove #(intensive-emphasis? (:emphasis %)) positioned)]
        (vec (concat
              (map-indexed #(assoc %2 :probeplan/slot-position %1) intensive)
              (map-indexed #(assoc %2 :probeplan/slot-position (+ 2 %1)) normal))))
      (mapv #(assoc % :probeplan/slot-position (:probeplan/position %)) positioned))))

(defn songs-by-position [songs]
  (assign-slot-positions songs))

(defn- row-key [row-idx]
  (str "r" row-idx))

(defn- slot-key [position]
  (str "s" position))

(defn- editable-row-signal [row-idx row]
  [(row-key row-idx)
   {:gig-id (str (:gig-id row))
    :songs  (into {}
                  (map (fn [{:song/keys [song-id] :probeplan/keys [position] :as song}]
                         [(slot-key position)
                          {:song-id  (str song-id)
                           :position position
                           :emphasis (song-emphasis-signal song)}]))
                  (:songs row))}])

(defn editable-signals [rows]
  {:probeplan
   {:rows (into {}
                (keep-indexed (fn [idx row]
                                (when (:fixed? row)
                                  (editable-row-signal idx row))))
                rows)}})

(defn- header-label [position]
  (if (intensive-position? position)
    [:i18n/tr :probeplan/header-intensive {:number (inc position)}]
    [:i18n/tr :probeplan/header-playthrough {:number (playthrough-number position)}]))

(defn- table-headers [song-count]
  (concat [[:i18n/tr :probeplan/header-number]
           [:i18n/tr :probeplan/header-date]
           [:i18n/tr :probeplan/header-gigs]]
          (map header-label (range song-count))))

(defn- row-class [{:keys [fixed? last-fixed?]}]
  (ui2/cs "probeplan-row"
          (when fixed? "probeplan-row--fixed")
          (when last-fixed? "probeplan-row--last-fixed")
          (when-not fixed? "probeplan-row--future")))

(defn- song-cell-class [position]
  (ui2/cs "probeplan-cell"
          "probeplan-cell--song"
          (if (intensive-position? position)
            "probeplan-cell--intensive"
            "probeplan-cell--normal")))

(defn- song-title [song]
  (or (:song/title song) "—"))

(defn- song-option [selected-id {:song/keys [song-id title]}]
  (let [song-id (str song-id)]
    [:option {:value    song-id
              :selected (= song-id selected-id)}
     title]))

(defn- song-select [all-songs row-idx song]
  (let [selected-id (str (:song/song-id song))
        position    (:probeplan/position song)]
    [:select {:class     "probeplan-select"
              :data-bind (str "probeplan.rows."
                              (row-key row-idx)
                              ".songs."
                              (slot-key position)
                              ".song-id")}
     (for [song all-songs]
       (song-option selected-id song))]))

(defn- song-cell [all-songs editing? row-idx fixed? slot-position song]
  [:td {:class (song-cell-class slot-position)}
   (cond
     (and editing? fixed? song)
     (song-select all-songs row-idx song)

     song
     [:span (song-title song)]

     :else
     [:span {:class "probeplan-cell-empty"} "—"])])

(defn- date-cell [req {:keys [fixed? gig-id date]}]
  [:td {:class "probeplan-cell probeplan-cell--date"}
   (let [date-label (ui2/format-date req :month-day date)]
     (if fixed?
       [:a {:href (urls/link-gig gig-id)}
        date-label]
       date-label))])

(defn probe-row [req all-songs editing? song-count row-idx {:keys [idx songs num-gigs] :as row}]
  (let [songs (songs-by-position songs)]
    [:tr {:class (row-class row)}
     [:td {:class "probeplan-cell probeplan-cell--number"} (inc (or idx row-idx))]
     (date-cell req row)
     [:td {:class "probeplan-cell probeplan-cell--gigs"} num-gigs]
     (for [slot-position (range song-count)]
       (song-cell all-songs
                  editing?
                  row-idx
                  (:fixed? row)
                  slot-position
                  (song-at-slot-position songs slot-position)))]))

(defn- max-song-count [rows]
  (max 5
       (inc (reduce max -1 (mapcat (comp (partial map :probeplan/slot-position) :songs) rows)))))

(defn- probe-table [req all-songs editing? rows]
  (let [rows       (mapv #(update % :songs songs-by-position) rows)
        song-count (max-song-count rows)]
    [:div {:class "table-shell" :style "position: relative;"}
     [:table {:class "probeplan-table"}
      [:thead
       [:tr
        (for [header (table-headers song-count)]
          [:th {:scope "col"} header])]]
      [:tbody
       (for [[row-idx row] (map-indexed vector rows)]
         (probe-row req all-songs editing? song-count row-idx row))]]]))

(defn how-it-works []
  [:wa-details {:class      "probeplan-how-it-works"
                :summary    [:i18n/tr :probeplan/how-it-works-title]
                :appearance "outlined"}
   [:p [:i18n/tr :probeplan/how-it-works-intro]]
   [:ul
    (for [key [:probeplan/how-it-works-generate
               :probeplan/how-it-works-fixed
               :probeplan/how-it-works-human-edit
               :probeplan/how-it-works-future-update
               :probeplan/how-it-works-gigs-column]]
      [:li [:i18n/tr key]])]])

(defn page [{:keys [db page-state] :as req}]
  (let [rows      (mapv #(update % :songs songs-by-position) (queries/probeplan-plans db))
        all-songs (queries/active-songs db)
        editing?  (true? (get-in page-state [:probeplan :editing]))
        actions   (if editing?
                    [[button/Button {:appearance  "outlined"
                                     :data-id     "probeplan-cancel"
                                     :data-action (d*/act req ::actions/cancel-edit)}
                      [:i18n/tr :action/cancel]]
                     [button/Button {:appearance  "filled"
                                     :variant     "brand"
                                     :data-id     "probeplan-save"
                                     :data-action (d*/act req ::actions/save-probeplans)}
                      [:i18n/tr :action/save]]]
                    [[button/Button {:appearance  "outlined"
                                     :variant     "brand"
                                     :data-id     "probeplan-edit"
                                     :data-action (d*/act req ::actions/open-edit)}
                      [:i18n/tr :action/edit]]])]
    (ui2/datastar-page*
     [page-surface/PageSurface
      {::page-surface/width :wide
       ::page-surface/toolbar
       [page-toolbar/PageToolbar
        {::page-toolbar/breadcrumb
         [breadcrumb/Breadcrumb
          {}
          [breadcrumb/BreadcrumbItem {::breadcrumb/href (urls/link-dashboard)}
           [:i18n/tr :home]]
          [breadcrumb/BreadcrumbItem [:i18n/tr :probeplan/title]]]
         ::page-toolbar/mobile-back
         [button/Button {:appearance "plain"
                         :href       (urls/link-dashboard)}
          [ico/Icon {::ico/library :phosphor
                     ::ico/name    :arrow-left
                     :slot         "start"}]
          [:i18n/tr :home]]
         ::page-toolbar/actions actions
         :aria-label            [:i18n/tr :probeplan/toolbar-label]}]}
      [:div {:class        "wa-stack wa-gap-l"
             :data-signals (d*/->signals (editable-signals rows))}
       [page-header/PageHeader {::page-header/title [:i18n/tr :probeplan/title]}]
       (how-it-works)
       (if (seq rows)
         (probe-table req all-songs editing? rows)
         (ui2/empty-state
          [:i18n/tr :probeplan/empty-title]
          [:i18n/tr :probeplan/empty-body]))]])))

(d*/refresh-all!)
