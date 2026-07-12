(ns app.ui2.jump-menu-test
  (:require
   [app.icons :as icons]
   [clojure.test :refer [deftest is use-fixtures]]
   [lookup.core :as l]))

(def member
  {:member/member-id #uuid "11111111-1111-4111-8111-111111111111"
   :member/name "Ada Lovelace"
   :member/nick "Ada"
   :member/avatar-template "/user_avatar/forum.streetnoise.at/ada/{size}/1.png"})

(def test-manifest
  (delay
    (icons/build-sprite-manifest
     [{:id          :snoico
       :source-root "public/img/snoico"
       :icons       [:calendar
                     :chart-bar-square
                     :chevron-down
                     :cog
                     :comments
                     :folder-open
                     :home
                     :trumpet]}])))

(defn install-test-manifest [f]
  (let [manifest_ (deref #'icons/sprite-manifest_)
        original  @manifest_]
    (icons/install-sprite-manifest! @test-manifest)
    (try
      (f)
      (finally
        (reset! manifest_ original)))))

(use-fixtures :each install-test-manifest)

(defn resolve-jump-menu []
  (try
    (requiring-resolve 'app.ui2.jump-menu/JumpMenu)
    (catch Throwable _
      nil)))

(defn jump-menu-view []
  (when-let [jump-menu (resolve-jump-menu)]
    (jump-menu
     {:app.ui2.jump-menu/logotype
      [:svg {:class "test-logotype" :aria-hidden "true"}]})))

(defn direct-children [node]
  (let [[_ ?attrs & children] node]
    (if (map? ?attrs)
      (filter vector? children)
      (filter vector? (cons ?attrs children)))))

(deftest jump-menu-renders-the-draft-navigation-surface
  (let [jump-menu (resolve-jump-menu)]
    (is (some? jump-menu) "JumpMenu should exist")
    (when jump-menu
      (let [view       (jump-menu-view)
            trigger    (l/select-one ".trigger" view)
            popover    (l/select-one "#jump-menu-popover" view)
            search     (l/select-one "input[type=search]" view)
            shortcuts  (l/select ".shortcut" view)
            recents    (l/select ".recent-item" view)
            gigs       (l/select ".gig" view)
            see-all    (l/select-one ".see-all" view)
            translation-nodes (l/select :i18n/tr view)]
        (is (= {:root-class "jump-menu"
                :root-signals "false"
                :root-init "$jumpMenuStuck = window.scrollY > 0"
                :root-scroll "$jumpMenuStuck = window.scrollY > 0"
                :root-stuck "$jumpMenuStuck"
                :trigger {:id "jump-menu-trigger"
                          :appearance "plain"
                          :with-caret true
                          :aria-controls "jump-menu-popover"
                          :aria-expanded "false"
                          :aria-haspopup "dialog"
                          :aria-label "Open jump menu"}
                :popover {:id "jump-menu-popover"
                          :for "jump-menu-trigger"
                          :placement "bottom"
                          :without-arrow true}
                :shortcut-labels ["Activity" "Calendar" "Reports" "Everything"]
                :search {:type "search"
                         :name "jump-menu-search"
                         :aria-label "Search or jump"
                         :placeholder "Search or jump to anything"}
                :section-headings ["Recently visited" "Gigs"]
                :recent-labels ["Dashboard" "Band settings" "Probeplan" "Members"]
                :gig-labels ["Sommerfest at Kulturhof" "Streetnoise at Hafenklang"]
                :see-all-href "/gigs"
                :translation-node-count 0}
               {:root-class (-> view l/attrs :class)
                :root-signals
                (-> view l/attrs :data-signals:jump-menu-stuck__ifmissing)
                :root-init (-> view l/attrs :data-init)
                :root-scroll
                (-> view l/attrs :data-on:scroll__window__throttle.50ms)
                :root-stuck (-> view l/attrs :data-class:stuck)
                :trigger (select-keys (l/attrs trigger)
                                      [:id :appearance :with-caret :aria-controls
                                       :aria-expanded :aria-haspopup :aria-label])
                :popover (select-keys (l/attrs popover)
                                      [:id :for :placement :without-arrow])
                :shortcut-labels (mapv l/text shortcuts)
                :search (select-keys (l/attrs search)
                                     [:type :name :aria-label :placeholder])
                :section-headings (mapv l/text (l/select 'h2 view))
                :recent-labels (mapv l/text recents)
                :gig-labels (mapv #(-> (l/select-one ".title" %) l/text) gigs)
                :see-all-href (-> see-all l/attrs :href)
                :translation-node-count (count translation-nodes)}))))))

(deftest application-shell-header-contains-only-the-jump-menu
  (let [app-shell-body (some-> (requiring-resolve 'app.layout2/app-shell-body) deref)
        view           (app-shell-body {:session {:session/member member}
                                        :tr      (fn
                                                   ([resource-ids]
                                                    (name (last resource-ids)))
                                                   ([resource-ids _]
                                                    (name (last resource-ids))))}
                                       [:main "Page content"])
        shell          (l/select-one 'app-shell view)
        header         (first (direct-children shell))
        children       (vec (direct-children header))]
    (is (= {:header-tag :header
            :direct-child-count 1
            :jump-menu-count 1
            :account-control-count 0
            :navigation-toggle-count 0}
           {:header-tag (first header)
            :direct-child-count (count children)
            :jump-menu-count (count (l/select ".jump-menu" header))
            :account-control-count (count (l/select 'app-shell-user header))
            :navigation-toggle-count
            (count
             (filter (fn [node]
                       (= :bars (:app.ui2.icon/name (l/attrs node))))
                     (l/select :app.ui2.icon/icon header)))}))))
