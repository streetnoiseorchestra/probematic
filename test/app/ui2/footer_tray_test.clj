(ns app.ui2.footer-tray-test
  (:require
   [app.html :as html]
   [app.icons :as icons]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]))

(def member
  {:member/member-id #uuid "11111111-1111-4111-8111-111111111111"
   :member/name "Ada Lovelace"
   :member/nick "Ada"
   :member/avatar-template "/user_avatar/forum.streetnoise.at/ada/{size}/1.png"})

(def footer-tray-translations
  {:footer-tray-label "Application shortcuts"
   :footer-tray-account "Account settings"
   :footer-tray-account-title "My Account"
   :footer-tray-account-settings "Account & Settings"
   :footer-tray-profile "My Profile"
   :footer-tray-logout "Logout"})

(defn footer-tray-translator
  ([resource-ids]
   (get footer-tray-translations (last resource-ids) (name (last resource-ids))))
  ([resource-ids _resource-data]
   (footer-tray-translator resource-ids)))

(def shortcuts
  [{:id "assignment-panel"
    :label "Assignments"
    :icon-library :phosphor
    :icon :check}
   {:id "calendar-panel"
    :label "Calendar"
    :icon-library :phosphor
    :icon :calendar}])

(def notification
  {:id "notification-panel"
   :label "Notifications"})

(def test-manifest
  (delay
    (icons/build-sprite-manifest
     [{:id :snoico
       :source-root "public/img/snoico"
       :icons [:user :cog :xmark]}
      {:id :phosphor
       :source-root "public/img/phosphor/phosphor-regular"
       :icons [:check :calendar :sign-out]}])))

(defn install-test-manifest [f]
  (let [manifest_ (deref #'icons/sprite-manifest_)
        original @manifest_]
    (icons/install-sprite-manifest! @test-manifest)
    (try
      (f)
      (finally
        (reset! manifest_ original)))))

(use-fixtures :each install-test-manifest)

(defn resolve-footer-tray []
  (try
    (requiring-resolve 'app.ui2.footer-tray/FooterTray)
    (catch Throwable _
      nil)))

(defn footer-tray-html []
  (when-let [footer-tray (some-> (resolve-footer-tray) deref)]
    (html/->str
     footer-tray-translator
     (footer-tray
      {:app.ui2.footer-tray/member member
       :app.ui2.footer-tray/shortcuts shortcuts
       :app.ui2.footer-tray/notification notification
       :id "application-footer"
       :class "custom-footer"}))))

(deftest footer-tray-renders-datastar-toggle-controls
  (let [footer-tray (resolve-footer-tray)]
    (is (some? footer-tray) "FooterTray should exist")
    (when footer-tray
      (let [rendered (footer-tray-html)
            dropdown-items (re-seq #"<wa-dropdown-item[^>]*>.*?</wa-dropdown-item>" rendered)
            account-settings-item (some #(when (str/includes? % "Account &amp; Settings") %) dropdown-items)
            logout-item (some #(when (str/includes? % "Logout") %) dropdown-items)]
        (is (= {:root? true
                :nav? true
                :dropdown? true
                :large-account-menu? true
                :account-label? true
                :account-title? true
                :account-settings-link? true
                :avatar? true
                :menu-values ["/member/11111111-1111-4111-8111-111111111111"
                              "/account-settings"
                              "/logout"]
                :logout-danger? false
                :logout-icon? true
                :account-menu-divider-count 0
                :account-menu-item-count 3
                :navigation-handler-count 3
                :sheet-targets ["assignment-panel"
                                "calendar-panel"
                                "notification-panel"]
                :toggle-binding-count 3
                :expanded-binding-count 3
                :selected-binding-count 3
                :shortcut-labels ["Assignments" "Calendar"]
                :caller-icons? true
                :ping? true
                :legacy-drawer-binding? false
                :internal-props-leaked? false}
               {:root? (str/includes? rendered "<footer id=\"application-footer\" class=\"footer-tray custom-footer\"")
                :nav? (str/includes? rendered "<nav aria-label=\"Application shortcuts\"")
                :dropdown? (str/includes? rendered "<wa-dropdown placement=\"top-start\"")
                :large-account-menu? (str/includes? rendered "size=\"l\"")
                :account-label? (str/includes? rendered "aria-label=\"Account settings\"")
                :account-title? (str/includes? rendered "<header class=\"account-menu-header\"><h2 class=\"wa-heading-l\">My Account</h2></header>")
                :account-settings-link? (and account-settings-item
                                             (str/includes? account-settings-item
                                                            "value=\"/account-settings\"")
                                             (str/includes? account-settings-item "onclick="))
                :avatar? (str/includes? rendered "class=\"sno-avatar\"")
                :menu-values (mapv second (re-seq #"<wa-dropdown-item[^>]+value=\"([^\"]+)\"" rendered))
                :logout-danger? (some-> logout-item (str/includes? "variant=\"danger\""))
                :logout-icon? (str/includes? rendered "#phosphor-sign-out")
                :account-menu-divider-count (count (re-seq #"<hr class=\"sno-divider\"" rendered))
                :account-menu-item-count (count dropdown-items)
                :navigation-handler-count (count (re-seq #"<wa-dropdown-item[^>]+onclick=" rendered))
                :sheet-targets (mapv second (re-seq #"aria-controls=\"([^\"]+)\"" rendered))
                :toggle-binding-count (count (re-seq #"data-on:click=\"\$footerTraySheet" rendered))
                :expanded-binding-count (count (re-seq #"data-attr:aria-expanded=" rendered))
                :selected-binding-count (count (re-seq #"data-class:selected=" rendered))
                :shortcut-labels (mapv second (re-seq #"<span class=\"shortcut-label\">([^<]+)</span>" rendered))
                :caller-icons? (and (str/includes? rendered "#phosphor-check")
                                    (str/includes? rendered "#phosphor-calendar"))
                :ping? (str/includes? rendered "class=\"ping-mark\"")
                :legacy-drawer-binding? (str/includes? rendered "data-drawer=")
                :internal-props-leaked? (str/includes? rendered "app.ui2.footer-tray/member")}))))))

(deftest footer-tray-toggle-expression-opens-and-closes-the-same-sheet
  (let [rendered (footer-tray-html)]
    (is (str/includes? rendered
                       "$footerTraySheet = ((($footerTraySheet === &quot;assignment-panel&quot;)) ? (&quot;&quot;) : (&quot;assignment-panel&quot;))"))))

(deftest footer-tray-does-not-own-sheet-markup
  (let [rendered (footer-tray-html)]
    (is (= {:dialog-count 0
            :web-awesome-drawer-count 0
            :placeholder-present? false}
           {:dialog-count (count (re-seq #"<dialog" rendered))
            :web-awesome-drawer-count (count (re-seq #"<wa-drawer" rendered))
            :placeholder-present? (str/includes? rendered "Hello world")}))))

(defn translator
  ([resource-ids]
   (name (last resource-ids)))
  ([resource-ids _resource-data]
   (translator resource-ids)))

(deftest application-shell-renders-native-sheets-as-footer-tray-siblings
  (let [app-shell-body (some-> (requiring-resolve 'app.layout2/app-shell-body) deref)
        rendered (html/->str
                  translator
                  (app-shell-body {:session {:session/member member}
                                   :tr translator}
                                  [:main "Page content"]))
        shell-position (str/index-of rendered "<app-shell>")
        footer-position (str/index-of rendered "<footer class=\"footer-tray\"")
        sheet-position (str/index-of rendered "<dialog")]
    (is (= {:footer-rendered? true
            :footer-after-shell? true
            :sheets-after-footer? true
            :native-sheet-count 5
            :bottom-sheet-count 4
            :end-sheet-count 1
            :web-awesome-drawer-count 0
            :open-dialog-count 5
            :reactive-inert-count 5
            :backdrop-rendered? true
            :labels-resolved? true
            :placeholder-count 5}
           {:footer-rendered? (some? footer-position)
            :footer-after-shell? (and shell-position
                                      footer-position
                                      (< shell-position footer-position))
            :sheets-after-footer? (and footer-position
                                       sheet-position
                                       (< footer-position sheet-position))
            :native-sheet-count (count (re-seq #"<dialog" rendered))
            :bottom-sheet-count (count (re-seq #"<dialog[^>]+class=\"tray-sheet bottom\"" rendered))
            :end-sheet-count (count (re-seq #"<dialog[^>]+class=\"tray-sheet end\"" rendered))
            :web-awesome-drawer-count (count (re-seq #"<wa-drawer" rendered))
            :open-dialog-count (count (re-seq #"<dialog[^>]+open" rendered))
            :reactive-inert-count (count (re-seq #"data-attr:inert=" rendered))
            :backdrop-rendered? (str/includes? rendered "class=\"tray-sheet-backdrop\"")
            :labels-resolved? (str/includes? rendered "aria-label=\"footer-tray-label\"")
            :placeholder-count (count (re-seq #">footer-tray-placeholder</dialog>" rendered))}))))

(deftest application-shell-sheets-bind-to-the-selected-footer-tray-sheet
  (let [app-shell-body (some-> (requiring-resolve 'app.layout2/app-shell-body) deref)
        rendered (html/->str
                  translator
                  (app-shell-body {:session {:session/member member}
                                   :tr translator}
                                  [:main "Page content"]))]
    (is (every? #(str/includes? rendered %)
                ["data-class:open=\"($footerTraySheet === &quot;footer-tray-assignments&quot;)\""
                 "data-attr:inert=\"(!(($footerTraySheet === &quot;footer-tray-assignments&quot;)))\""
                 "data-attr:aria-hidden=\"((($footerTraySheet === &quot;footer-tray-assignments&quot;)) ? (&quot;false&quot;) : (&quot;true&quot;))\""
                 "data-effect=\"((($footerTraySheet === &quot;footer-tray-assignments&quot;)) ? ((el.focus())) : (null))\""]))))
