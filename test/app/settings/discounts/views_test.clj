(ns app.settings.discounts.views-test
  (:require
   [app.settings.discounts.views :as views]
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
    :band-settings/travel-discount-type-add})

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
