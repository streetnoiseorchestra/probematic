(ns app.account.preferences.views-test
  (:require
   [app.account.test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is]]))

(defn control [id view]
  (some-> (support/element-by-id id view) support/attrs))

(deftest preferences-page-separates-browser-appearance-from-nexus-preferences
  (let [page (support/public-fn 'app.account.preferences.views/page)]
    (is (fn? page) "app.account.preferences.views/page should exist")
    (when page
      (let [view       (page (support/request))
            appearance (support/element-by-id "account-appearance" view)
            radios     (filter #(= "appearance" (:name (support/attrs %)))
                               (support/elements :input appearance))]
        (is (= [{:type "radio" :name "appearance" :value "light"}
                {:type "radio" :name "appearance" :value "dark"}
                {:type "radio" :name "appearance" :value "system"}]
               (mapv #(select-keys (support/attrs %) [:type :name :value]) radios)))
        (is (every? #(nil? (:data-bind (support/attrs %))) radios))
        (is (every? #(nil? (:form (support/attrs %))) radios))
        (is (= "account-appearance-controller"
               (:id (support/attrs
                     (support/element-by-id "account-appearance-controller" view)))))
        (is (= #{:app.account.actions/save-date-time-preferences}
               (support/actions-in view)))))))

(deftest preferences-page-uses-a-standard-shell-and-local-time-zone-options
  (let [page (support/public-fn 'app.account.preferences.views/page)]
    (is (fn? page) "app.account.preferences.views/page should exist")
    (when page
      (let [view     (page (support/request))
            contract (page-shell/page-contract view {:include-header? true})
            save     (control "account-preferences-save" view)
            options  (support/elements :option
                                       (support/element-by-id
                                        "account-preferences-time-zone"
                                        view))]
        (is (= {:width       :standard
                :breadcrumbs [:account-settings/title
                              :account-settings/preferences-title]
                :mobile      {:href "/account-settings"
                              :label :account-settings/title}
                :actions     []
                :overflow    []
                :heading     :account-settings/preferences-title}
               (select-keys contract
                            [:width :breadcrumbs :mobile :actions :overflow :heading])))
        (is (= {:type "submit" :form "account-preferences-form"}
               (select-keys save [:type :form])))
        (is (> (count options) 100))
        (is (some #(= "Europe/Berlin" (:value (support/attrs %))) options))
        (is (= "account-preferences.time-zone"
               (:data-bind (control "account-preferences-time-zone" view))))
        (is (empty? (support/elements :wa-input view)))))))

(deftest preferences-page-date-and-time-choices-use-native-selects
  (let [page (support/public-fn 'app.account.preferences.views/page)]
    (is (fn? page) "app.account.preferences.views/page should exist")
    (when page
      (let [view (page (support/request))
            option-values
            (fn [id]
              (mapv #(-> % support/attrs :value)
                    (support/elements :option
                                      (support/element-by-id id view))))]
        (is (= {:name "week-start"
                :form "account-preferences-form"
                :data-bind "account-preferences.week-start"}
               (select-keys (control "account-preferences-week-start" view)
                            [:name :form :data-bind])))
        (is (= ["monday" "sunday"]
               (option-values "account-preferences-week-start")))
        (is (= {:name "time-format"
                :form "account-preferences-form"
                :data-bind "account-preferences.time-format"}
               (select-keys (control "account-preferences-time-format" view)
                            [:name :form :data-bind])))
        (is (= ["12-hour" "24-hour"]
               (option-values "account-preferences-time-format")))))))

(deftest preferences-time-zone-caption-uses-the-instance-name-and-account-links
  (let [page (support/public-fn 'app.account.preferences.views/page)]
    (is (fn? page) "app.account.preferences.views/page should exist")
    (when page
      (let [view        (page (support/request
                               {:system {:env {:name "The Test Band"}}}))
            description (support/element-by-id
                         "account-preferences-time-zone-description"
                         view)
            links       (support/elements :a description)]
        (is (= ["/account-settings/profile"
                "/account-settings/notifications"]
               (mapv #(-> % support/attrs :href) links)))
        (is (= [:i18n/tr
                :account-settings/time-zone-description-before-profile
                {:instance-name "The Test Band"}]
               (support/translation-node
                :account-settings/time-zone-description-before-profile
                description)))
        (is (= #{:account-settings/time-zone-description-before-profile
                 :account-settings/time-zone-profile-link
                 :account-settings/time-zone-description-before-notifications
                 :account-settings/time-zone-notifications-link}
               (support/translation-keys description)))))))
