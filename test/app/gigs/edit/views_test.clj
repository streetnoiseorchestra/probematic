(ns app.gigs.edit.views-test
  (:require
   [app.gigs.edit.views :as views]
   [app.gigs.view-test-support :as support]
   [app.ui2.page-shell-test-support :as page-shell]
   [clojure.test :refer [deftest is testing]]))

(defn- save-action []
  {:label      :action/save
   :form       "gig-edit-form"
   :type       "submit"
   :appearance "filled"
   :variant    "brand"})

(deftest create-gig-page-surface
  (testing "New Gig uses the edit toolbar without a destructive overflow."
    (let [{:keys [conn]} (support/new-system "gig-create-surface")]
      (is (= {:contract
              {:width       :wide
               :breadcrumbs [:gigs/title :gigs/new-gig]
               :mobile      {:label :gigs/title :href "/gigs"}
               :actions     [{:label      :action/cancel
                              :href       "/gigs"
                              :appearance "plain"}
                             (save-action)]
               :overflow    []}
              :heading     :gigs/new-gig
              :subtitle    nil
              :form-id     "gig-edit-form"
              :last-tag    :app.ui2.page-surface/page-surface
              :last-id     nil}
             (-> conn support/request views/page page-shell/page-structure))))))

(deftest edit-gig-page-surface
  (testing "Edit keeps Cancel and Save visible and moves Delete into overflow."
    (let [{:keys [conn]} (support/new-system "gig-edit-surface")
          gig-id         (random-uuid)]
      (support/seed-gig! conn gig-id)
      (is (= {:contract
              {:width       :wide
               :breadcrumbs [:gigs/title "Summer Concert" :action/edit]
               :mobile      {:label "Summer Concert"
                             :href  (str "/gig/" gig-id)}
               :actions     [{:label      :action/cancel
                              :href       (str "/gig/" gig-id)
                              :appearance "plain"}
                             (save-action)]
               :overflow    [{:label       :action/delete
                              :data-dialog (str "open gig-remove-" gig-id)
                              :variant     "danger"}]}
              :heading "Summer Concert"
              :subtitle "gig"
              :form-id  "gig-edit-form"
              :last-tag :wa-dialog
              :last-id  (str "gig-remove-" gig-id)}
             (-> conn
                 (support/request {:path-params {:gig/gig-id gig-id}})
                 views/page
                 page-shell/page-structure))))))

(deftest edit-gig-omits-redundant-type-subtitle
  (testing "The event type is not repeated beneath an identical gig title."
    (let [{:keys [conn]} (support/new-system "gig-edit-redundant-subtitle")
          gig-id         (random-uuid)]
      (support/seed-gig! conn gig-id {:gig/title    "Probe"
                                      :gig/gig-type :gig.type/probe})
      (is (nil? (-> conn
                    (support/request {:path-params {:gig/gig-id gig-id}})
                    views/page
                    page-shell/page-structure
                    :subtitle))))))
