(ns app.probeplan.views-test
  (:require
   [app.probeplan.views :as views]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]
   [tick.core :as t]))

(defn tr [[k]]
  (name k))

(def intensive-row
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

(def positioned-intensive-row
  (update intensive-row :songs views/songs-by-position))

(deftest instructions
  (testing "The probe plan includes an explanation of how it works."
    (let [view    (views/how-it-works {:tr tr})
          details (l/select-one 'wa-details view)]
      (testing "The explanation starts collapsed with its translated summary."
        (is (= {:summary "how-it-works-title"
                :open?   false}
               {:summary (:summary (l/attrs details))
                :open?   (contains? (l/attrs details) :open)})))
      (testing "The explanation contains an introduction and five guidance steps."
        (is (= {:intro "how-it-works-intro"
                :steps ["how-it-works-generate"
                        "how-it-works-fixed"
                        "how-it-works-human-edit"
                        "how-it-works-future-update"
                        "how-it-works-gigs-column"]}
               {:intro (-> (l/select-one 'p details) l/text)
                :steps (mapv l/text (l/select 'li details))}))))))

(deftest fixed-row
  (testing "A fixed probe-plan row contains one intensive song and four playthrough songs."
    (let [view      (views/probe-row
                     {:current-locale :de}
                     []
                     false
                     6
                     0
                     positioned-intensive-row)
          song-text (fn [selector]
                      (->> (l/select selector view)
                           (map l/text)
                           (remove #{"—"})
                           vec))]
      (testing "Songs are placed in columns according to their emphasis."
        (is (= {:intensive   ["Dude"]
                :playthrough ["Alerta Feminista"
                              "Burkan Čoček"
                              "Rave de la Relation"
                              "Ti-cul"]}
               {:intensive   (song-text ".probeplan-cell--intensive")
                :playthrough (song-text ".probeplan-cell--normal")}))))))

(deftest editable-signals
  (testing "A fixed probe-plan row already contains songs with saved emphasis."
    (testing "The edit state preserves each song's position and emphasis."
      (is (= {"s0" {:position 0, :emphasis "intensive"}
              "s1" {:position 1, :emphasis "none"}
              "s2" {:position 2, :emphasis "none"}
              "s3" {:position 3, :emphasis "none"}
              "s4" {:position 4, :emphasis "none"}}
             (->> (get-in (views/editable-signals [positioned-intensive-row])
                          [:probeplan :rows "r0" :songs])
                  (map (fn [[slot song]]
                         [slot (select-keys song [:position :emphasis])]))
                  (into {})))))))
