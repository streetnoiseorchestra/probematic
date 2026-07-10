(ns app.settings.sections.views-test
  (:require
   [app.settings.sections.views :as views]
   [app.settings.views-test-support :as support]
   [app.test-common :as tc]
   [clojure.test :refer [deftest is]]
   [datomic.api :as d]
   [reitit.core :as r]))

(def populated-translation-keys
  #{:action/cancel
    :action/confirm-delete
    :action/confirm-generic
    :action/create
    :action/done
    :action/remove
    :action/reorder
    :action/save
    :action/update
    :band-settings/section-add
    :band-settings/section-delete-confirm
    :band-settings/section-manage-subtitle
    :band-settings/section-manage-title
    :band-settings/section-name
    :band-settings/section-page-subtitle
    :band-settings/section-reorder-instructions
    :band-settings/section-title
    :band-settings/title
    :status-active
    :status-label})

(def empty-translation-keys
  #{:action/reorder
    :band-settings/section-add
    :band-settings/section-empty-subtitle
    :band-settings/section-empty-title
    :band-settings/section-manage-subtitle
    :band-settings/section-manage-title
    :band-settings/section-page-subtitle
    :band-settings/section-title
    :band-settings/title})

(defn page-view [populated?]
  (let [{:keys [conn]} (tc/new-system "settings-section-views")]
    (when populated?
      @(d/transact conn [{:section/name     "Trumpets"
                          :section/active?  true
                          :section/position 0}]))
    (views/page
     {:db         (d/db conn)
      :page-state (if populated?
                    {:section-create  {:open true}
                     :section         {:section-id "Trumpets"}
                     :section-reorder {:open true}}
                    {})
      :tr         support/legacy-tr
      ::r/router  support/router})))

(deftest sections-page-returns-translation-data-test
  (let [view (page-view true)]
    (is (= {:root             :main
            :translation-keys populated-translation-keys}
           {:root             (first view)
            :translation-keys (support/translation-keys view)}))))

(deftest sections-empty-state-returns-translation-data-test
  (let [view (page-view false)]
    (is (= {:root             :main
            :translation-keys empty-translation-keys}
           {:root             (first view)
            :translation-keys (support/translation-keys view)}))))
