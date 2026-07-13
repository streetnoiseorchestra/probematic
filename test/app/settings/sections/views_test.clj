(ns app.settings.sections.views-test
  (:require
   [app.settings.sections.views :as views]
   [app.settings.views-test-support :as support]
   [app.test-common :as tc]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]
   [datomic.api :as d]
   [lookup.core :as l]
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
    :band-settings/toolbar-label
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
    :band-settings/title
    :band-settings/toolbar-label})

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
    (testing "Sections uses Band Settings as its desktop and mobile parent context."
      (is (= {:width             :standard
              :toolbar-label     :band-settings/toolbar-label
              :breadcrumbs       [:band-settings/title
                                  :band-settings/section-title]
              :mobile            {:href "/band-settings"
                                  :label :band-settings/title}
              :actions           []
              :overflow          []
              :heading           :band-settings/section-title
              :subtitle          :band-settings/section-page-subtitle
              :header-breadcrumb nil}
             (page-shell/page-contract view {:include-header? true}))))
    (testing "Dialogs follow the visible section-management content."
      (is (= [:section :wa-dialog :wa-dialog :wa-dialog :wa-dialog]
             (mapv first (l/children (l/select-one "#sections-panel" view))))))
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
