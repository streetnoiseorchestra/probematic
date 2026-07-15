(ns app.probeplan.views-test
  (:require
   [app.probeplan.views :as views]
   [app.test-common :as tc]
   [app.ui2.breadcrumb :as breadcrumb]
   [app.ui2.button :as button]
   [app.ui2.page-header :as page-header]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-toolbar :as page-toolbar]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]
   [reitit.core :as r]
   [tick.core :as t]))

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
    (let [view    (views/how-it-works)
          details (l/select-one 'wa-details view)]
      (testing "The explanation starts collapsed with its translated summary."
        (is (= {:summary :probeplan/how-it-works-title
                :open?   false}
               {:summary (some-> (l/select-one :i18n/tr (:summary (l/attrs details)))
                                 l/first-child)
                :open?   (contains? (l/attrs details) :open)})))
      (testing "The explanation contains an introduction and five guidance steps."
        (is (= {:intro :probeplan/how-it-works-intro
                :steps [:probeplan/how-it-works-generate
                        :probeplan/how-it-works-fixed
                        :probeplan/how-it-works-human-edit
                        :probeplan/how-it-works-future-update
                        :probeplan/how-it-works-gigs-column]}
               {:intro (some-> (l/select-one :i18n/tr (l/select-one 'p details))
                               l/first-child)
                :steps (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                             (l/select 'li details))}))))))

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

(deftest probeplan-page-surface
  (let [{:keys [conn]} (tc/new-system "probeplan-page-surface")
        request        {::r/router       (r/router ["/act" {:name :app.routes.datastar/act}])
                        :current-locale :en
                        :db             (d/db conn)
                        :page-state     {}}
        view           (views/page request)
        surface        (l/select-one page-surface/PageSurface view)
        surface-attrs  (l/attrs surface)
        toolbar-attrs  (-> surface-attrs ::page-surface/toolbar l/attrs)
        breadcrumb     (::page-toolbar/breadcrumb toolbar-attrs)
        parent         (->> (l/select breadcrumb/BreadcrumbItem breadcrumb)
                            vec
                            butlast
                            last)
        actions        (l/select button/Button (::page-toolbar/actions toolbar-attrs))
        header         (l/select-one page-header/PageHeader surface)]
    (testing "The probeplan uses a wide workspace with its lifecycle action in the toolbar."
      (is (= {:width       :wide
              :breadcrumbs [:home :probeplan/title]
              :mobile      {:href "/" :label :home}
              :actions     [{:id "probeplan-edit" :label :action/edit}]
              :heading     :probeplan/title
              :header-actions nil}
             {:width       (::page-surface/width surface-attrs)
              :breadcrumbs (mapv #(some-> (l/select-one :i18n/tr %) l/first-child)
                                 (l/select breadcrumb/BreadcrumbItem breadcrumb))
              :mobile      {:href  (-> parent l/attrs ::breadcrumb/href)
                            :label (some-> (l/select-one :i18n/tr parent) l/first-child)}
              :actions     (mapv (fn [action]
                                   {:id    (:data-id (l/attrs action))
                                    :label (some-> (l/select-one :i18n/tr action) l/first-child)})
                                 actions)
              :heading     (some-> header l/attrs ::page-header/title l/first-child)
              :header-actions (some-> header l/attrs ::page-header/actions)})))
    (is (not (contains? toolbar-attrs ::page-toolbar/mobile-back)))

    (testing "Edit mode replaces Edit with Cancel and Save without changing page context."
      (let [edit-view          (views/page (assoc request :page-state {:probeplan {:editing true}}))
            edit-surface       (l/select-one page-surface/PageSurface edit-view)
            edit-toolbar-attrs (-> edit-surface l/attrs ::page-surface/toolbar l/attrs)
            edit-actions       (l/select button/Button (::page-toolbar/actions edit-toolbar-attrs))]
        (is (= [{:id "probeplan-cancel" :label :action/cancel}
                {:id "probeplan-save" :label :action/save}]
               (mapv (fn [action]
                       {:id    (:data-id (l/attrs action))
                        :label (some-> (l/select-one :i18n/tr action) l/first-child)})
                     edit-actions)))))))
