(ns app.account.index.views-test
  (:require
   [app.account.test-support :as support]
   [clojure.test :refer [deftest is testing]]
   [lookup.core :as l]))

(def expected-row-hrefs
  ["/account-settings/profile"
   "/account-settings/notifications"
   "/account-settings/preferences"
   "/account-settings/on-a-break"
   "#"
   "/logout"])

(def expected-row-keys
  [:account-settings/profile-row-title
   :account-settings/notifications-row-title
   :account-settings/preferences-row-title
   :account-settings/break-row-title
   :account-settings/help-row-title
   :account-settings/logout-row-title])

(defn row-summary [row]
  {:href  (:href (support/attrs row))
   :title (some-> (l/select-one :i18n/tr row) l/first-child)})

(deftest account-directory-renders-the-approved-destination-order
  (let [page (support/public-fn 'app.account.index.views/page)]
    (is (fn? page) "app.account.index.views/page should exist")
    (when page
      (let [view      (page (support/request))
            directory (support/element-by-id "account-settings-directory" view)
            rows      (filter #(contains? (support/class-tokens %)
                                          "account-settings-row")
                              (support/elements :a directory))]
        (is (= :main (first view)))
        (is (contains? (support/class-tokens view) "account-settings"))
        (is (= (mapv (fn [href title] {:href href :title title})
                     expected-row-hrefs
                     expected-row-keys)
               (mapv row-summary rows)))
        (is (= "#" (:href (support/attrs (nth rows 4)))))
        (is (= "/logout" (:href (support/attrs (nth rows 5)))))))))

(deftest account-directory-renders-local-store-assets-and-real-prototype-actions
  (let [page (support/public-fn 'app.account.index.views/page)]
    (is (fn? page) "app.account.index.views/page should exist")
    (when page
      (let [view       (page (support/request))
            apps       (support/element-by-id "account-settings-apps" view)
            images     (mapv support/attrs (support/elements :img apps))
            app-links  (filter #(= "#" (:href (support/attrs %)))
                               (support/elements :a apps))]
        (is (= [{:src "/img/app-store-ios.png"
                 :alt [:i18n/tr :account-settings/app-store-ios-alt]}
                {:src "/img/app-store-android.png"
                 :alt [:i18n/tr :account-settings/app-store-android-alt]}]
               (mapv #(select-keys % [:src :alt]) images)))
        (is (= 3 (count app-links)))
        (is (every? #(re-find #"preventDefault" (:data-on:click (support/attrs %)))
                    app-links))
        (is (= #{:app.account.actions/launch-app}
               (support/actions-in apps)))))))

(deftest account-directory-home-screen-action-keeps-its-label-together
  (let [page (support/public-fn 'app.account.index.views/page)]
    (is (fn? page) "app.account.index.views/page should exist")
    (when page
      (let [view     (page (support/request))
            apps     (support/element-by-id "account-settings-apps" view)
            pwa-link (some #(when (contains? (support/class-tokens %)
                                             "pwa-link")
                              %)
                           (support/elements :span apps))]
        (is (some? pwa-link))
        (is (contains? (support/class-tokens pwa-link) "wa-flex-nowrap"))
        (is (contains? (support/class-tokens pwa-link) "wa-text-nowrap"))))))

(deftest account-directory-visible-copy-is-fluent-data
  (let [page (support/public-fn 'app.account.index.views/page)]
    (is (fn? page) "app.account.index.views/page should exist")
    (when page
      (is (every? (support/translation-keys (page (support/request)))
                  (into #{:account-settings/title
                          :account-settings/apps-title
                          :account-settings/app-pwa-title}
                        expected-row-keys))))))

(deftest account-directory-app-copy-uses-the-configured-instance-name
  (let [page (support/public-fn 'app.account.index.views/page)]
    (is (fn? page) "app.account.index.views/page should exist")
    (when page
      (let [view (page (support/request
                        {:system {:env {:name "The Test Band"}}}))]
        (is (= {:instance-name "The Test Band"}
               (nth (support/translation-node
                     :account-settings/apps-description
                     view)
                    2)))))))
