(ns app.probeplan.views-test
  (:require
   [app.probeplan.views :as views]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [tick.core :as t]))

(defn tr [[k]]
  (name k))

(deftest how-it-works-renders-collapsed-web-awesome-details
  (let [[tag attrs & body] (#'views/how-it-works {:tr tr})]
    (is (= {:tag        :wa-details
            :summary    "how-it-works-title"
            :open?      false
            :body-tags  [:p :ul]}
           {:tag       tag
            :summary   (:summary attrs)
            :open?     (contains? attrs :open)
            :body-tags (mapv first body)}))))

(defn one-intensive-row []
  {:idx         0
   :date        (t/date "2026-05-20")
   :gig-id      #uuid "019ddbb1-53fb-8b93-9058-015b57799c51"
   :fixed?      true
   :last-fixed? false
   :num-gigs    0
   :songs       [{:song/title  "Dude"
                  :song/song-id #uuid "01844740-3eed-856d-84c1-c26f0706820a"
                  :position     0
                  :emphasis     :probeplan.emphasis/intensive}
                 {:song/title  "Alerta Feminista"
                  :song/song-id #uuid "019b0bfd-350d-8056-86f7-05571a05f412"
                  :position     1
                  :emphasis     :probeplan.emphasis/none}
                 {:song/title  "Burkan Čoček"
                  :song/song-id #uuid "01860df5-b3fe-8a11-aae0-5091418dd9e7"
                  :position     2
                  :emphasis     :probeplan.emphasis/none}
                 {:song/title  "Rave de la Relation"
                  :song/song-id #uuid "01860df5-da90-83b8-8a78-84dbf9734adb"
                  :position     3
                  :emphasis     :probeplan.emphasis/none}
                 {:song/title  "Ti-cul"
                  :song/song-id #uuid "018cf96c-d5d3-8222-baf0-dfd6dc7347e9"
                  :position     4
                  :emphasis     :probeplan.emphasis/none}]})

(defn normalized-one-intensive-row []
  (update (one-intensive-row) :songs #'views/songs-by-position))

(defn hiccup-text [form]
  (->> (tree-seq #(and (coll? %) (not (map? %))) seq form)
       (filter string?)
       (str/join " ")))

(defn non-empty-song-texts [cells]
  (->> cells
       (map hiccup-text)
       (remove #{"—"})
       (remove str/blank?)
       (vec)))

(defn song-cells [row]
  (let [[_tag _attrs _number-cell _date-cell _gigs-cell & cells] (#'views/probe-row [] false 6 0 row)]
    (if (and (= 1 (count cells))
             (sequential? (first cells)))
      (vec (first cells))
      (vec cells))))

(defn signal-emphases [signals]
  (->> (get-in signals [:probeplan :rows "r0" :songs])
       (map (fn [[slot song]]
              [slot (select-keys song [:position :emphasis])]))
       (into {})))

(deftest fixed-row-song-columns-follow-emphasis-not-position
  (let [cells (song-cells (normalized-one-intensive-row))]
    (is (= {:intensive-column-texts   ["Dude"]
            :playthrough-column-texts ["Alerta Feminista"
                                       "Burkan Čoček"
                                       "Rave de la Relation"
                                       "Ti-cul"]}
           {:intensive-column-texts   (non-empty-song-texts (take 2 cells))
            :playthrough-column-texts (non-empty-song-texts (drop 2 cells))}))))

(deftest editable-signals-preserve-existing-emphasis
  (is (= {"s0" {:position 0, :emphasis "intensive"}
          "s1" {:position 1, :emphasis "none"}
          "s2" {:position 2, :emphasis "none"}
          "s3" {:position 3, :emphasis "none"}
          "s4" {:position 4, :emphasis "none"}}
         (signal-emphases (#'views/editable-signals [(normalized-one-intensive-row)])))))
