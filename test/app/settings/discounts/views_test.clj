(ns app.settings.discounts.views-test
  (:require
   [app.settings.discounts.views :as views]
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
    :action/remove
    :action/save
    :action/update
    :band-settings/title
    :band-settings/travel-discount-delete-confirm
    :band-settings/travel-discount-manage-subtitle
    :band-settings/travel-discount-manage-title
    :band-settings/travel-discount-page-subtitle
    :band-settings/travel-discount-title
    :band-settings/travel-discount-type-add
    :band-settings/travel-discount-type-name
    :band-settings/toolbar-label
    :status-active
    :status-label})

(def empty-translation-keys
  #{:band-settings/title
    :band-settings/travel-discount-empty-subtitle
    :band-settings/travel-discount-empty-title
    :band-settings/travel-discount-manage-subtitle
    :band-settings/travel-discount-manage-title
    :band-settings/travel-discount-page-subtitle
    :band-settings/travel-discount-title
    :band-settings/travel-discount-type-add
    :band-settings/toolbar-label})

(defn page-view [populated?]
  (let [{:keys [conn]} (tc/new-system "settings-discount-views")
        discount-type-id (random-uuid)]
    (when populated?
      @(d/transact conn [{:travel.discount.type/discount-type-id   discount-type-id
                          :travel.discount.type/discount-type-name "Klimaticket"
                          :travel.discount.type/enabled?           true}]))
    (views/page
     {:db         (d/db conn)
      :page-state (if populated?
                    {:discount-type-create {:open true}
                     :discount-type        {:discount-type-id discount-type-id}}
                    {})
      :tr         support/legacy-tr
      ::r/router  support/router})))

(deftest travel-discounts-page-returns-translation-data-test
  (let [view (page-view true)]
    (testing "Travel Discounts uses Band Settings as its desktop and mobile parent context."
      (is (= {:width             :standard
              :toolbar-label     :band-settings/toolbar-label
              :breadcrumbs       [:band-settings/title
                                  :band-settings/travel-discount-title]
              :mobile            {:href "/band-settings"
                                  :label :band-settings/title}
              :actions           []
              :overflow          []
              :heading           :band-settings/travel-discount-title
              :subtitle          :band-settings/travel-discount-page-subtitle
              :header-breadcrumb nil}
             (page-shell/page-contract view {:include-header? true}))))
    (testing "Dialogs follow the visible travel-discount content."
      (is (= [:section :wa-dialog :wa-dialog :wa-dialog]
             (mapv first
                   (l/children
                    (l/select-one "#travel-discount-types" view))))))
    (is (= {:root             :main
            :translation-keys populated-translation-keys}
           {:root             (first view)
            :translation-keys (support/translation-keys view)}))))

(deftest travel-discounts-empty-state-returns-translation-data-test
  (let [view (page-view false)]
    (is (= {:root             :main
            :translation-keys empty-translation-keys}
           {:root             (first view)
            :translation-keys (support/translation-keys view)}))))
