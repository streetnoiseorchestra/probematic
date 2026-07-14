(ns app.account.break.views-test
  (:require
   [app.account.test-support :as support]
   [app.ui2.button :as button]
   [app.ui2.page-surface :as page-surface]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is]]
   [lookup.core :as l]))

(deftest break-page-wires-the-high-fidelity-status-prototype
  (let [page (support/public-fn 'app.account.break.views/page)]
    (is (fn? page) "app.account.break.views/page should exist")
    (when page
      (let [view (page (support/request))]
        (is (= #{:app.account.actions/update-break-settings
                 :app.account.actions/end-break}
               (support/actions-in view)))
        (is (= "account-break.active"
               (-> (support/element-by-id "account-break-active" view)
                   support/attrs
                   :data-bind__prop.checked__event.change)))
        (is (= {:type "date" :data-bind "account-break.start-date"}
               (select-keys
                (support/attrs
                 (support/element-by-id "account-break-start-date" view))
                [:type :data-bind])))
        (is (= {:type "date" :data-bind "account-break.end-date"}
               (select-keys
                (support/attrs
                 (support/element-by-id "account-break-end-date" view))
                [:type :data-bind])))
        (is (some? (support/element-by-id "account-break-pause-overlay" view)))
        (is (some? (:data-show
                    (support/attrs
                     (support/element-by-id "account-break-status-fields" view)))))
        (is (= :app.account.actions/update-break-settings
               (support/action-keyword
                (-> (support/element-by-id "account-break-active" view)
                    support/attrs
                    :data-on:change))))
        (is (every? #(= :app.account.actions/update-break-settings
                        (support/action-keyword
                         (:data-on:change (support/attrs %))))
                    (support/elements :input view)))
        (is (empty? (filter #(= "submit" (:type (support/attrs %)))
                            (support/elements button/Button view))))
        (is (nil? (-> (support/element-by-id "account-break-form" view)
                      support/attrs
                      :data-action)))
        (is (nil? (-> (support/element-by-id "account-break-form" view)
                      support/attrs
                      :data-init)))
        (is (contains? (support/translation-keys view)
                       :account-settings/break-pause-overlay))
        (is (contains? (support/translation-keys view)
                       :account-settings/break-explanation-title))))))

(deftest break-page-keeps-the-end-dialog-after-visible-content
  (let [page (support/public-fn 'app.account.break.views/page)]
    (is (fn? page) "app.account.break.views/page should exist")
    (when page
      (let [view     (page (support/request))
            contract (page-shell/page-contract view {:include-header? true})
            surface  (l/select-one page-surface/PageSurface view)]
        (is (= {:width       :standard
                :breadcrumbs [:account-settings/title
                              :account-settings/break-title]
                :mobile      {:href "/account-settings"
                              :label :account-settings/title}
                :actions     []
                :overflow    []
                :heading     :account-settings/break-title}
               (select-keys contract
                            [:width :breadcrumbs :mobile :actions :overflow :heading])))
        (is (= "account-break-form"
               (-> (support/element-by-id "account-break-form" view)
                   support/attrs
                   :id)))
        (is (= :wa-dialog (last (support/child-tags surface))))
        (is (= "account-break-end-dialog"
               (-> surface l/last-child support/attrs :id)))))))

(deftest break-page-server-renders-the-initial-visibility-state
  (let [page (support/public-fn 'app.account.break.views/page)]
    (is (fn? page) "app.account.break.views/page should exist")
    (when page
      (let [inactive  (page (support/request))
            scheduled (page (support/request
                             {:page-state
                              {:account-break {:active true
                                               :status "scheduled"}}}))
            hidden?   (fn [id view]
                        (true? (:hidden
                                (support/attrs
                                 (support/element-by-id id view)))))]
        (is (false? (hidden? "account-break-available-label" inactive)))
        (is (true? (hidden? "account-break-away-label" inactive)))
        (is (true? (hidden? "account-break-status-copy" inactive)))
        (is (true? (hidden? "account-break-status-scheduled" inactive)))
        (is (true? (hidden? "account-break-status-ended" inactive)))
        (is (true? (hidden? "account-break-status-fields" inactive)))
        (is (true? (hidden? "account-break-start-date-row" inactive)))
        (is (true? (hidden? "account-break-end-early" inactive)))

        (is (true? (hidden? "account-break-available-label" scheduled)))
        (is (false? (hidden? "account-break-away-label" scheduled)))
        (is (false? (hidden? "account-break-status-copy" scheduled)))
        (is (false? (hidden? "account-break-status-scheduled" scheduled)))
        (is (true? (hidden? "account-break-status-ended" scheduled)))
        (is (false? (hidden? "account-break-status-fields" scheduled)))
        (is (true? (hidden? "account-break-start-date-row" scheduled)))
        (is (false? (hidden? "account-break-end-early" scheduled)))
        (is (contains?
             (support/class-tokens
              (support/element-by-id "account-break-avatar-frame" scheduled))
             "paused"))
        (doseq [id ["account-break-available-label"
                    "account-break-away-label"
                    "account-break-status-copy"
                    "account-break-status-scheduled"
                    "account-break-status-ended"
                    "account-break-status-fields"
                    "account-break-start-date-row"
                    "account-break-end-early"]]
          (is (string? (:data-attr:hidden
                        (support/attrs
                         (support/element-by-id id scheduled))))))))))

(deftest break-feedback-shares-a-spaced-stack-with-the-break-summary
  (let [page (support/public-fn 'app.account.break.views/page)]
    (is (fn? page) "app.account.break.views/page should exist")
    (when page
      (let [view (page
                  (support/request
                   {:page-state
                    {:account-break
                     {:_feedback
                      [:i18n/tr :account-settings/break-ended-feedback]}}}))
            content (support/element-by-id "account-break-card-content" view)
            feedback (support/element-by-id "account-break-feedback" view)]
        (is (contains? (support/class-tokens content) "wa-stack"))
        (is (contains? (support/class-tokens content) "wa-gap-l"))
        (is (some #{feedback} (tree-seq coll? seq content)))))))

(deftest break-explanation-uses-the-configured-instance-name
  (let [page (support/public-fn 'app.account.break.views/page)]
    (is (fn? page) "app.account.break.views/page should exist")
    (when page
      (let [view (page (support/request
                        {:system {:env {:name "The Test Band"}}}))]
        (doseq [message-id [:account-settings/break-explanation-intro
                            :account-settings/break-explanation-avatar-description]]
          (is (= {:instance-name "The Test Band"}
                 (nth (support/translation-node message-id view) 2))))))))
